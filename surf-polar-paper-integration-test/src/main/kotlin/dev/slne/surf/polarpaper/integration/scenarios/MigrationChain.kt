package dev.slne.surf.polarpaper.integration.scenarios

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettingsBuilder
import dev.slne.surf.polarpaper.integration.Scenario
import dev.slne.surf.polarpaper.integration.ScenarioContext
import dev.slne.surf.polarpaper.integration.expectEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.key.Key
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Migrations 2..100 with a gap at 50..59 each record their version, and every tenth one changes a block. */
class MigrationChain : Scenario("migration-chain-1-to-100") {
    private val applied = DataType(Key.key("integration", "applied-versions"), ListSerializer(Int.serializer()))
    private val runs = AtomicInteger()
    private val gap = 50..59

    private inner class Step(version: Int) : WorldMigration(version) {
        override fun migrateRaw(world: RawWorld) {
            runs.incrementAndGet()
            world.data[applied] = (world.data[applied] ?: emptyList()) + toVersion

            if (toVersion % 10 == 0) {
                world.chunk(0, 0)!!.setBlock(toVersion / 10, 64, 0, "minecraft:gold_block")
            }
        }
    }

    private fun settings(version: Int): PolarPaperSettingsBuilder.() -> Unit = {
        gameMode("chain") {
            this.version = version
            if (version >= 2) {
                (2..version).filterNot { it in gap }.forEach { migration(Step(it)) }
            }
        }
        data(applied)
    }

    override suspend fun run(context: ScenarioContext) {
        val first = context.newApi(settings(1))
        val uuid = UUID.randomUUID()
        val created = first.createWorld(uuid, "chain")
        context.anchor(created.bukkitWorld, 0, 0)
        first.saveWorld(created)
        context.closeApi(first)

        val second = context.newApi(settings(100))
        val migrated = second.loadWorld(uuid, "chain")
        val expected = (2..100).filterNot { it in gap }

        expectEquals(100, migrated.version, "Version after the chain")
        expectEquals(expected, migrated.data[applied], "Versions the migrations appended")
        expectEquals(expected.size, runs.get(), "Number of migration runs")

        context.loadChunk(migrated.bukkitWorld, 0, 0)
        context.onMain {
            val changed = (10..100 step 10).filterNot { it in gap }
            for (version in changed) {
                expectEquals(
                    Material.GOLD_BLOCK,
                    migrated.bukkitWorld.getBlockAt(version / 10, 64, 0).type,
                    "Block written by migration $version"
                )
            }
            // 50 is inside the gap, so its block must be missing
            expectEquals(Material.AIR, migrated.bukkitWorld.getBlockAt(5, 64, 0).type, "Block of the skipped version 50")
        }
        context.closeApi(second)

        // Stored at 100 now: loading again must not run anything
        val before = runs.get()
        val third = context.newApi(settings(100))
        val reloaded = third.loadWorld(uuid, "chain")
        expectEquals(before, runs.get(), "Migration runs after reloading at the current version")
        context.loadChunk(reloaded.bukkitWorld, 0, 0)
        context.onMain {
            for (version in (10..100 step 10).filterNot { it in gap }) {
                expectEquals(
                    Material.GOLD_BLOCK,
                    reloaded.bukkitWorld.getBlockAt(version / 10, 64, 0).type,
                    "Block of migration $version after reopening the migrated world"
                )
            }
        }
        expectEquals(expected, reloaded.data[applied], "Versions after reloading")
    }
}
