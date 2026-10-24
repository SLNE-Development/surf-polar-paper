package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Location
import org.bukkit.entity.Pig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A live migration removes the pigs of chunks that were not loaded in this session. */
class LiveMigration : Scenario("live-migration") {
    private val visited = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

    private val removePigs = object : WorldMigration(2) {
        override val hasLiveStage get() = true

        override fun migrateLive(chunk: SurfChunk) {
            visited += chunk.x to chunk.z
            chunk.bukkitChunk.entities.filterIsInstance<Pig>().forEach { it.remove() }
        }
    }

    private val chunks = listOf(0 to 0, 1 to 0, 0 to 1, -3 to 2, 7 to -6, 12 to 12)

    private fun settings(version: Int): PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("live") {
            this.version = version
            if (version == 2) migration(removePigs)
        }
        handlers { entities() }
    }

    override suspend fun run(context: ScenarioContext) {
        val first = context.newApi(settings(1))
        val uuid = UUID.randomUUID()
        val world = first.createWorld(uuid, "live")

        for ((cx, cz) in chunks) {
            context.anchor(world.bukkitWorld, cx, cz)
            context.onMain {
                repeat(3) { index ->
                    val at = Location(world.bukkitWorld, cx * 16 + 3.5 + index * 3, 62.0, cz * 16 + 8.5)
                    world.bukkitWorld.spawn(at, Pig::class.java) {
                        it.setGravity(false)
                        it.setAI(false)
                    }
                }
            }
        }
        first.saveWorld(world)
        context.closeApi(first)

        // The pigs must really be in the stored world, or "no pig left" would prove nothing
        val precondition = context.newApi(settings(1))
        val stored = precondition.loadWorld(uuid, "live")
        for ((cx, cz) in chunks) {
            val chunk = context.loadChunk(stored.bukkitWorld, cx, cz)
            context.awaitTrue("the three stored pigs of chunk $cx, $cz") { chunk.entities.count { it is Pig } == 3 }
        }
        context.closeApi(precondition)

        val second = context.newApi(settings(2))
        val migrated = second.loadWorld(uuid, "live")
        expectEquals(2, migrated.version, "Version after the live migration")
        check(visited.containsAll(chunks)) { "The live stage skipped chunks, visited: $visited, expected $chunks" }

        for ((cx, cz) in chunks) {
            val chunk = context.loadChunk(migrated.bukkitWorld, cx, cz)
            context.ticks(5)
            context.onMain {
                expectEquals(0, chunk.entities.count { it is Pig }, "Pigs left in chunk $cx, $cz")
            }
        }

        // The migrated world is saved right away, so a reload must not bring the pigs back
        context.closeApi(second)
        val third = context.newApi(settings(2))
        val reloaded = third.loadWorld(uuid, "live")
        for ((cx, cz) in chunks) {
            val chunk = context.loadChunk(reloaded.bukkitWorld, cx, cz)
            context.ticks(5)
            context.onMain {
                expectEquals(0, chunk.entities.count { it is Pig }, "Pigs in chunk $cx, $cz after a reload")
            }
        }
    }
}
