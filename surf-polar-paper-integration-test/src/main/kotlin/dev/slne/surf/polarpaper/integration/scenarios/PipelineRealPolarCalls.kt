package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import kotlinx.serialization.builtins.serializer
import live.minehub.polarpaper.core.generator.PolarGenerator
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The Polar calls that unit tests cannot reach: Polar's own autosave and save on stop stay off, the save goes through
 * the plugin's world access, and the world data is written through the generator's user data.
 */
class PipelineRealPolarCalls : Scenario("pipeline-real-polar-calls") {
    private val chunkType = DataType(Key.key("integration", "pipeline-chunk"), String.serializer())
    private val worldType = DataType(Key.key("integration", "pipeline-world"), String.serializer())

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("pipeline") { version = 1 }
        data(chunkType)
        data(worldType)
    }

    override suspend fun run(context: ScenarioContext) {
        // The test server configures Polar to autosave every 100 ticks and to save on stop. If that did not arrive,
        // the checks below would prove nothing.
        val polar = Bukkit.getPluginManager().getPlugin("polarpaper") ?: throw ScenarioFailure("polarpaper is not installed")
        expectEquals(100, polar.config.getInt("default.autosaveIntervalTicks"), "Polar's configured default autosave interval")
        expectEquals(true, polar.config.getBoolean("default.saveOnStop"), "Polar's configured default save on stop")

        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "pipeline")
        val bukkit = world.bukkitWorld

        val generator = PolarGenerator.fromWorld(bukkit) ?: throw ScenarioFailure("The world is not a polar world")
        expectEquals(-1, generator.config.autoSaveIntervalTicks(), "Polar autosave interval of the created world")
        expectEquals(false, generator.config.saveOnStop(), "Polar save on stop of the created world")
        expectEquals(
            "dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess",
            generator.worldAccess.javaClass.name,
            "The world access Polar saves through"
        )

        context.anchor(bukkit, 0, 0)
        world.chunk(0, 0)!!.data[chunkType] = "chunk section"
        world.data[worldType] = "world data"

        // Longer than the 100 ticks Polar would wait if its autosave were running
        context.ticks(160)
        check(polarFilesOf(uuid).isEmpty()) { "Polar wrote the world on its own: ${polarFilesOf(uuid)}" }

        api.saveWorld(world)

        // The world data went through PolarGenerator.setUserData, so Polar writes it back from the generator
        val userData = generator.userData
        check(userData != null && userData.isNotEmpty()) { "The generator holds no user data after the save" }
        check(polarFilesOf(uuid).isEmpty()) { "Polar wrote its own file for the world while it was saved: ${polarFilesOf(uuid)}" }

        context.closeApi(api)

        val reloaded = context.newApi(settings).loadWorld(uuid, "pipeline")
        context.loadChunk(reloaded.bukkitWorld, 0, 0)
        expectEquals("world data", reloaded.data[worldType], "World data after a reload")
        expectEquals("chunk section", reloaded.chunk(0, 0)?.data?.get(chunkType), "Chunk section after a reload")
    }

    /** Files Polar's own file source would have written for the world. */
    private fun polarFilesOf(uuid: UUID): List<String> {
        val directory = Path.of("plugins", "polarpaper", "worlds")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.map { it.fileName.toString() }.filter { uuid.toString() in it }.toList()
        }
    }
}
