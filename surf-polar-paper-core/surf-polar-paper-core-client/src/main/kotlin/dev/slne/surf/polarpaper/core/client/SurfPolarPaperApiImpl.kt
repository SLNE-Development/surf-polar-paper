package dev.slne.surf.polarpaper.core.client

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.auto.service.AutoService
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.core.util.toObjectSet
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.lock.DisabledWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.WorldLocks
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.common.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import dev.slne.surf.polarpaper.core.common.world.key
import it.unimi.dsi.fastutil.objects.ObjectSet
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.UnmodifiableView
import java.util.*

@AutoService(SurfPolarPaperApi::class)
class SurfPolarPaperApiImpl internal constructor(
    private val storageProviderSupplier: () -> StorageProvider?,
    private val platformBridgeSupplier: () -> PlatformWorldBridge,
    private val worldLocksSupplier: () -> WorldLocks = { DisabledWorldLocks }
) : SurfPolarPaperApi {
    constructor() : this(
        storageProviderSupplier = { SurfPolarPaperInstance.INSTANCE.storageProvider },
        platformBridgeSupplier = { PlatformWorldBridge.INSTANCE },
        worldLocksSupplier = { SurfPolarPaperInstance.INSTANCE.worldLocks }
    )

    private val log = logger()

    private val worldCache = Caffeine.newBuilder()
        .build<WorldCacheKey, SurfWorld>()

    private val platformBridge get() = platformBridgeSupplier()
    private val worldLocks get() = worldLocksSupplier()

    override val loadedWorlds: @UnmodifiableView ObjectSet<SurfWorld>
        get() = worldCache.asMap().values.toObjectSet()

    private fun ensureStorageProvider() = storageProviderSupplier()
        ?: throw IllegalStateException("Storage provider is not initialized")

    private suspend fun createWorldPlatform(world: SurfWorld) {
        try {
            platformBridge.createWorld(world)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to create platform world $world",
                exception
            )
        }
    }

    private suspend fun loadWorldPlatform(world: SurfWorld) {
        try {
            platformBridge.loadWorld(world)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to load platform world $world",
                exception
            )
        }
    }

    private suspend fun saveWorldPlatform(world: SurfWorld) {
        try {
            platformBridge.saveWorld(world)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to save platform world $world",
                exception
            )
        }
    }

    private suspend fun unloadWorldPlatform(world: SurfWorld) {
        try {
            platformBridge.unloadWorld(world)
        } catch (exception: Exception) {
            throw IllegalStateException(
                "Failed to unload platform world $world",
                exception
            )
        }
    }

    override fun <W> getPlatformWorld(world: SurfWorld): W? {
        return platformBridge.getPlatformWorld(world)
    }

    private inline fun <T> withStorageProvider(action: (storageProvider: StorageProvider) -> T): T {
        val storageProvider = ensureStorageProvider()

        return action(storageProvider)
    }

    override suspend fun createWorld(
        worldUuid: UUID,
        gameModeIdentifier: String,
        worldBytes: ByteArray
    ): SurfWorld {
        GameModeIdentifiers.requireValid(gameModeIdentifier)

        val world = SurfWorld(worldUuid, gameModeIdentifier, worldBytes)

        worldLocks.lock(world.key())
        try {
            createWorldPlatform(world)
            saveWorld(world)
        } catch (exception: Exception) {
            releaseLock(world.key())
            throw exception
        }

        log.atInfo().log("Created world $world")

        return world
    }

    override suspend fun loadWorld(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): SurfWorld = withStorageProvider { storageProvider ->
        GameModeIdentifiers.requireValid(gameModeIdentifier)

        val key = WorldCacheKey(worldUuid, gameModeIdentifier)
        val cacheHit = worldCache.getIfPresent(key)

        if (cacheHit != null) {
            return cacheHit
        }

        // Locked before reading, so no other server can change the world while it is loaded here
        worldLocks.lock(key)

        val world = try {
            val storedBytes = storageProvider.loadWorldBytes(worldUuid, gameModeIdentifier)
                ?: throw IllegalStateException("World with UUID $worldUuid and game mode $gameModeIdentifier does not exist")
            val world = SurfWorld(worldUuid, gameModeIdentifier, WorldStorageCodec.decode(storedBytes).worldBytes)

            loadWorldPlatform(world)
            world
        } catch (exception: Exception) {
            releaseLock(key)
            throw exception
        }

        worldCache.put(key, world)

        log.atInfo().log("Loaded world $world")

        return world
    }

    override suspend fun saveWorld(
        world: SurfWorld
    ) = withStorageProvider { storageProvider ->
        GameModeIdentifiers.requireValid(world.gameModeIdentifier)

        // Without the lock another server may have loaded and changed the world, saving would overwrite that
        check(worldLocks.isHeld(world.key())) {
            "Not saving world $world, this server no longer holds its lock"
        }

        saveWorldPlatform(world)

        storageProvider.writeWorldBytes(
            world.uuid,
            world.gameModeIdentifier,
            WorldStorageCodec.encode(0, world.worldBytes)
        )
        worldCache.put(world.key(), world)

        log.atInfo().log("Saved world $world")
    }

    override suspend fun unloadWorld(
        world: SurfWorld,
        saveChanges: Boolean
    ) {
        if (saveChanges) {
            saveWorld(world)
        }

        unloadWorldPlatform(world)
        worldCache.invalidate(world.key())
        releaseLock(world.key())

        log.atInfo().log("Unloaded world $world")
    }

    override suspend fun deleteWorld(
        world: SurfWorld
    ) = withStorageProvider { storageProvider ->
        GameModeIdentifiers.requireValid(world.gameModeIdentifier)

        // A world that is not loaded here is locked for the deletion, so it cannot be deleted while loaded elsewhere
        val key = world.key()
        if (!worldLocks.isHeld(key)) {
            worldLocks.lock(key)
        }

        try {
            unloadWorldPlatform(world)
            worldCache.invalidate(key)

            storageProvider.deleteWorldBytes(world.uuid, world.gameModeIdentifier)
        } finally {
            releaseLock(key)
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

    override suspend fun unloadAllWorlds(
        saveChanges: Boolean,
        beforeHook: (world: SurfWorld) -> Unit,
        afterHook: (world: SurfWorld) -> Unit
    ) {
        forEachLoadedWorld("unload") { world ->
            beforeHook(world)
            unloadWorld(world, saveChanges)
            afterHook(world)
        }
    }

    override suspend fun saveAllWorlds() {
        forEachLoadedWorld("save") { world ->
            saveWorld(world)
        }
    }

    /**
     * Runs [action] for every loaded world and keeps going when it fails for one, so a single broken world cannot block
     * the others, e.g. during shutdown. All failures are thrown together afterwards.
     */
    private suspend inline fun forEachLoadedWorld(actionName: String, action: (SurfWorld) -> Unit) {
        val worlds = loadedWorlds.toObjectSet()
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
        val key = WorldCacheKey(worldUuid, gameModeIdentifier)

        return worldCache.getIfPresent(key)
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String,
        showLoadedOnly: Boolean
    ): List<WorldInfo> {
        GameModeIdentifiers.requireValid(gameModeIdentifier)

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
}
