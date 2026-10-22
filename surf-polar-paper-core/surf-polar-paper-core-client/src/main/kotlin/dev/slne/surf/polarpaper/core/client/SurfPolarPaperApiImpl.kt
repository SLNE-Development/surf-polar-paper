package dev.slne.surf.polarpaper.core.client

import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.core.util.toObjectSet
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.GameModeSettings
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.UnhandledWorldDataException
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.api.world.WorldMigrationException
import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import dev.slne.surf.polarpaper.core.client.lock.DisabledWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.WorldLocks
import dev.slne.surf.polarpaper.core.client.migration.MigrationPlanner
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.polarpaper.core.common.world.key
import it.unimi.dsi.fastutil.objects.ObjectSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.plugin.Plugin
import org.jetbrains.annotations.UnmodifiableView
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * The API instance of one plugin. Only worlds of the game modes in [settings] can be used through it.
 * Create it through [SurfPolarPaperApi.create] so that game mode ownership is claimed.
 */
class SurfPolarPaperApiImpl internal constructor(
    override val plugin: Plugin,
    override val settings: PolarPaperSettings,
    private val storageProviderSupplier: () -> StorageProvider?,
    private val platformBridgeSupplier: () -> PlatformWorldBridge,
    private val worldLocksSupplier: () -> WorldLocks = { DisabledWorldLocks },
    autosaveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SurfPolarPaperApi {
    private val log = logger()

    private val worldCache = Caffeine.newBuilder()
        .build<WorldCacheKey, SurfWorldImpl>()

    private val platformBridge get() = platformBridgeSupplier()
    private val worldLocks get() = worldLocksSupplier()
    private val closed = AtomicBoolean(false)
    private val loadMutexes = ConcurrentHashMap<WorldCacheKey, Mutex>()

    private val autosaveJob: Job? = settings.autosaveInterval?.let { interval ->
        autosaveScope.launch {
            while (true) {
                delay(interval)
                try {
                    saveAllWorlds()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // A save that was in flight when the API closed fails on the closed API, which is no news
                    if (closed.get()) throw CancellationException("API of plugin ${plugin.name} closed", exception)
                    log.atSevere().withCause(exception).log("Autosave of plugin ${plugin.name} failed")
                }
            }
        }
    }

    override val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>
        get() {
            requireOpen()
            return loadedSnapshot()
        }

    private fun loadedSnapshot(): ObjectSet<SurfWorld> = worldCache.asMap().values.toObjectSet()

    /** A closed instance has released its game modes, so using it any further would write worlds it no longer owns. */
    private fun requireOpen() {
        check(!closed.get()) { "The API of plugin '${plugin.name}' is closed" }
    }

    private fun ensureStorageProvider() = storageProviderSupplier()
        ?: throw IllegalStateException("Storage provider is not initialized")

    private inline fun <T> withStorageProvider(action: (storageProvider: StorageProvider) -> T): T =
        action(ensureStorageProvider())

    /** Validates the identifier and that this instance owns the game mode. */
    private fun requireOwned(gameModeIdentifier: String): GameModeSettings {
        GameModeIdentifiers.requireValid(gameModeIdentifier)

        return requireNotNull(settings.gameModes[gameModeIdentifier]) {
            "Game mode '$gameModeIdentifier' is not owned by plugin '${plugin.name}'"
        }
    }

    private fun implOf(world: SurfWorld): SurfWorldImpl {
        require(world is SurfWorldImpl && world.api === this) {
            "World $world does not belong to the API of plugin '${plugin.name}'"
        }

        return world
    }

    private suspend fun loadWorldPlatform(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>) {
        try {
            platformBridge.loadWorld(world, polarBytes, migrations)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // These already say what went wrong, wrapping them would hide the type from callers
            if (exception is WorldMigrationException
                || exception is UnhandledWorldDataException
                || exception is WorldVersionTooNewException
            ) {
                throw exception
            }

            throw IllegalStateException("Failed to load platform world $world", exception)
        }
    }

    private suspend fun unloadWorldPlatform(world: SurfWorldImpl) {
        try {
            platformBridge.unloadWorld(world)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException("Failed to unload platform world $world", exception)
        }
    }

    /**
     * Cleans up after a failed load or create: unloads the platform world and releases the lock. Runs
     * non-cancellable so a cancelled caller cannot leave the world locked; a failure here must not hide the
     * original exception.
     */
    private suspend fun discardFailedLoad(world: SurfWorldImpl?, key: WorldCacheKey, releaseLock: Boolean) {
        withContext(NonCancellable) {
            try {
                if (world != null) platformBridge.unloadWorld(world)
            } catch (exception: Exception) {
                log.atWarning().withCause(exception).log("Failed to unload platform world $world after a failed load")
            } finally {
                // A lock this server already held belongs to a world that is still loaded, e.g. one orphaned by
                // an earlier instance, and must stay
                if (releaseLock) releaseLock(key)
            }
        }
    }

    private fun mutexFor(key: WorldCacheKey) = loadMutexes.computeIfAbsent(key) { Mutex() }

    override suspend fun createWorld(
        worldUuid: UUID,
        gameModeIdentifier: String,
        polarBytes: ByteArray?,
        worldVersion: Int?
    ): SurfWorld {
        requireOpen()
        val gameMode = requireOwned(gameModeIdentifier)
        val key = WorldCacheKey(worldUuid, gameModeIdentifier)

        return mutexFor(key).withLock {
            // Locking again would be a no-op for this server, and a failure would then unload the live world
            check(worldCache.getIfPresent(key) == null) {
                "World $gameModeIdentifier-$worldUuid is already loaded"
            }

            val templateVersion = worldVersion ?: gameMode.currentVersion
            val world = SurfWorldImpl(worldUuid, gameModeIdentifier, templateVersion, this, platformBridge)

            val heldBefore = worldLocks.isHeld(key)
            worldLocks.lock(key)
            try {
                val migrations = MigrationPlanner.plan(worldUuid, gameMode, templateVersion)

                loadWorldPlatform(world, polarBytes, migrations)
                world.version = gameMode.currentVersion
                saveLocked(world, shutdown = false)
            } catch (exception: Exception) {
                discardFailedLoad(world, key, releaseLock = !heldBefore)
                throw exception
            }

            worldCache.put(key, world)
            log.atInfo().log("Created world $world")

            world
        }
    }

    override suspend fun loadWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld = withStorageProvider { storageProvider ->
        requireOpen()
        val gameMode = requireOwned(gameModeIdentifier)

        val key = WorldCacheKey(worldUuid, gameModeIdentifier)
        worldCache.getIfPresent(key)?.let { return it }

        // Concurrent callers for the same world wait here and then find it cached
        mutexFor(key).withLock {
            worldCache.getIfPresent(key)?.let { return@withLock it }

            // Locked before reading, so no other server can change the world while it is loaded here
            val heldBefore = worldLocks.isHeld(key)
            worldLocks.lock(key)

            var world: SurfWorldImpl? = null
            try {
                val storedBytes = storageProvider.loadWorldBytes(worldUuid, gameModeIdentifier)
                    ?: throw IllegalStateException("World with UUID $worldUuid and game mode $gameModeIdentifier does not exist")
                val stored = WorldStorageCodec.decode(storedBytes)
                val migrations = MigrationPlanner.plan(worldUuid, gameMode, stored.worldVersion)

                val loading = SurfWorldImpl(worldUuid, gameModeIdentifier, stored.worldVersion, this, platformBridge)
                world = loading

                loadWorldPlatform(loading, stored.worldBytes, migrations)
                loading.version = gameMode.currentVersion

                // Persist the migrated state right away, so the migrations never have to run twice
                if (stored.worldVersion != gameMode.currentVersion) {
                    saveLocked(loading, shutdown = false)
                }

                worldCache.put(key, loading)
                log.atInfo().log("Loaded world $loading")

                loading
            } catch (exception: Exception) {
                discardFailedLoad(world, key, releaseLock = !heldBefore)
                throw exception
            }
        }
    }

    override suspend fun saveWorld(world: SurfWorld) {
        requireOpen()
        val impl = implOf(world)
        requireOwned(impl.gameModeIdentifier)

        // Serialized with unloads and deletes of the same world: a save that is in flight must not write after them
        mutexFor(impl.key()).withLock { saveLocked(impl, shutdown = false) }
    }

    /** Saves [impl] and writes it to storage. The caller holds the mutex of the world, or is the shutdown. */
    private suspend fun saveLocked(
        impl: SurfWorldImpl,
        shutdown: Boolean
    ) = withStorageProvider { storageProvider ->
        requireOwned(impl.gameModeIdentifier)

        // Without the lock another server may have loaded and changed the world, saving would overwrite that
        check(worldLocks.isHeld(impl.key())) {
            "Not saving world $impl, this server no longer holds its lock"
        }

        val polarBytes = try {
            if (shutdown) platformBridge.saveWorldForShutdown(impl) else platformBridge.saveWorld(impl)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalStateException("Failed to save platform world $impl", exception)
        }

        // The save took a while, the lock may have been lost or released meanwhile
        check(worldLocks.isHeld(impl.key())) {
            "Not writing world $impl, this server no longer holds its lock"
        }
        storageProvider.writeWorldBytes(
            impl.uuid,
            impl.gameModeIdentifier,
            WorldStorageCodec.encode(impl.version, polarBytes)
        )

        log.atInfo().log("Saved world $impl")
    }

    override suspend fun unloadWorld(
        world: SurfWorld,
        saveChanges: Boolean
    ) {
        requireOpen()
        val impl = implOf(world)

        mutexFor(impl.key()).withLock { unloadLocked(impl, saveChanges) }
    }

    private suspend fun unloadLocked(impl: SurfWorldImpl, saveChanges: Boolean) {
        val key = impl.key()

        if (saveChanges) {
            saveLocked(impl, shutdown = false)
        }

        unloadWorldPlatform(impl)
        worldCache.invalidate(key)
        releaseLock(key)

        log.atInfo().log("Unloaded world $impl")
    }

    override suspend fun deleteWorld(
        world: SurfWorld
    ) = withStorageProvider { storageProvider ->
        requireOpen()
        requireOwned(world.gameModeIdentifier)

        val key = world.key()
        mutexFor(key).withLock {
            // A world that is not loaded here is locked for the deletion, so it cannot be deleted while loaded elsewhere
            if (!worldLocks.isHeld(key)) {
                worldLocks.lock(key)
            }

            val loaded = worldCache.getIfPresent(key)
            if (loaded != null) {
                // A refused unload leaves the world loaded, so its lock stays held and nothing is deleted
                unloadWorldPlatform(loaded)
                worldCache.invalidate(key)
            }

            try {
                storageProvider.deleteWorldBytes(world.uuid, world.gameModeIdentifier)
            } finally {
                releaseLock(key)
            }
        }

        log.atInfo().log("Deleted world $world")
    }

    private suspend fun releaseLock(key: WorldCacheKey) {
        try {
            worldLocks.unlock(key)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            // The lease runs out on its own, so the world only stays unavailable for a little while
            log.atWarning().withCause(exception).log("Failed to release the lock of world $key")
        }
    }

    override suspend fun unloadAllWorlds(saveChanges: Boolean) {
        requireOpen()
        forEachLoadedWorld("unload") { world ->
            unloadWorld(world, saveChanges)
        }
    }

    override suspend fun saveAllWorlds() {
        requireOpen()
        forEachLoadedWorld("save") { world ->
            saveWorld(world)
        }
    }

    /**
     * Closes the instance while the server keeps running, e.g. when its plugin is disabled at runtime: saves every
     * world, unloads it and releases its lock, and frees the game modes. A world that Bukkit refuses to unload (players
     * inside, a default world) stays saved, locked and loaded in the platform, with a warning. For a server stop use
     * [closeForShutdown].
     */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return

        autosaveJob?.cancelAndJoin()
        try {
            forEachLoadedWorld("close") { world -> closeWorld(implOf(world)) }
        } finally {
            ApiInstances.unregister(this)
        }
    }

    private suspend fun closeWorld(impl: SurfWorldImpl) {
        val key = impl.key()

        mutexFor(key).withLock {
            // A world that cannot be saved stays loaded, unloading it would lose its changes
            saveLocked(impl, shutdown = false)

            try {
                unloadWorldPlatform(impl)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.atWarning().withCause(exception).log(
                    "Could not unload world $impl while closing the API of plugin ${plugin.name}: it stays saved, loaded and locked"
                )
                return@withLock
            }

            worldCache.invalidate(key)
            releaseLock(key)
        }
    }

    /**
     * [close] for the server shutdown, where the disabling thread is blocked: an autosave that is in flight may be
     * queued behind that thread and never finish, so it is cancelled without waiting for it, and the saves only wait
     * a bounded time for each other. Worlds are only saved and never unloaded: Paper's world manager is already
     * closed at this point and worlds with players cannot be unloaded. Locks are released when the server
     * disconnects.
     */
    suspend fun closeForShutdown() {
        if (!closed.compareAndSet(false, true)) return

        autosaveJob?.cancel()
        try {
            forEachLoadedWorld("save") { world -> saveForShutdown(implOf(world)) }
        } finally {
            ApiInstances.unregister(this)
        }
    }

    private suspend fun saveForShutdown(impl: SurfWorldImpl) {
        val mutex = mutexFor(impl.key())
        // Normally free, because the autosave was cancelled. Only a save that is stuck can make this wait, and the
        // blocked thread must not wait for that forever.
        val locked = mutex.tryLock() || (withTimeoutOrNull(SHUTDOWN_MUTEX_WAIT) { mutex.lock(); true } ?: false)
        if (!locked) log.atWarning().log("Saving world $impl without waiting any longer for a save that is still running")

        try {
            saveLocked(impl, shutdown = true)
        } finally {
            if (locked) mutex.unlock()
        }
    }

    /**
     * Runs [action] for every loaded world and keeps going when it fails for one, so a single broken world cannot block
     * the others, e.g. during shutdown. All failures are thrown together afterwards.
     */
    private suspend inline fun forEachLoadedWorld(actionName: String, action: (SurfWorld) -> Unit) {
        val worlds = loadedSnapshot()
        val failures = mutableListOf<Exception>()

        for (world in worlds) {
            try {
                action(world)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.atSevere().withCause(exception).log("Failed to $actionName world $world")
                failures += exception
            }
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to $actionName ${failures.size} of ${worlds.size} worlds").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    override fun findWorld(worldUuid: UUID, gameModeIdentifier: String): SurfWorld? {
        requireOpen()
        return worldCache.getIfPresent(WorldCacheKey(worldUuid, gameModeIdentifier))
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String,
        showLoadedOnly: Boolean
    ): List<WorldInfo> {
        requireOpen()
        requireOwned(gameModeIdentifier)

        return if (showLoadedOnly) {
            loadedWorlds
                .filter { it.gameModeIdentifier == gameModeIdentifier }
                .map { it.info() }
        } else {
            withStorageProvider { storageProvider ->
                storageProvider.listWorlds(gameModeIdentifier)
            }
        }
    }

    private companion object {
        /** How long a shutdown save waits for a save of the same world that is still running. */
        val SHUTDOWN_MUTEX_WAIT = 10.seconds
    }
}
