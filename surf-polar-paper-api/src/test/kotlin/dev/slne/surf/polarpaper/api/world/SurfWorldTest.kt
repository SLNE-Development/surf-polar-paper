package dev.slne.surf.polarpaper.api.world

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class SurfWorldTest {
    private val uuid = UUID.fromString("6f1d1b0e-1f0a-4c3b-9a55-0d7c1c1c7a10")

    @Test
    fun `worlds with the same uuid and game mode are equal regardless of their bytes`() {
        val first = SurfWorld(uuid, "survival", byteArrayOf(1, 2, 3))
        val second = SurfWorld(uuid, "survival", byteArrayOf(9))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `worlds differ by uuid or game mode`() {
        val world = SurfWorld(uuid, "survival", ByteArray(0))

        assertNotEquals(world, SurfWorld(UUID.randomUUID(), "survival", ByteArray(0)))
        assertNotEquals(world, SurfWorld(uuid, "creative", ByteArray(0)))
    }

    @Test
    fun `hash code is stable when the world bytes change`() {
        val world = SurfWorld(uuid, "survival", byteArrayOf(1))
        val set = hashSetOf(world)

        world.worldBytes = ByteArray(1024) { it.toByte() }

        assertTrue(world in set)
    }

    @Test
    fun `name is the uuid`() {
        assertEquals(uuid.toString(), SurfWorld(uuid, "survival", ByteArray(0)).name)
    }

    @Test
    fun `info carries uuid and game mode`() {
        assertEquals(WorldInfo(uuid, "survival"), SurfWorld(uuid, "survival", byteArrayOf(1)).info())
    }

    @Test
    fun `toString reports the byte count instead of the bytes`() {
        val world = SurfWorld(uuid, "survival", ByteArray(42))

        assertTrue("worldBytes=42 bytes" in world.toString())
    }

    @Test
    fun `world and info render the same component text`() {
        val world = SurfWorld(uuid, "survival", ByteArray(0))
        val plain = PlainTextComponentSerializer.plainText()

        assertEquals("survival-$uuid", plain.serialize(world.asComponent()))
        assertEquals(plain.serialize(world.asComponent()), plain.serialize(world.info().asComponent()))
    }
}
