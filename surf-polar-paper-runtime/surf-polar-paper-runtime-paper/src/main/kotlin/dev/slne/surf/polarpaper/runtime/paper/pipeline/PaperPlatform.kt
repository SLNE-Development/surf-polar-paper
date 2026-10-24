package dev.slne.surf.polarpaper.runtime.paper.pipeline

import com.github.shynixn.mccoroutine.folia.globalRegionDispatcher
import com.github.shynixn.mccoroutine.folia.regionDispatcher
import dev.slne.surf.polarpaper.runtime.paper.plugin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import live.minehub.polarpaper.Polar
import live.minehub.polarpaper.PolarPaper
import live.minehub.polarpaper.core.config.Config
import live.minehub.polarpaper.core.generator.PolarGenerator
import live.minehub.polarpaper.core.source.BytesPolarSource
import live.minehub.polarpaper.core.world.BlockSelector
import live.minehub.polarpaper.core.world.PolarDataConverter
import live.minehub.polarpaper.core.world.PolarWorld
import live.minehub.polarpaper.core.world.PolarWorldAccess
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.plugin.Plugin
import kotlin.coroutines.CoroutineContext

/**
 * Everything the load and save pipelines need from the running server. Split from the pipelines so that they can be
 * tested with real Polar worlds and without a server.
 */
interface PaperPlatform {
    /**
     * Creates the platform world from [polarWorld]. Polar's own autosave and save-on-stop are disabled for it, because
     * they would save without the chunk preparation. Returns null when Polar could not create the world.
     */
    suspend fun createWorld(polarWorld: PolarWorld, name: String, access: PolarWorldAccess): World?

    /** The world Polar registered for [name], if any. */
    fun findWorld(name: String): World?

    /** Unloads [world] without saving, on the global region thread. Returns false when Bukkit refuses. */
    suspend fun unloadWorld(world: World): Boolean

    /** Loads the chunk and returns it once it is live. */
    suspend fun loadChunk(world: World, x: Int, z: Int): Chunk

    fun chunkIfLoaded(world: World, x: Int, z: Int): Chunk?

    /** Coordinates of the chunks that are loaded in [world] right now. */
    fun loadedChunkCoordinates(world: World): List<Pair<Int, Int>>

    fun addTicket(world: World, x: Int, z: Int, plugin: Plugin)

    fun removeTicket(world: World, x: Int, z: Int, plugin: Plugin)

    /** Runs [block] on the thread that owns the chunk, directly when the caller already is that thread. */
    suspend fun <T> onChunkThread(world: World, x: Int, z: Int, block: () -> T): T

    /**
     * Sets the world user data that [savePolar] writes. Polar writes back the user data its generator captured at
     * load, so this replaces it.
     */
    fun setWorldUserData(world: World, bytes: ByteArray)

    /** Saves the live [world] with [access] over the chunks of [polarWorld] and returns the Polar bytes. */
    suspend fun savePolar(world: World, polarWorld: PolarWorld, access: PolarWorldAccess): ByteArray
}

/** The [PaperPlatform] backed by Bukkit and Polar. */
class BukkitPaperPlatform(
    // World unloading must happen on the global region thread (the main thread on Paper)
    private val globalRegionContext: () -> CoroutineContext = { plugin.globalRegionDispatcher }
) : PaperPlatform {
    override suspend fun createWorld(polarWorld: PolarWorld, name: String, access: PolarWorldAccess): World? {
        // -1 disables Polar's autosave, which would call Polar.saveWorld without SurfWorldAccess.prepareChunkSave
        val config = Config.readFromConfig(PolarPaper.getPlugin().config, name).toBuilder()
            .autoSaveIntervalTicks(-1)
            .saveOnStop(false)
            .build()

        val world = Polar.createWorld(polarWorld, name, config, access, PolarDataConverter.DEFAULT).await()
        // Nothing starts one with -1, this only makes sure a task from an earlier world of the same name is gone
        world?.let { Polar.stopAutoSaveTask(it.key) }
        return world
    }

    override fun findWorld(name: String): World? = Bukkit.getWorld(worldKey(name))

    override suspend fun unloadWorld(world: World): Boolean =
        // Already on the global region thread when called from onDisable, where no new coroutines can be dispatched
        if (Bukkit.isGlobalTickThread()) {
            Bukkit.unloadWorld(world, false)
        } else {
            withContext(globalRegionContext()) { Bukkit.unloadWorld(world, false) }
        }

    override suspend fun loadChunk(world: World, x: Int, z: Int): Chunk = world.getChunkAtAsync(x, z).await()

    override fun chunkIfLoaded(world: World, x: Int, z: Int): Chunk? = world.getChunkAt(x, z, false)
        .takeIf { it.isLoaded }

    override fun loadedChunkCoordinates(world: World): List<Pair<Int, Int>> =
        world.loadedChunks.map { it.x to it.z }

    override fun addTicket(world: World, x: Int, z: Int, plugin: Plugin) {
        world.addPluginChunkTicket(x, z, plugin)
    }

    override fun removeTicket(world: World, x: Int, z: Int, plugin: Plugin) {
        world.removePluginChunkTicket(x, z, plugin)
    }

    override suspend fun <T> onChunkThread(world: World, x: Int, z: Int, block: () -> T): T =
        if (Bukkit.isOwnedByCurrentRegion(world, x, z)) {
            block()
        } else {
            withContext(plugin.regionDispatcher(world, x, z)) { block() }
        }

    override fun setWorldUserData(world: World, bytes: ByteArray) {
        val generator = PolarGenerator.fromWorld(world)
            ?: throw IllegalStateException("World ${world.name} is not a polar world")
        generator.userData = bytes
    }

    override suspend fun savePolar(world: World, polarWorld: PolarWorld, access: PolarWorldAccess): ByteArray {
        val generator = PolarGenerator.fromWorld(world)
            ?: throw IllegalStateException("World ${world.name} is not a polar world")

        val source = BytesPolarSource()
        Polar.saveWorld(world, polarWorld.chunks(), source, access, BlockSelector.ALL, generator.config).await()
        return source.bytes()
    }

    companion object {
        // Polar registers the worlds it creates under its own namespace, so they cannot be found by plain name
        const val POLAR_NAMESPACE = "polarpaper"

        fun worldKey(name: String) = NamespacedKey(POLAR_NAMESPACE, name)
    }
}
