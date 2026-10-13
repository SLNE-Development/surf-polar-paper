package dev.slne.surf.polarpaper.runtime.microservice.database.tables

import dev.slne.surf.database.libs.org.jetbrains.exposed.v1.core.BlobColumnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldStorageTableTest {
    @Test
    fun `worlds are unique per uuid and game mode`() {
        val uniqueIndices = WorldStorageTable.indices.filter { it.unique }

        assertEquals(
            listOf(listOf(WorldStorageTable.worldUuid, WorldStorageTable.gameModeIdentifier)),
            uniqueIndices.map { it.columns }
        )
    }

    @Test
    fun `the world uuid alone is not unique`() {
        assertTrue(WorldStorageTable.indices.none { it.unique && it.columns == listOf(WorldStorageTable.worldUuid) })
    }

    @Test
    fun `world bytes are stored as a blob`() {
        assertInstanceOf(BlobColumnType::class.java, WorldStorageTable.worldBytes.columnType)
    }
}
