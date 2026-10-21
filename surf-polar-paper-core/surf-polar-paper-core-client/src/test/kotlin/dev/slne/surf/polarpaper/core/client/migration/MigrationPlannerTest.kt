package dev.slne.surf.polarpaper.core.client.migration

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.GameModeSettings
import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class MigrationPlannerTest {
    private val world = UUID.randomUUID()

    private class Step(toVersion: Int) : WorldMigration(toVersion)

    private fun settings(current: Int, vararg versions: Int) =
        GameModeSettings("test", current, versions.map { Step(it) })

    private fun plan(settings: GameModeSettings, stored: Int) =
        MigrationPlanner.plan(world, settings, stored).map { it.toVersion }

    @Test
    fun `equal versions plan nothing`() {
        assertTrue(plan(settings(3, 2, 3), 3).isEmpty())
    }

    @Test
    fun `plans migrations above the stored version in order`() {
        assertEquals(listOf(2, 4), plan(settings(4, 4, 2), 1))
    }

    @Test
    fun `gaps are skipped`() {
        assertTrue(plan(settings(3), 1).isEmpty())
    }

    @Test
    fun `a world at version 1 plans every migration up to 100 in order`() {
        val versions = ((2..100) - (50..59).toSet()).shuffled(Random(42))
        val settings = settings(100, *versions.toIntArray())

        assertEquals((2..100) - (50..59).toSet(), plan(settings, 1))
        assertEquals((60..100).toList(), plan(settings, 55))
        assertTrue(plan(settings, 100).isEmpty())
    }

    @Test
    fun `stored newer than current is refused`() {
        val e = assertThrows<WorldVersionTooNewException> { plan(settings(4, 2), 5) }

        assertEquals(5, e.storedVersion)
        assertEquals(4, e.currentVersion)
        assertEquals(world, e.worldUuid)
    }
}
