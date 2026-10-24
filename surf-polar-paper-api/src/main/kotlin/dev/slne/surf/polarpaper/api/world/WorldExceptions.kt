package dev.slne.surf.polarpaper.api.world

import net.kyori.adventure.key.Key
import java.util.*

/** Thrown when a game mode identifier is already owned by another plugin. */
class GameModeAlreadyOwnedException(
    val gameModeIdentifier: String,
    val owner: String
) : IllegalStateException("Game mode '$gameModeIdentifier' is already owned by '$owner'")

/** Thrown when a stored world was written by a newer version than the game mode supports. */
class WorldVersionTooNewException(
    val worldUuid: UUID,
    val gameModeIdentifier: String,
    val storedVersion: Int,
    val currentVersion: Int
) : IllegalStateException(
    "World $gameModeIdentifier-$worldUuid has version $storedVersion, which is newer than the current version $currentVersion"
)

/** Thrown when a migration of a world fails. */
class WorldMigrationException(
    val worldUuid: UUID,
    val gameModeIdentifier: String,
    val toVersion: Int,
    cause: Throwable
) : IllegalStateException(
    "Migration of world $gameModeIdentifier-$worldUuid to version $toVersion failed: " +
            (cause.message ?: cause::class.simpleName),
    cause
)

/** Thrown when a world contains data sections that no registered type or handler accounts for. */
class UnhandledWorldDataException(
    val worldUuid: UUID,
    val gameModeIdentifier: String,
    val keys: Set<Key>
) : IllegalStateException(
    "World $gameModeIdentifier-$worldUuid contains data sections without a registered type or handler: " +
            keys.joinToString { it.asString() }
)
