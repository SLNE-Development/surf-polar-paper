package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.IntegrationTestPlugin
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * A chunk unloads and loads again while its world stays loaded. Its entities come back from the data captured at the
 * unload, and nothing else (Paper, CraftEngine, Nexo) may bring them back as well: every marked entity and every
 * furniture piece exists exactly once, after the reload of the chunk, after two saves and after reopening the world.
 */
class InSessionUnloadReload(plugin: IntegrationTestPlugin) : Scenario("in-session-unload-reload") {
    private val markerKey = NamespacedKey(plugin, "unload-marker")

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("unload-reload") { version = 1 }
        handlers {
            entities()
            craftEngine()
            nexoFurniture()
        }
    }

    private class Counts(val stands: Int, val craftEngine: Int, val nexo: Int, val colliders: Int) {
        override fun toString() = "stands=$stands craftEngine=$craftEngine nexo=$nexo colliders=$colliders"
    }

    private fun counts(chunk: Chunk): Counts {
        val entities = chunk.entities.filter { it !is Player }
        val ce = CraftEngineSupport.isInstalled()
        val nexo = NexoSupport.isInstalled()
        return Counts(
            entities.count { it is ArmorStand && it.persistentDataContainer.get(markerKey, PersistentDataType.STRING) == "once" },
            if (ce) entities.count { CraftEngineSupport.isFurnitureBase(it) } else 0,
            if (nexo) entities.count { it is ItemDisplay && NexoSupport.furnitureId(it) != null } else 0,
            if (ce) entities.count { CraftEngineSupport.isCollider(it) } else 0
        )
    }

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "unload-reload")
        val bukkit = world.bukkitWorld
        val chunk = context.anchor(bukkit, 0, 0)

        context.onMain {
            bukkit.spawn(Location(bukkit, 4.5, 62.0, 4.5), ArmorStand::class.java) {
                it.setGravity(false)
                it.persistentDataContainer.set(markerKey, PersistentDataType.STRING, "once")
            }
            if (CraftEngineSupport.isInstalled()) {
                CraftEngineSupport.placeFurniture(Location(bukkit, 8.5, 61.0, 8.5, 90f, 0f))
            }
            if (NexoSupport.isInstalled()) {
                NexoSupport.place("it_chair", Location(bukkit, 12.5, 61.5, 12.5), 0f, BlockFace.UP)
            }
        }
        context.ticks(10)
        val before = context.onMain { counts(chunk) }
        expectEquals(1, before.stands, "Marked armor stands before the unload")

        // Out of memory and back, in the same session
        context.onMain {
            bukkit.removePluginChunkTicket(0, 0, context.plugin)
            bukkit.unloadChunk(0, 0, false)
        }
        context.awaitTrue("chunk 0, 0 to unload") { !bukkit.isChunkLoaded(0, 0) }
        val reloaded = context.loadChunk(bukkit, 0, 0)
        context.awaitTrue("the entities to be back") { counts(reloaded).stands >= 1 }
        context.ticks(60)
        expectEquals(before.toString(), context.onMain { counts(reloaded) }.toString(), "Entities after the unload and reload")

        // Two saves: the data captured at the unload is written by the first and must not be needed by the second
        api.saveWorld(world)
        api.saveWorld(world)
        context.closeApi(api)

        val reopenedApi = context.newApi(settings)
        val reopened = reopenedApi.loadWorld(uuid, "unload-reload")
        val reopenedChunk = context.loadChunk(reopened.bukkitWorld, 0, 0)
        context.awaitTrue("the entities after reopening the world") { counts(reopenedChunk).stands >= 1 }
        context.ticks(60)
        expectEquals(before.toString(), context.onMain { counts(reopenedChunk) }.toString(), "Entities after reopening the world")
    }
}
