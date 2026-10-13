package dev.slne.surf.polarpaper.runtime.paper

import com.github.shynixn.mccoroutine.folia.globalRegionDispatcher
import com.google.auto.service.AutoService
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.common.world.PlatformWorldBridge
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import live.minehub.polarpaper.Polar
import live.minehub.polarpaper.core.source.BytesPolarSource
import org.bukkit.Bukkit
import org.bukkit.World
import kotlin.coroutines.CoroutineContext

@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper(
    // World unloading must happen on the global region thread (the main thread on Paper)
    private val globalRegionContext: () -> CoroutineContext = { plugin.globalRegionDispatcher }
) : PlatformWorldBridge {
    override suspend fun createWorld(world: SurfWorld) {
        loadWorld(world)
    }

    override suspend fun loadWorld(world: SurfWorld) {
        if (Bukkit.getWorld(world.name) != null) {
            return
        }

        val source = BytesPolarSource(world.worldBytes)

        Polar.createWorld(source, world.name).await()
            ?: throw IllegalStateException("Polar failed to create platform world for $world")
    }

    override suspend fun saveWorld(world: SurfWorld) {
        val platformWorld = getPlatformWorld<World>(world)
            ?: throw IllegalStateException("World $world is not loaded")

        val source = BytesPolarSource()
        Polar.saveWorld(platformWorld, source).await()
        world.worldBytes = source.bytes()
    }

    override suspend fun unloadWorld(world: SurfWorld) {
        val platformWorld = getPlatformWorld<World>(world) ?: return

        val unloaded = withContext(globalRegionContext()) {
            Bukkit.unloadWorld(platformWorld, false)
        }

        check(unloaded) {
            "Bukkit refused to unload world $world, players may still be inside it"
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <W> getPlatformWorld(world: SurfWorld): W? {
        return Bukkit.getWorld(world.name) as? W
    }
}
