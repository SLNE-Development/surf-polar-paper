package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.IntegrationTestPlugin
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.persistence.PersistentDataType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Stopping the server with a loaded, modified world saves it without hanging. The first server run modifies a world
 * and leaves it loaded on purpose ([prepare]); the Gradle task then starts the server a second time, which loads the
 * world and checks that the changes arrived ([verify]). A hang in the shutdown save shows as the server run timing out.
 */
class ShutdownSavesLoadedWorld(private val plugin: IntegrationTestPlugin) {
    val name = "shutdown-saves-loaded-world"

    private val sectionType = DataType(Key.key("integration", "shutdown"), String.serializer())
    private val markerKey = NamespacedKey(plugin, "shutdown-marker")
    private val gameMode = "shutdown"
    private val markerFile: Path get() = plugin.dataPath.resolve("shutdown-world.txt")

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode(gameMode) { version = 1 }
        handlers { entities() }
        data(sectionType)
    }

    /** Whether a previous server run left a world to verify. */
    fun hasPendingVerification() = Files.exists(markerFile)

    /** Creates the world, saves it, modifies it afterwards and leaves it loaded and unsaved. */
    suspend fun prepare(context: ScenarioContext) {
        val api = SurfPolarPaperApi.create(plugin, settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, gameMode)
        val bukkit = world.bukkitWorld

        context.anchor(bukkit, 0, 0)
        world.chunk(0, 0)!!.data[sectionType] = "before"
        api.saveWorld(world)

        // Everything below happens after the last save
        world.chunk(0, 0)!!.data[sectionType] = "changed after the last save"
        world.data[sectionType] = "world data after the last save"
        context.onMain {
            bukkit.getBlockAt(1, 61, 1).setType(Material.GOLD_BLOCK, false)
            bukkit.spawn(Location(bukkit, 4.5, 62.0, 4.5), ArmorStand::class.java) {
                it.setGravity(false)
                it.persistentDataContainer.set(markerKey, PersistentDataType.STRING, "survived the shutdown")
            }
        }

        Files.createDirectories(markerFile.parent)
        Files.writeString(markerFile, uuid.toString())
    }

    /** Loads the world the previous run left behind and checks that the shutdown saved its changes. */
    suspend fun verify(context: ScenarioContext) {
        val uuid = UUID.fromString(Files.readString(markerFile).trim())
        val api = context.newApi(settings)
        val world = api.loadWorld(uuid, gameMode)
        val chunk = context.loadChunk(world.bukkitWorld, 0, 0)

        expectEquals("world data after the last save", world.data[sectionType], "World data saved by the shutdown")
        expectEquals(
            "changed after the last save", world.chunk(0, 0)?.data?.get(sectionType), "Chunk section saved by the shutdown"
        )
        context.awaitTrue("the armor stand saved by the shutdown") {
            chunk.entities.filterIsInstance<ArmorStand>()
                .any { it.persistentDataContainer.get(markerKey, PersistentDataType.STRING) == "survived the shutdown" }
        }
        context.onMain {
            expectEquals(
                Material.GOLD_BLOCK, world.bukkitWorld.getBlockAt(1, 61, 1).type, "Block placed after the last save"
            )
        }

        // Polar saves on stop by itself with this server's configuration; it must not have written the world
        val polarFile = Path.of("plugins", "polarpaper", "worlds", "$uuid.polar")
        check(Files.notExists(polarFile)) { "Polar saved the world on stop by itself: $polarFile" }
        if (Files.notExists(markerFile)) throw ScenarioFailure("The marker vanished")
    }
}
