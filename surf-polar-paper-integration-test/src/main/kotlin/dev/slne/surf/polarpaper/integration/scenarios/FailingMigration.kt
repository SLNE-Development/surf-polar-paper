package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.world.WorldMigrationException
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.ScenarioFailure
import dev.slne.surf.polarpaper.integration.check
import dev.slne.surf.polarpaper.integration.expectEquals
import java.util.UUID

/** A migration that throws aborts the load and leaves the stored world byte-identical. */
class FailingMigration : Scenario("failing-migration-leaves-storage-untouched") {
    override suspend fun run(context: ScenarioContext) {
        val old = context.newApi { gameMode("failing") { version = 2 } }
        val uuid = UUID.randomUUID()
        val world = old.createWorld(uuid, "failing")
        context.anchor(world.bukkitWorld, 0, 0)
        old.saveWorld(world)
        context.closeApi(old)

        val before = context.storedBytes("failing", uuid)

        val updated = context.newApi {
            gameMode("failing") {
                version = 3
                migration(object : WorldMigration(3) {
                    override fun migrateRaw(world: RawWorld) {
                        // Changes something first, so a leaked partial result would show
                        world.chunk(0, 0)?.setBlock(0, 60, 0, "minecraft:diamond_block")
                        throw IllegalStateException("migration three is broken")
                    }
                })
            }
        }

        try {
            updated.loadWorld(uuid, "failing")
            throw ScenarioFailure("A failing migration did not fail the load")
        } catch (exception: WorldMigrationException) {
            expectEquals(3, exception.toVersion, "Version named by the exception")
        }

        check(context.storedBytes("failing", uuid).contentEquals(before)) { "The failed migration changed the stored bytes" }
        check(updated.findWorld(uuid, "failing") == null) { "The failed world is cached as loaded" }
        check(context.bukkitWorldOf(uuid) == null) { "The failed world is still loaded in Bukkit" }
    }
}
