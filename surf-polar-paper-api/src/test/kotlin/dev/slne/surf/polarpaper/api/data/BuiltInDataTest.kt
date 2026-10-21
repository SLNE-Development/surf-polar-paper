package dev.slne.surf.polarpaper.api.data

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuiltInDataTest {
    @Test
    fun `built-in keys match the spec`() {
        assertEquals(Key.key("surf", "entities"), BuiltInData.ENTITIES.key)
        assertEquals(Key.key("surf", "chunk-pdc"), BuiltInData.CHUNK_PDC.key)
        assertEquals(Key.key("nexo", "furniture"), BuiltInData.NEXO_FURNITURE.key)
        assertEquals(Key.key("craftengine", "furniture"), BuiltInData.CRAFT_ENGINE_FURNITURE.key)
        assertEquals(Key.key("craftengine", "blocks"), BuiltInData.CRAFT_ENGINE_BLOCKS.key)
    }
}
