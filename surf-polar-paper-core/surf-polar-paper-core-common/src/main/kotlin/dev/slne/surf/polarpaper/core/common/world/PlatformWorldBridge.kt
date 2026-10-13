package dev.slne.surf.polarpaper.core.common.world

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.world.SurfWorld

private val bridge = requiredService<PlatformWorldBridge>()

interface PlatformWorldBridge {
    suspend fun createWorld(world: SurfWorld)
    suspend fun saveWorld(world: SurfWorld)
    suspend fun loadWorld(world: SurfWorld)
    suspend fun unloadWorld(world: SurfWorld)
    fun <W> getPlatformWorld(world: SurfWorld): W?

    companion object : PlatformWorldBridge by bridge {
        val INSTANCE get() = bridge
    }
}