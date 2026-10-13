package dev.slne.surf.polarpaper.api.world

import java.util.*

/**
 * Thrown when a world cannot be used because another server holds its lock.
 */
class WorldLockedException(
    val worldUuid: UUID,
    val gameModeIdentifier: String,
    val owner: String
) : IllegalStateException("World $gameModeIdentifier-$worldUuid is locked by server '$owner'")
