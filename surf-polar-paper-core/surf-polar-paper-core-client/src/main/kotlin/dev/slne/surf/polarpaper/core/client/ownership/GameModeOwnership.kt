package dev.slne.surf.polarpaper.core.client.ownership

import dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException
import java.util.concurrent.ConcurrentHashMap

/** Tracks which plugin owns which game mode identifier. */
class GameModeOwnership {
    private val owners = ConcurrentHashMap<String, String>()

    /**
     * Claims [gameModeIdentifier] for [owner]; claiming it again as the same owner is a no-op.
     *
     * @throws GameModeAlreadyOwnedException when another owner holds it
     */
    fun claim(gameModeIdentifier: String, owner: String) {
        owners.compute(gameModeIdentifier) { _, current ->
            if (current != null && current != owner) {
                throw GameModeAlreadyOwnedException(gameModeIdentifier, current)
            }
            owner
        }
    }

    /** Frees every game mode held by [owner]. */
    fun release(owner: String) {
        owners.values.removeIf { it == owner }
    }

    fun ownerOf(gameModeIdentifier: String): String? = owners[gameModeIdentifier]
}
