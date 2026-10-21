package dev.slne.surf.polarpaper.core.client.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SectionContainerCodecTest {
    @Test
    fun `container round-trips sections`() {
        val sections = mapOf("surf:entities" to byteArrayOf(1, 2, 3), "nexo:furniture" to ByteArray(0))

        val encoded = SectionContainerCodec.encode(sections)
        val decoded = SectionContainerCodec.decode(encoded)

        assertTrue(SectionContainerCodec.isContainer(encoded))
        assertEquals(sections.keys, decoded.keys)
        sections.forEach { (key, value) -> assertArrayEquals(value, decoded.getValue(key)) }
    }

    @Test
    fun `empty or null user data decodes to no sections`() {
        assertTrue(SectionContainerCodec.decode(null).isEmpty())
        assertTrue(SectionContainerCodec.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `bytes without the magic are not a container`() {
        val legacy = byteArrayOf(9, 8, 7, 6, 5)

        assertFalse(SectionContainerCodec.isContainer(legacy))
        assertFalse(SectionContainerCodec.isContainer(null))
        assertThrows<IllegalArgumentException> { SectionContainerCodec.decode(legacy) }
    }
}
