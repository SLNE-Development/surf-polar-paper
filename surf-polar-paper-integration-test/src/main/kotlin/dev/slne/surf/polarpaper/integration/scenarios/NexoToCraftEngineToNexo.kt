package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Chunk
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Furniture moves from Nexo to CraftEngine and back through migrations that rewrite the neutral records, so no
 * furniture plugin API is needed to convert them.
 */
class NexoToCraftEngineToNexo : Scenario("nexo-to-craftengine-to-nexo") {
    override fun skipReason(): String? = when {
        !NexoSupport.isInstalled() -> "Nexo is not installed"
        !CraftEngineSupport.isInstalled() -> "CraftEngine is not installed"
        else -> null
    }

    /** The id mapping table of the test plugin: Nexo id to CraftEngine id. */
    private val toCraftEngine = mapOf("it_chair" to "default:bench", "it_chest" to "default:globe")
    private val toNexo = toCraftEngine.entries.associate { (nexo, ce) -> ce to nexo }

    private val forward = object : WorldMigration(2) {
        override fun migrateRaw(world: RawWorld) {
            for (chunk in world.chunks) {
                val records = chunk.data[BuiltInData.NEXO_FURNITURE] ?: continue
                // The Nexo placement data rides along in the properties, so the way back can use it
                chunk.data[BuiltInData.CRAFT_ENGINE_FURNITURE] = records.map {
                    it.copy(id = toCraftEngine.getValue(it.id), properties = it.properties.filterKeys { key -> key == "face" || key == "block" })
                }
                chunk.data.remove(BuiltInData.NEXO_FURNITURE.key)
            }
        }
    }

    private val backward = object : WorldMigration(3) {
        override fun migrateRaw(world: RawWorld) {
            for (chunk in world.chunks) {
                val records = chunk.data[BuiltInData.CRAFT_ENGINE_FURNITURE] ?: continue
                chunk.data[BuiltInData.NEXO_FURNITURE] = records.map { it.copy(id = toNexo.getValue(it.id)) }
                chunk.data.remove(BuiltInData.CRAFT_ENGINE_FURNITURE.key)
            }
        }
    }

    private fun settings(version: Int): PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("nexo-ce") {
            this.version = version
            if (version >= 2) migration(forward)
            if (version >= 3) migration(backward)
        }
        handlers {
            entities()
            when (version) {
                2 -> craftEngine()
                else -> nexoFurniture()
            }
        }
    }

    private val pieces = listOf(
        NexoPiece("it_chair", Triple(2, 61, 2), 90f, BlockFace.UP),
        NexoPiece("it_chair", Triple(6, 61, 5), 180f, BlockFace.UP),
        NexoPiece("it_chest", Triple(10, 61, 9), 0f, BlockFace.UP)
    )

    private data class CeLook(val id: String?, val x: Double, val y: Double, val z: Double, val yaw: Float, val live: Boolean)

    private fun ceSnapshot(chunk: Chunk): List<CeLook> = chunk.entities.filter { it !is Player }
        .filter { CraftEngineSupport.isFurnitureBase(it) }
        .map { CeLook(CraftEngineSupport.furnitureId(it), it.location.x, it.location.y, it.location.z, it.location.yaw, CraftEngineSupport.isLiveFurniture(it)) }
        .sortedWith(compareBy({ it.x }, { it.z }))

    override suspend fun run(context: ScenarioContext) {
        val first = context.newApi(settings(1))
        val uuid = UUID.randomUUID()
        val world = first.createWorld(uuid, "nexo-ce")
        val chunk = context.anchor(world.bukkitWorld, 0, 0)
        context.onMain { placePieces(world.bukkitWorld, pieces) }
        context.ticks(10)
        val original = context.onMain { nexoSnapshot(chunk) }
        expectEquals(pieces.size, original.looks.size, "Nexo furniture before saving")
        first.saveWorld(world)
        context.closeApi(first)

        // Version 2: CraftEngine only
        val second = context.newApi(settings(2))
        val migrated = second.loadWorld(uuid, "nexo-ce")
        val ceChunk = context.loadChunk(migrated.bukkitWorld, 0, 0)
        context.awaitTrue("the CraftEngine furniture to be placed") { ceSnapshot(ceChunk).size == pieces.size }
        context.ticks(40)

        val ce = context.onMain { ceSnapshot(ceChunk) }
        expectEquals(pieces.size, ce.size, "CraftEngine furniture after the conversion ($ce)")
        val expectedCe = original.looks.sortedWith(compareBy({ it.x }, { it.z }))
        for ((nexoLook, ceLook) in expectedCe.zip(ce)) {
            check(abs(nexoLook.x - ceLook.x) < 1e-6 && abs(nexoLook.y - ceLook.y) < 1e-6 && abs(nexoLook.z - ceLook.z) < 1e-6) {
                "CraftEngine furniture is not where the Nexo furniture was: $nexoLook -> $ceLook"
            }
            check(abs(nexoLook.yaw - ceLook.yaw) < 1e-3) { "CraftEngine furniture is rotated differently: $nexoLook -> $ceLook" }
            expectEquals(toCraftEngine[nexoLook.id], ceLook.id, "CraftEngine furniture id (all: $ce)")
        }
        expectEquals(0, context.onMain { nexoSnapshot(ceChunk).looks.size }, "Nexo furniture left after the conversion")
        context.closeApi(second)

        // Version 3: Nexo only again
        val third = context.newApi(settings(3))
        val back = third.loadWorld(uuid, "nexo-ce")
        val backChunk = context.loadChunk(back.bukkitWorld, 0, 0)
        context.awaitTrue("the Nexo furniture to be placed again") { nexoSnapshot(backChunk).looks.size == pieces.size }
        context.ticks(40)

        val again = context.onMain { nexoSnapshot(backChunk) }
        expectEquals(pieces.size, again.looks.size, "Nexo furniture after the way back")
        for ((before, after) in original.looks.zip(again.looks)) {
            check(before.sameAs(after)) { "Nexo furniture is not where it was: $before -> $after" }
        }
        expectEquals(0, context.onMain { ceSnapshot(backChunk).size }, "CraftEngine furniture left after the way back")
        expectEquals(original.seats, again.seats, "Seat entities after the way back")
    }

    private fun abs(value: Double) = kotlin.math.abs(value)
    private fun abs(value: Float) = kotlin.math.abs(value)
}
