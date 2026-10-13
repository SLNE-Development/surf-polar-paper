package dev.slne.surf.polarpaper.runtime.paper

import com.github.shynixn.mccoroutine.folia.globalRegionDispatcher
import com.google.auto.service.AutoService
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.common.world.PlatformWorldBridge
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import live.minehub.polarpaper.Polar
import live.minehub.polarpaper.core.source.BytesPolarSource
import live.minehub.polarpaper.core.world.PolarWorld
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import kotlin.coroutines.CoroutineContext

@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper(
    // World unloading must happen on the global region thread (the main thread on Paper)
    private val globalRegionContext: () -> CoroutineContext = { plugin.globalRegionDispatcher },
    private val emptyPolarWorld: () -> PolarWorld = { PolarWorld(EMPTY_WORLD_MIN_SECTION, EMPTY_WORLD_MAX_SECTION) }
) : PlatformWorldBridge {
    override suspend fun createWorld(world: SurfWorld) {
        loadWorld(world)
    }

    override suspend fun loadWorld(world: SurfWorld) {
        if (getPlatformWorld<World>(world) != null) {
            return
        }

        val platformWorld = if (world.worldBytes.isEmpty()) {
            // A new world has no Polar data yet, which Polar cannot read, so start from an empty world instead
            Polar.createWorld(emptyPolarWorld(), world.name)
        } else {
            Polar.createWorld(BytesPolarSource(world.worldBytes), world.name)
        }

        platformWorld.await()
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
        return Bukkit.getWorld(worldKey(world)) as? W
    }

    companion object {
        // Polar registers the worlds it creates under its own namespace, so they cannot be found by plain name
        const val POLAR_NAMESPACE = "polarpaper"

        fun worldKey(world: SurfWorld) = NamespacedKey(POLAR_NAMESPACE, world.name)

        // Overworld height, Y -64 to 319
        const val EMPTY_WORLD_MIN_SECTION: Byte = -4
        const val EMPTY_WORLD_MAX_SECTION: Byte = 19
    }
}
