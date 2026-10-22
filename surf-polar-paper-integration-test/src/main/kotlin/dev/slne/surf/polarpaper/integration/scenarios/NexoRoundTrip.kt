package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.IntegrationTestPlugin
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.math.abs

/** One Nexo furniture piece to place: where, with which rotation and against which face. */
internal class NexoPiece(val id: String, val block: Triple<Int, Int, Int>, val yaw: Float, val face: BlockFace) {
    override fun toString() = "$id at $block facing $face yaw $yaw"
}

/** What one live Nexo furniture piece looks like, to compare before and after a reload. */
internal data class NexoLook(val id: String?, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
    fun sameAs(other: NexoLook) =
        id == other.id && abs(x - other.x) < 1e-6 && abs(y - other.y) < 1e-6 && abs(z - other.z) < 1e-6 &&
                abs(yaw - other.yaw) < 1e-3 && abs(pitch - other.pitch) < 1e-3
}

internal class NexoSnapshot(val looks: List<NexoLook>, val seats: Int, val others: Int)

internal fun nexoSnapshot(chunk: Chunk): NexoSnapshot {
    val entities = chunk.entities.filter { it !is Player }
    val looks = entities.filterIsInstance<ItemDisplay>().mapNotNull { display ->
        NexoSupport.furnitureId(display)?.let {
            val at = display.location
            NexoLook(it, at.x, at.y, at.z, at.yaw, at.pitch)
        }
    }.sortedWith(compareBy({ it.id }, { it.x }, { it.z }))
    return NexoSnapshot(looks, entities.count { NexoSupport.isSeat(it) }, entities.count { it !is ItemDisplay && !NexoSupport.isSeat(it) })
}

internal fun placePieces(world: World, pieces: List<NexoPiece>): List<ItemDisplay> = pieces.map {
    NexoSupport.place(it.id, Location(world, it.block.first + 0.5, it.block.second + 0.5, it.block.third + 0.5), it.yaw, it.face)
}

/**
 * Nexo furniture on a floor, a wall and a ceiling, with a seat and a storage, survives repeated saves and reloads
 * without moving, keeps its persistent data and its seats are not duplicated.
 */
class NexoRoundTrip(plugin: IntegrationTestPlugin) : Scenario("nexo-round-trip") {
    override fun skipReason(): String? = if (NexoSupport.isInstalled()) null else "Nexo is not installed"

    private val markerKey = NamespacedKey(plugin, "nexo-marker")
    // Raw bytes under a key of the test plugin: the handler copies the whole persistent data of the base entity
    private val storageKey = NamespacedKey(plugin, "storage-bytes")
    private val storageBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("nexo") { version = 1 }
        handlers {
            entities()
            nexoFurniture()
        }
    }

    private val pieces = listOf(
        NexoPiece("it_chair", Triple(2, 61, 2), 90f, BlockFace.UP),
        NexoPiece("it_chest", Triple(6, 61, 2), 0f, BlockFace.UP),
        NexoPiece("it_floor", Triple(10, 61, 2), 180f, BlockFace.UP),
        NexoPiece("it_roof", Triple(2, 61, 6), 0f, BlockFace.DOWN),
        NexoPiece("it_wall", Triple(6, 61, 6), 0f, BlockFace.EAST),
        NexoPiece("it_wall", Triple(10, 61, 6), 0f, BlockFace.NORTH)
    )

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "nexo")
        val chunk = context.anchor(world.bukkitWorld, 0, 0)

        val placed = context.onMain { placePieces(world.bukkitWorld, pieces) }
        context.onMain {
            val chest = placed[1]
            chest.persistentDataContainer.set(markerKey, PersistentDataType.STRING, "chest-marker")
            chest.persistentDataContainer.set(storageKey, PersistentDataType.BYTE_ARRAY, storageBytes)
        }
        context.ticks(10)

        val original = context.onMain { nexoSnapshot(chunk) }
        expectEquals(pieces.size, original.looks.size, "Furniture pieces before saving")
        // Nexo spawns the seat entity of a chair only while somebody sits in it, so none exist here. The comparison
        // below still catches a handler that would create seats on restore.

        var currentApi = api
        var currentChunk = chunk
        var currentUuid = uuid
        repeat(3) { round ->
            currentApi.saveWorld(currentApi.findWorld(currentUuid, "nexo")!!)
            context.closeApi(currentApi)

            currentApi = context.newApi(settings)
            val reloaded = currentApi.loadWorld(currentUuid, "nexo")
            currentChunk = context.loadChunk(reloaded.bukkitWorld, 0, 0)
            context.awaitTrue("the furniture to be placed again (round ${round + 1})") {
                nexoSnapshot(currentChunk).looks.size == pieces.size
            }
            context.ticks(40)

            val now = context.onMain { nexoSnapshot(currentChunk) }
            expectEquals(pieces.size, now.looks.size, "Furniture pieces after reload ${round + 1}")
            for ((before, after) in original.looks.zip(now.looks)) {
                check(before.sameAs(after)) { "Furniture drifted in reload ${round + 1}: $before -> $after" }
            }
            expectEquals(original.seats, now.seats, "Seat entities after reload ${round + 1}")
            expectEquals(original.others, now.others, "Other entities after reload ${round + 1}")
        }

        context.onMain {
            val chest = currentChunk.entities.filterIsInstance<ItemDisplay>().single { NexoSupport.furnitureId(it) == "it_chest" }
            expectEquals(
                "chest-marker", chest.persistentDataContainer.get(markerKey, PersistentDataType.STRING), "Persistent data of the chest"
            )
            check(storageBytes.contentEquals(chest.persistentDataContainer.get(storageKey, PersistentDataType.BYTE_ARRAY))) {
                "The storage bytes of the chest changed"
            }
        }
    }
}
