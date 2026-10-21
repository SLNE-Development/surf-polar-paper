package dev.slne.surf.polarpaper.core.client.ownership

import dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameModeOwnershipTest {
    @Test
    fun `a second owner cannot claim a game mode`() {
        val ownership = GameModeOwnership()
        ownership.claim("bedwars", "A")

        val e = assertThrows<GameModeAlreadyOwnedException> { ownership.claim("bedwars", "B") }

        assertEquals("bedwars", e.gameModeIdentifier)
        assertEquals("A", e.owner)
        assertEquals("A", ownership.ownerOf("bedwars"))
    }

    @Test
    fun `release frees all game modes of an owner`() {
        val ownership = GameModeOwnership()
        ownership.claim("bedwars", "A")
        ownership.claim("skywars", "A")
        ownership.claim("lobby", "B")

        ownership.release("A")

        assertNull(ownership.ownerOf("bedwars"))
        assertNull(ownership.ownerOf("skywars"))
        assertEquals("B", ownership.ownerOf("lobby"))
        ownership.claim("bedwars", "B")
    }

    @Test
    fun `the same owner may claim twice`() {
        val ownership = GameModeOwnership()
        ownership.claim("bedwars", "A")
        ownership.claim("bedwars", "A")

        assertEquals("A", ownership.ownerOf("bedwars"))
    }
}
