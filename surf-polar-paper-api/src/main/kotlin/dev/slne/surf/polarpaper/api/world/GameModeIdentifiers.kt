package dev.slne.surf.polarpaper.api.world

/**
 * Validates game mode identifiers, which are used as directory names and object key prefixes
 * by the storage providers.
 */
object GameModeIdentifiers {
    const val MAX_LENGTH = 64

    private val PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")

    fun isValid(gameModeIdentifier: String) = PATTERN.matches(gameModeIdentifier)

    fun requireValid(gameModeIdentifier: String): String {
        require(isValid(gameModeIdentifier)) {
            "Invalid game mode identifier '$gameModeIdentifier', expected 1-$MAX_LENGTH characters of A-Z, a-z, 0-9, '_' or '-'"
        }

        return gameModeIdentifier
    }
}
