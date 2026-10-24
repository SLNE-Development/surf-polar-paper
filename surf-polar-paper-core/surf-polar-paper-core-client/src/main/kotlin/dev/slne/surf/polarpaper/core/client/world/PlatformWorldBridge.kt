package dev.slne.surf.polarpaper.core.client.world

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.SurfChunk
import org.bukkit.World

/**
 * Connects the API to the platform's worlds. Service-loaded, implemented in runtime-paper.
 */
interface PlatformWorldBridge {
    /**
     * Loads [world] from [polarBytes], or as a new empty world when null.
     *
     * Order: raw stages, then the section check when no migration has a live stage, then the platform world,
     * then force-load and live stages, then the section check. Fills [SurfWorldImpl.data] from the Polar world
     * user data. On failure the platform world is unloaded without saving and the exception propagates.
     */
    suspend fun loadWorld(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>)

    /** Serializes the world to Polar bytes, including sections and [SurfWorldImpl.data]. */
    suspend fun saveWorld(world: SurfWorldImpl): ByteArray

    /**
     * Like [saveWorld] for the server shutdown: the main thread is blocked then, so it must not wait for a save that
     * is stuck behind it. Waits only a bounded time for other saves of the world.
     */
    suspend fun saveWorldForShutdown(world: SurfWorldImpl): ByteArray = saveWorld(world)

    /** Unloads the platform world without saving it. Does nothing if the world is not loaded. */
    suspend fun unloadWorld(world: SurfWorldImpl)

    fun bukkitWorld(world: SurfWorldImpl): World?

    fun chunk(world: SurfWorldImpl, x: Int, z: Int): SurfChunk?

    companion object {
        val INSTANCE: PlatformWorldBridge by lazy { requiredService<PlatformWorldBridge>() }
    }
}
