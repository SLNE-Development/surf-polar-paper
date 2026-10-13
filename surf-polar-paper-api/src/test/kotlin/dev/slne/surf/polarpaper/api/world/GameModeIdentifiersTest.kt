package dev.slne.surf.polarpaper.api.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class GameModeIdentifiersTest {
    @ParameterizedTest
    @ValueSource(strings = ["survival", "SkyWars", "bed_wars", "one-block", "a", "x1"])
    fun `accepts letters, digits, underscores and dashes`(gameModeIdentifier: String) {
        assertTrue(GameModeIdentifiers.isValid(gameModeIdentifier))
        assertEquals(gameModeIdentifier, GameModeIdentifiers.requireValid(gameModeIdentifier))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "..", "../worlds", "a/b", "a\\b", "with space", "dot.ted", "ümlaut", "C:"])
    fun `rejects identifiers that are unsafe in paths or object keys`(gameModeIdentifier: String) {
        assertFalse(GameModeIdentifiers.isValid(gameModeIdentifier))
        assertThrows<IllegalArgumentException> { GameModeIdentifiers.requireValid(gameModeIdentifier) }
    }

    @ParameterizedTest
    @ValueSource(ints = [GameModeIdentifiers.MAX_LENGTH])
    fun `accepts the maximum length`(length: Int) {
        assertTrue(GameModeIdentifiers.isValid("a".repeat(length)))
    }

    @ParameterizedTest
    @ValueSource(ints = [GameModeIdentifiers.MAX_LENGTH + 1])
    fun `rejects identifiers longer than the maximum length`(length: Int) {
        assertFalse(GameModeIdentifiers.isValid("a".repeat(length)))
    }
}
