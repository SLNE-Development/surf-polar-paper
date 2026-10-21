package dev.slne.surf.polarpaper.core.client.data

import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.data.FurnitureRecord
import kotlinx.serialization.builtins.ListSerializer
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapDataSectionsTest {
    private val type = DataType(Key.key("nexo", "furniture"), ListSerializer(FurnitureRecord.serializer()))
    private val records = listOf(FurnitureRecord("chair", 1.0, 2.0, 3.0, 90f, 0f, mapOf("a" to "b")))

    @Test
    fun `typed get returns what set stored`() {
        val sections = MapDataSections()

        sections[type] = records

        assertEquals(records, sections[type])
        assertTrue(sections.contains(type.key))
        assertEquals(setOf(type.key), sections.keys)
    }

    @Test
    fun `set null removes the section`() {
        val sections = MapDataSections()
        sections[type] = records

        sections[type] = null

        assertNull(sections[type])
        assertFalse(sections.contains(type.key))
        assertTrue(sections.raw().isEmpty())
    }

    @Test
    fun `read decodes a section by key and serializer without a registered type`() {
        val sections = MapDataSections()
        sections[type] = records
        val reloaded = MapDataSections(sections.raw())

        val read = reloaded.read(type.key, ListSerializer(FurnitureRecord.serializer()))

        assertEquals(records, read)
        assertNull(reloaded.read(Key.key("other", "missing"), ListSerializer(FurnitureRecord.serializer())))
    }
}
