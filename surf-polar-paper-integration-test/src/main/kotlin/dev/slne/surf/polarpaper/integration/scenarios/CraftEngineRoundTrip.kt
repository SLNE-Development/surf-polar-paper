package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.abs

/**
 * A CraftEngine furniture and a CraftEngine custom block survive a save and a reload: the furniture exists exactly once
 * (no second piece, no duplicated colliders or seats), keeps its position and rotation and is a live CraftEngine
 * furniture again, and the block is still a CraftEngine block with the same state.
 */
class CraftEngineRoundTrip : Scenario("craftengine-round-trip") {
    override fun skipReason(): String? =
        if (CraftEngineSupport.isInstalled()) null else "CraftEngine is not installed"

    private val settings: PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("craftengine") { version = 1 }
        handlers {
            entities()
            craftEngine()
        }
    }

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi(settings)
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "craftengine")
        val bukkit = world.bukkitWorld
        val chunk = context.anchor(bukkit, 0, 0)

        val furnitureAt = Location(bukkit, 8.5, 61.0, 8.5, 90f, 0f)
        val blockAt = Location(bukkit, 3.0, 61.0, 3.0)
        context.onMain {
            val furniture = CraftEngineSupport.placeFurniture(furnitureAt)
            check(CraftEngineSupport.isLiveFurniture(furniture)) { "CraftEngine does not track the furniture it just placed" }
            CraftEngineSupport.placeBlock(blockAt)
        }
        // Colliders are spawned right after the base entity
        context.ticks(10)

        val blockBefore = context.onMain { CraftEngineSupport.describeBlock(bukkit.getBlockAt(blockAt)) }
        check(blockBefore != null) { "CraftEngine does not see the custom block it just placed at $blockAt" }
        val snapshotBefore = context.onMain { snapshot(chunk) }
        expectEquals(1, snapshotBefore.furniture.size, "Furniture base entities before saving")

        api.saveWorld(world)
        context.closeApi(api)

        val reloaded = context.newApi(settings).loadWorld(uuid, "craftengine")
        val reloadedChunk = context.loadChunk(reloaded.bukkitWorld, 0, 0)
        context.awaitTrue("the furniture to be placed again") { snapshot(reloadedChunk).furniture.isNotEmpty() }
        // A duplicate would be placed a tick later, or by a second restore
        context.ticks(40)

        val after = context.onMain { snapshot(reloadedChunk) }
        context.plugin.logger.info(
            "craftengine-round-trip: before=${snapshotBefore.describe()} after=${after.describe()} block=$blockBefore"
        )
        expectEquals(1, after.furniture.size, "Furniture base entities after the reload")
        expectEquals(
            snapshotBefore.furniture.single().id, after.furniture.single().id,
            "Furniture id (after: ${after.describe()}, live=${after.furniture.map { it.live }})"
        )
        check(after.furniture.single().live) { "CraftEngine does not track the restored furniture as live" }
        check(sameSpot(snapshotBefore.furniture.single().location, after.furniture.single().location)) {
            "Furniture moved: ${snapshotBefore.furniture.single().location} -> ${after.furniture.single().location}"
        }
        expectEquals(snapshotBefore.colliders, after.colliders, "Colliders")
        expectEquals(snapshotBefore.seats, after.seats, "Seats")
        expectEquals(snapshotBefore.others, after.others, "Other entities")

        val blockAfter = context.onMain { CraftEngineSupport.describeBlock(reloaded.bukkitWorld.getBlockAt(blockAt)) }
            ?: throw ScenarioFailure(
                "The custom block is only a vanilla block after the reload: " +
                        context.onMain { reloaded.bukkitWorld.getBlockAt(blockAt).blockData.asString }
            )
        expectEquals(blockBefore, blockAfter, "Custom block as CraftEngine sees it")
    }

    private fun sameSpot(a: Location, b: Location) =
        abs(a.x - b.x) < 1e-6 && abs(a.y - b.y) < 1e-6 && abs(a.z - b.z) < 1e-6 &&
                abs(a.yaw - b.yaw) < 1e-3 && abs(a.pitch - b.pitch) < 1e-3

    private class Furniture(val id: String?, val location: Location, val live: Boolean)

    private class Snapshot(
        val furniture: List<Furniture>,
        val colliders: Int,
        val seats: Int,
        val others: Map<String, Int>
    )

    private fun Snapshot.describe() =
        "furniture=${furniture.map { "${it.id}@${it.location.x},${it.location.y},${it.location.z}/${it.location.yaw}" }} " +
                "colliders=$colliders seats=$seats others=$others"

    private fun snapshot(chunk: Chunk): Snapshot {
        val entities: List<Entity> = chunk.entities.filter { it !is Player }
        val furniture = entities.filter { CraftEngineSupport.isFurnitureBase(it) }.map {
            Furniture(CraftEngineSupport.furnitureId(it), it.location, CraftEngineSupport.isLiveFurniture(it))
        }
        val rest = entities.filter { !CraftEngineSupport.isFurnitureBase(it) }
        return Snapshot(
            furniture,
            rest.count { CraftEngineSupport.isCollider(it) },
            rest.count { CraftEngineSupport.isSeat(it) },
            rest.filter { !CraftEngineSupport.isCollider(it) && !CraftEngineSupport.isSeat(it) }
                .groupingBy { it.type.key.asString() }.eachCount()
        )
    }
}
