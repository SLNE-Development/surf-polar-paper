package dev.slne.surf.polarpaper.core.client.fakes

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import org.bukkit.World

/**
 * Keeps "platform worlds" as the bytes they were loaded with and records every call.
 */
class FakePlatformWorldBridge : PlatformWorldBridge {
    val platformWorlds = mutableMapOf<SurfWorldImpl, ByteArray>()

    /** Calls in order: `load:<uuid>:<migration target versions>`, `save:<uuid>`, `unload:<uuid>`. */
    val calls = mutableListOf<String>()

    /** Bytes the next save returns, defaults to the bytes the world was loaded with. */
    var savedBytes: ((SurfWorldImpl) -> ByteArray)? = null
    val failingUnloads = mutableSetOf<SurfWorldImpl>()
    var failOnLoad: Exception? = null

    /** When set, loads wait for it, so tests can interleave concurrent callers. */
    var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null


    override suspend fun loadWorld(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>) {
        calls += "load:${world.name}:${migrations.map { it.toVersion }}"
        loadGate?.await()
        failOnLoad?.let { throw it }

        platformWorlds[world] = polarBytes ?: ByteArray(0)
    }

    override suspend fun saveWorld(world: SurfWorldImpl): ByteArray {
        calls += "save:${world.name}"
        val platformBytes = platformWorlds[world] ?: error("World $world is not loaded")

        return savedBytes?.invoke(world) ?: platformBytes
    }

    override suspend fun unloadWorld(world: SurfWorldImpl) {
        calls += "unload:${world.name}"
        if (world in failingUnloads) error("Players are still inside $world")

        platformWorlds.remove(world)
    }

    override fun bukkitWorld(world: SurfWorldImpl): World? = null

    override fun chunk(world: SurfWorldImpl, x: Int, z: Int): SurfChunk? = null

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}
