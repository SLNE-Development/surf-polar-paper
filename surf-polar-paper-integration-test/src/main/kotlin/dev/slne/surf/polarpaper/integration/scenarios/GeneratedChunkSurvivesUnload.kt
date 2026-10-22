package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * A chunk that was generated after the world loaded (it is not part of the stored world) gets a block, an entity and a
 * section, is unloaded, and the world is saved and reloaded. Reports what Polar actually does with such a chunk, and
 * fails when something is dropped. Also checks that unloading a world does not log one warning per chunk.
 */
class GeneratedChunkSurvivesUnload(plugin: dev.slne.surf.polarpaper.integration.IntegrationTestPlugin) :
    Scenario("generated-chunk-survives-unload") {
    private val sectionType = DataType(Key.key("integration", "generated"), String.serializer())
    private val markerKey = NamespacedKey(plugin, "generated-marker")

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("generated") { version = 1 }
        handlers { entities() }
        data(sectionType)
    }

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "generated")
        val bukkit = world.bukkitWorld

        // Far away from anything stored, so it is generated, and requested without a ticket like a plain chunk request
        val chunkX = 40
        val chunkZ = 40
        context.loadChunkUnheld(bukkit, chunkX, chunkZ)
        context.onMain {
            bukkit.getBlockAt(chunkX * 16 + 1, 64, chunkZ * 16 + 1).setType(Material.DIAMOND_BLOCK, false)
            bukkit.spawn(Location(bukkit, chunkX * 16 + 5.5, 66.0, chunkZ * 16 + 5.5), ArmorStand::class.java) {
                it.setGravity(false)
                it.persistentDataContainer.set(markerKey, PersistentDataType.STRING, "generated-stand")
            }
        }
        val live = world.chunk(chunkX, chunkZ) ?: throw ScenarioFailure("Chunk $chunkX, $chunkZ is not live after loading it")
        live.data[sectionType] = "generated section"

        // Force the chunk out of memory
        context.onMain { bukkit.unloadChunk(chunkX, chunkZ, false) }
        context.awaitTrue("chunk $chunkX, $chunkZ to unload") { !bukkit.isChunkLoaded(chunkX, chunkZ) }

        api.saveWorld(world)
        context.closeApi(api)

        val reloadApi = context.newApi(settings)
        val reloaded = reloadApi.loadWorld(uuid, "generated")
        val chunk = context.loadChunk(reloaded.bukkitWorld, chunkX, chunkZ)
        context.ticks(10)

        val missing = mutableListOf<String>()
        context.onMain {
            if (reloaded.bukkitWorld.getBlockAt(chunkX * 16 + 1, 64, chunkZ * 16 + 1).type != Material.DIAMOND_BLOCK) {
                missing += "the block"
            }
            val stands = chunk.entities.filterIsInstance<ArmorStand>().filter {
                it.persistentDataContainer.get(markerKey, PersistentDataType.STRING) == "generated-stand"
            }
            if (stands.isEmpty()) missing += "the armor stand"
            if (stands.size > 1) missing += "a single armor stand (found ${stands.size})"
        }
        if (reloaded.chunk(chunkX, chunkZ)?.data?.get(sectionType) != "generated section") missing += "the section"

        context.closeApi(reloadApi)
        val noise = unloadNoise(context)

        if (missing.isNotEmpty()) {
            throw ScenarioFailure(
                "A chunk generated after the world loaded and unloaded before the save was dropped by Polar. " +
                        "Lost: ${missing.joinToString()}. Polar only writes the chunks it knew at load or that are loaded " +
                        "when the save runs, so everything in such a chunk is lost. This is a data-loss gap." +
                        (if (noise > 0) " Unloading a world also logged $noise per-chunk warnings." else "")
            )
        }
        check(noise == 0) { "Unloading a world with 8 loaded chunks logged $noise warnings or errors" }
    }

    /** Unloads a world with several loaded chunks that hold sections and counts the per-chunk unload warnings. */
    private suspend fun unloadNoise(context: ScenarioContext): Int {
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "generated")
        for (x in 0..3) for (z in 0..1) {
            context.anchor(world.bukkitWorld, x, z)
            world.chunk(x, z)!!.data[sectionType] = "noise $x $z"
        }
        api.saveWorld(world)

        val mark = context.logMark()
        context.closeApi(api)
        context.ticks(20)
        // Any warning or error that mentions the world while it unloads is noise from the chunks going away with it
        return context.logSince(mark).count { uuid.toString() in it && ("/WARN]" in it || "/ERROR]" in it) }
    }
}
