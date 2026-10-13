package dev.slne.surf.polarpaper.core.client

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.auto.service.AutoService
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.core.util.toObjectSet
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
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
    private val platformBridgeSupplier: () -> PlatformWorldBridge
) : SurfPolarPaperApi {
    constructor() : this(
        storageProviderSupplier = { SurfPolarPaperInstance.INSTANCE.storageProvider },
        platformBridgeSupplier = { PlatformWorldBridge.INSTANCE }
    )

    private val log = logger()

    private val worldCache = Caffeine.newBuilder()
        .build<WorldCacheKey, SurfWorld>()

    private val platformBridge get() = platformBridgeSupplier()

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

        createWorldPlatform(world)
        saveWorld(world)

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

        val storedBytes = storageProvider.loadWorldBytes(worldUuid, gameModeIdentifier)
            ?: throw IllegalStateException("World with UUID $worldUuid and game mode $gameModeIdentifier does not exist")
        val worldBytes = WorldStorageCodec.decode(storedBytes)

        val world = SurfWorld(worldUuid, gameModeIdentifier, worldBytes)

        loadWorldPlatform(world)
        worldCache.put(key, world)

        log.atInfo().log("Loaded world $world")

        return world
    }

    override suspend fun saveWorld(
        world: SurfWorld
    ) = withStorageProvider { storageProvider ->
        GameModeIdentifiers.requireValid(world.gameModeIdentifier)

        saveWorldPlatform(world)

        storageProvider.writeWorldBytes(
            world.uuid,
            world.gameModeIdentifier,
            WorldStorageCodec.encode(world.worldBytes)
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

        log.atInfo().log("Unloaded world $world")
    }

    override suspend fun deleteWorld(
        world: SurfWorld
    ) = withStorageProvider { storageProvider ->
        GameModeIdentifiers.requireValid(world.gameModeIdentifier)

        unloadWorld(world, saveChanges = false)

        storageProvider.deleteWorldBytes(world.uuid, world.gameModeIdentifier)

        log.atInfo().log("Deleted world $world")
    }

    override suspend fun unloadAllWorlds(
        saveChanges: Boolean,
        beforeHook: (world: SurfWorld) -> Unit,
        afterHook: (world: SurfWorld) -> Unit
    ) {
        val worldsToUnload = loadedWorlds.toObjectSet()
        val failures = mutableListOf<Exception>()

        // Keep unloading the remaining worlds when one fails, so a single broken world cannot block shutdown
        for (world in worldsToUnload) {
            try {
                beforeHook(world)
                unloadWorld(world, saveChanges)
                afterHook(world)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.atSevere().withCause(exception).log("Failed to unload world $world")
                failures += exception
            }
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to unload ${failures.size} of ${worldsToUnload.size} worlds").apply {
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
