package dev.slne.surf.polarpaper.integration

import com.github.shynixn.mccoroutine.folia.globalRegionDispatcher
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.api.world.SurfWorld
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * What a scenario can use: API instances that are closed after the scenario, the main thread, chunks and the stored
 * files of the plugin's disk storage.
 */
class ScenarioContext(val plugin: IntegrationTestPlugin) {
    private val apis = mutableListOf<SurfPolarPaperApi>()

    /** Creates an API instance of the test plugin. Its game modes are freed again when the scenario ends. */
    fun newApi(settings: PolarPaperSettingsBuilder.() -> Unit): SurfPolarPaperApi =
        SurfPolarPaperApi.create(plugin, settings).also { apis += it }

    /** Unloads the worlds of [api] and closes it, as a plugin does that is replaced by an updated build. */
    suspend fun closeApi(api: SurfPolarPaperApi) {
        api.unloadAllWorlds()
        api.close()
        apis -= api
    }

    /** Closes whatever the scenario left open, ignoring failures: the scenario already has its own result. */
    suspend fun cleanUp() {
        for (api in apis.toList()) {
            try {
                closeApi(api)
            } catch (exception: Exception) {
                plugin.logger.warning("Could not clean up an API instance: $exception")
                // Still frees the game modes, which later scenarios do not share anyway
                runCatching { api.close() }
                apis -= api
            }
        }
    }

    /** Runs [block] on the main thread. */
    suspend fun <T> onMain(block: () -> T): T =
        if (Bukkit.isPrimaryThread()) block() else withContext(plugin.globalRegionDispatcher) { block() }

    /** Waits [ticks] server ticks. */
    suspend fun ticks(ticks: Int) = delay(ticks * 50L)

    /** Polls [condition] on the main thread every tick until it holds, and fails with [what] after [timeoutTicks]. */
    suspend fun awaitTrue(what: String, timeoutTicks: Int = 100, condition: () -> Boolean) {
        repeat(timeoutTicks) {
            if (onMain(condition)) return
            ticks(1)
        }
        if (!onMain(condition)) throw ScenarioFailure("Timed out after $timeoutTicks ticks waiting for: $what")
    }

    /**
     * Loads the chunk and keeps it loaded with a plugin ticket. Without a ticket Paper unloads a chunk a few ticks
     * after it was requested, and a chunk that unloaded is not part of the next save of a Polar world.
     */
    suspend fun loadChunk(world: World, x: Int, z: Int): Chunk {
        val chunk = world.getChunkAtAsync(x, z).await()
        onMain { world.addPluginChunkTicket(x, z, plugin) }
        return chunk
    }

    /** Loads the chunk without a ticket, like a plain chunk request of a plugin. */
    suspend fun loadChunkUnheld(world: World, x: Int, z: Int): Chunk = world.getChunkAtAsync(x, z).await()

    /**
     * Loads the chunk (with a ticket, see [loadChunk]) and puts a stone block into it. Polar drops chunks that only
     * contain air, together with their data sections, so every chunk that holds data in a scenario needs a block.
     */
    suspend fun anchor(world: World, chunkX: Int, chunkZ: Int): Chunk {
        val chunk = loadChunk(world, chunkX, chunkZ)
        onMain { world.getBlockAt(chunkX * 16, ANCHOR_Y, chunkZ * 16).setType(Material.STONE, false) }
        return chunk
    }

    /** The Bukkit world Polar registered for [world], found the way a third party would. */
    fun bukkitWorldOf(uuid: UUID): World? = Bukkit.getWorld(NamespacedKey("polarpaper", uuid.toString()))

    /** The file the disk storage writes a world to. */
    fun storedFile(world: SurfWorld): Path = storedFile(world.gameModeIdentifier, world.uuid)

    fun storedFile(gameMode: String, uuid: UUID): Path =
        Path.of("plugins", "surf-polar-paper-runtime-paper", "worlds", gameMode, "$uuid.cbor")

    fun storedBytes(gameMode: String, uuid: UUID): ByteArray = Files.readAllBytes(storedFile(gameMode, uuid))

    /** The current size of the server log, to read what was logged afterwards with [logSince]. */
    fun logMark(): Long = Files.size(SERVER_LOG)

    /** The server log lines written since [mark]. */
    fun logSince(mark: Long): List<String> =
        Files.newInputStream(SERVER_LOG).use { stream ->
            stream.skipNBytes(mark)
            String(stream.readAllBytes(), Charsets.UTF_8).lines()
        }

    companion object {
        private val SERVER_LOG: Path = Path.of("logs", "latest.log")

        /** The y of anchor blocks and the base for entities spawned above them. */
        const val ANCHOR_Y = 60
    }
}
