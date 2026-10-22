package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import org.bukkit.Material
import java.util.UUID

/** Berry bushes placed at version 1 are removed by a raw migration to version 2. */
class RawMigrationStrawberries : Scenario("raw-migration-strawberries") {
    private object RemoveStrawberries : WorldMigration(2) {
        override fun migrateRaw(world: RawWorld) {
            val minY = world.minSection * 16
            val maxY = (world.maxSection + 1) * 16 - 1

            for (chunk in world.chunks) {
                for (x in 0..15) for (z in 0..15) for (y in minY..maxY) {
                    if (chunk.getBlock(x, y, z).startsWith("minecraft:sweet_berry_bush")) {
                        chunk.setBlock(x, y, z, "minecraft:air")
                    }
                }
            }
        }
    }

    private val bushes = listOf(Triple(1, 61, 1), Triple(5, 61, 7), Triple(17, 61, 3), Triple(-5, 61, -9))

    private val chunkPositions get() = bushes.map { Math.floorDiv(it.first, 16) to Math.floorDiv(it.third, 16) }.toSet()

    override suspend fun run(context: ScenarioContext) {
        val api = context.newApi { gameMode("strawberries") { version = 1 } }
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "strawberries")
        val bukkit = world.bukkitWorld

        for ((cx, cz) in chunkPositions) context.anchor(bukkit, cx, cz)
        context.onMain {
            for ((x, y, z) in bushes) {
                bukkit.getBlockAt(x, y, z).setType(Material.SWEET_BERRY_BUSH, false)
            }
            check(bushes.all { (x, y, z) -> bukkit.getBlockAt(x, y, z).type == Material.SWEET_BERRY_BUSH }) {
                "The berry bushes were not placed"
            }
        }

        api.saveWorld(world)
        expectEquals(1, world.version, "Stored version before the update")
        context.closeApi(api)

        val updated = context.newApi {
            gameMode("strawberries") {
                version = 2
                migration(RemoveStrawberries)
            }
        }
        val migrated = updated.loadWorld(uuid, "strawberries")
        expectEquals(2, migrated.version, "Version after the migration")
        verifyWorld(context, migrated.bukkitWorld, "right after the migration")

        // The migrated world was saved by the load: what is stored must have the same content
        context.closeApi(updated)
        val reopened = context.newApi {
            gameMode("strawberries") {
                version = 2
                migration(RemoveStrawberries)
            }
        }.loadWorld(uuid, "strawberries")
        expectEquals(2, reopened.version, "Stored version after reopening")
        verifyWorld(context, reopened.bukkitWorld, "after reopening the migrated world")
    }

    private suspend fun verifyWorld(context: ScenarioContext, world: org.bukkit.World, moment: String) {
        for ((cx, cz) in chunkPositions) context.loadChunk(world, cx, cz)
        context.onMain {
            for ((x, y, z) in bushes) {
                expectEquals(Material.AIR, world.getBlockAt(x, y, z).type, "Block at $x, $y, $z $moment")
            }
            // The migration must only remove the bushes: every chunk keeps its anchor
            for ((cx, cz) in chunkPositions) {
                expectEquals(Material.STONE, world.getBlockAt(cx * 16, 60, cz * 16).type, "Anchor of chunk $cx, $cz $moment")
            }
        }
    }
}
