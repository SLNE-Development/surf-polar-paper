package dev.slne.surf.polarpaper.runtime.paper

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NmsClasspathTest {

    @Test
    fun `nms compound tags are on the test classpath`() {
        val tag = CompoundTag()
        tag.putString("id", "minecraft:pig")

        assertEquals("minecraft:pig", tag.getStringOr("id", ""))
    }
}
