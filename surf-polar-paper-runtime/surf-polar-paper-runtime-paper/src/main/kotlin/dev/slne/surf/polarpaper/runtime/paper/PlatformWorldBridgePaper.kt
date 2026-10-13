package dev.slne.surf.polarpaper.runtime.paper

import com.google.auto.service.AutoService
import dev.jorel.commandapi.CommandAPI
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.common.world.PlatformWorldBridge
import kotlinx.coroutines.future.await
import live.minehub.polarpaper.Polar
import live.minehub.polarpaper.core.source.BytesPolarSource
import org.bukkit.Bukkit
import org.bukkit.World

@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper : PlatformWorldBridge {
    override suspend fun createWorld(world: SurfWorld) {
        loadWorld(world)
    }

    override suspend fun loadWorld(world: SurfWorld) {
        if (Bukkit.getWorld(world.name) != null) {
            return
        }

        val worldName = world.name
        val source = BytesPolarSource(world.worldBytes)

        Polar.createWorld(source, worldName).await()
            ?: throw CommandAPI.failWithString("Failed to create/load platform world for $world")
    }

    override suspend fun saveWorld(world: SurfWorld) {
        val platformWorld = getPlatformWorld<World>(world)
            ?: throw IllegalStateException("World ${world.uuid} is not loaded")

        val source = BytesPolarSource()
        Polar.saveWorld(platformWorld, source).await()
        world.worldBytes = source.bytes()
    }

    override suspend fun unloadWorld(world: SurfWorld) {
        saveWorld(world)

        Bukkit.unloadWorld(world.uuid.toString(), false)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <W> getPlatformWorld(world: SurfWorld): W? {
        return Bukkit.getWorld(world.uuid.toString()) as? W
    }
}