package dev.slne.surf.polarpaper.runtime.paper

import com.google.auto.service.AutoService
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.pipeline.BukkitPaperPlatform
import dev.slne.surf.polarpaper.runtime.paper.pipeline.LoadedWorld
import dev.slne.surf.polarpaper.runtime.paper.pipeline.PaperPlatform
import dev.slne.surf.polarpaper.runtime.paper.pipeline.WorldLoadPipeline
import dev.slne.surf.polarpaper.runtime.paper.pipeline.WorldSavePipeline
import dev.slne.surf.polarpaper.runtime.paper.world.SurfChunkImpl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import live.minehub.polarpaper.core.world.PolarWorld
import org.bukkit.World
import java.util.concurrent.ConcurrentHashMap

/** Loads, saves and unloads the Polar worlds behind [SurfWorldImpl]. The steps live in the pipeline classes. */
@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper(
    private val platform: PaperPlatform,
    readWorld: ((ByteArray) -> PolarWorld)?
) : PlatformWorldBridge {
    constructor(platform: PaperPlatform) : this(platform, null)

    // Needed by the service loader
    constructor() : this(BukkitPaperPlatform())

    private val log = logger()
    private val loadPipeline = readWorld?.let { WorldLoadPipeline(platform, it) } ?: WorldLoadPipeline(platform)
    private val savePipeline = WorldSavePipeline(platform)

    // Keyed by uuid and game mode, like the worlds themselves
    private val loaded = ConcurrentHashMap<SurfWorldImpl, LoadedWorld>()

    override suspend fun loadWorld(world: SurfWorldImpl, polarBytes: ByteArray?, migrations: List<WorldMigration>) {
        if (loaded.containsKey(world)) return

        var created: LoadedWorld? = null
        try {
            loadPipeline.load(world, polarBytes, migrations) {
                created = it
                // Registered right away, because chunk hooks may already ask for the bukkit world
                loaded[world] = it
            }
        } catch (throwable: Throwable) {
            discard(world, created)
            throw throwable
        }
    }

    /** Unloads the platform world of a failed load without saving, so nothing half migrated can reach storage. */
    private suspend fun discard(world: SurfWorldImpl, created: LoadedWorld?) {
        withContext(NonCancellable) {
            loaded.remove(world)
            if (created == null) return@withContext

            try {
                if (!platform.unloadWorld(created.bukkit)) {
                    log.atWarning().log("Bukkit refused to unload the platform world of %s after a failed load", world)
                }
            } catch (exception: Exception) {
                log.atWarning().withCause(exception)
                    .log("Failed to unload the platform world of %s after a failed load", world)
            }
        }
    }

    override suspend fun saveWorld(world: SurfWorldImpl): ByteArray {
        val entry = loaded[world] ?: throw IllegalStateException("World $world is not loaded")
        return savePipeline.save(world, entry)
    }

    override suspend fun unloadWorld(world: SurfWorldImpl) {
        val bukkit = loaded[world]?.bukkit ?: platform.findWorld(world.name) ?: return

        check(platform.unloadWorld(bukkit)) {
            "Bukkit refused to unload world $world, players may still be inside it"
        }
        loaded.remove(world)
    }

    override fun bukkitWorld(world: SurfWorldImpl): World? = loaded[world]?.bukkit ?: platform.findWorld(world.name)

    override fun chunk(world: SurfWorldImpl, x: Int, z: Int): SurfChunk? {
        val entry = loaded[world] ?: return null
        val chunk = platform.chunkIfLoaded(entry.bukkit, x, z) ?: return null
        // A chunk generated after the load has no stored data, but is a live chunk like the others
        val sections = entry.live.getOrCreate(x, z)

        return SurfChunkImpl(world, chunk, sections)
    }
}
