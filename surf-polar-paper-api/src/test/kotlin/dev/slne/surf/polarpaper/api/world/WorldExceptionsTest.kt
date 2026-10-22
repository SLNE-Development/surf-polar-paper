package dev.slne.surf.polarpaper.api.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WorldExceptionsTest {
    @Test
    fun `a migration failure names the cause message`() {
        val exception = WorldMigrationException(UUID.randomUUID(), "survival", 2, IllegalStateException("boom"))

        assertTrue(exception.message!!.endsWith("to version 2 failed: boom"), exception.message)
    }

    @Test
    fun `a migration failure falls back to the cause type when the cause has no message`() {
        val exception = WorldMigrationException(UUID.randomUUID(), "survival", 2, NullPointerException())

        assertTrue(exception.message!!.endsWith("to version 2 failed: NullPointerException"), exception.message)
        assertEquals(NullPointerException::class, exception.cause!!::class)
    }
}
