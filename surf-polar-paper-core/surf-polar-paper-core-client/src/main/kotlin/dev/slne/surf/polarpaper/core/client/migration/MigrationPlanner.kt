package dev.slne.surf.polarpaper.core.client.migration

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.GameModeSettings
import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import java.util.*

/** Decides which migrations a stored world still needs. */
object MigrationPlanner {
    /**
     * Returns the migrations with `storedVersion < toVersion <= currentVersion`, ascending.
     * Versions without a registered migration are skipped, so the result is empty when nothing applies.
     *
     * @throws WorldVersionTooNewException when [storedVersion] is newer than the game mode's current version
     */
    fun plan(worldUuid: UUID, gameMode: GameModeSettings, storedVersion: Int): List<WorldMigration> {
        if (storedVersion > gameMode.currentVersion) {
            throw WorldVersionTooNewException(
                worldUuid,
                gameMode.identifier,
                storedVersion,
                gameMode.currentVersion
            )
        }

        // Sort here instead of trusting the settings order; the planner is the one place that guarantees it.
        return gameMode.migrations
            .filter { it.toVersion > storedVersion && it.toVersion <= gameMode.currentVersion }
            .sortedBy { it.toVersion }
    }
}
