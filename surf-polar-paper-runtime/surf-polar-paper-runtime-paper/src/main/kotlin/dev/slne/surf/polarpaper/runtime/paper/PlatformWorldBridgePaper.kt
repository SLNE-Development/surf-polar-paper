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
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Loads, saves and unloads the Polar worlds behind [SurfWorldImpl]. The steps live in the pipeline classes. */
@AutoService(PlatformWorldBridge::class)
class PlatformWorldBridgePaper(
    private val platform: PaperPlatform,
    readWorld: ((ByteArray) -> PolarWorld)?,
    private val shutdownLockTimeout: Duration = 10.seconds
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

    override suspend fun saveWorldForShutdown(world: SurfWorldImpl): ByteArray {
        val entry = loaded[world] ?: throw IllegalStateException("World $world is not loaded")
        return savePipeline.save(world, entry, shutdownLockTimeout)
    }

    /** Worlds that are being unloaded. Their chunks unload with them and are not captured. Weak, so a world can go. */
    private val unloadingWorlds: MutableSet<World> = Collections.newSetFromMap(WeakHashMap())

    /** Marks [bukkit] as unloading: the chunk events that follow belong to the world going away, not to gameplay. */
    fun worldUnloading(bukkit: World) {
        unloadingWorlds += bukkit
        loaded.values.firstOrNull { it.bukkit == bukkit }?.access?.worldClosing()
    }

    private fun entryOf(bukkit: World): LoadedWorld? {
        if (bukkit in unloadingWorlds) return null
        return loaded.values.firstOrNull { it.bukkit == bukkit }
    }

    /** Hands the entities of a chunk that is about to unload to its capture, see [SurfWorldAccess.entitiesUnloading]. */
    fun entitiesUnloading(bukkit: World, x: Int, z: Int, entities: List<Entity>) {
        entryOf(bukkit)?.access?.entitiesUnloading(x, z, entities)
    }

    /**
     * Captures a chunk that unloaded, so the saves that follow still write its sections and entities: Polar writes
     * chunks that are not loaded anymore, but only knows their blocks.
     */
    fun chunkUnloaded(bukkit: World, chunk: Chunk) {
        val entry = entryOf(bukkit) ?: return
        entry.access.chunkUnloading(chunk)
        entry.live.remove(chunk.x, chunk.z)
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
        // The data of an undecodable chunk must stay as stored, so no one gets a chunk to write into
        if (entry.access.isUndecodable(x, z)) return null
        val chunk = platform.chunkIfLoaded(entry.bukkit, x, z) ?: return null
        // A chunk generated after the load has no stored data, but is a live chunk like the others
        val sections = entry.live.getOrCreate(x, z)

        return SurfChunkImpl(world, chunk, sections)
    }
}
