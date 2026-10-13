package dev.slne.surf.polarpaper.core.client.fakes

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.common.world.PlatformWorldBridge

/**
 * Keeps "platform worlds" as the bytes they were loaded with and records every call.
 */
class FakePlatformWorldBridge : PlatformWorldBridge {
    val platformWorlds = mutableMapOf<SurfWorld, ByteArray>()
    val calls = mutableListOf<String>()

    /** Bytes the next save writes into the world, defaults to the bytes the world was loaded with. */
    var savedBytes: ((SurfWorld) -> ByteArray)? = null
    val failingUnloads = mutableSetOf<SurfWorld>()
    var failOnLoad: Exception? = null

    override suspend fun createWorld(world: SurfWorld) {
        calls += "create:${world.name}"
        platformWorlds[world] = world.worldBytes
    }

    override suspend fun saveWorld(world: SurfWorld) {
        calls += "save:${world.name}"
        val platformBytes = platformWorlds[world] ?: error("World $world is not loaded")

        world.worldBytes = savedBytes?.invoke(world) ?: platformBytes
    }

    override suspend fun loadWorld(world: SurfWorld) {
        calls += "load:${world.name}"
        failOnLoad?.let { throw it }

        platformWorlds[world] = world.worldBytes
    }

    override suspend fun unloadWorld(world: SurfWorld) {
        calls += "unload:${world.name}"
        if (world in failingUnloads) error("Players are still inside $world")

        platformWorlds.remove(world)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <W> getPlatformWorld(world: SurfWorld): W? = platformWorlds[world] as W?

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}
