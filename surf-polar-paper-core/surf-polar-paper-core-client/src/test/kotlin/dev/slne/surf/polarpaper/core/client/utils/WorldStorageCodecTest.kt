package dev.slne.surf.polarpaper.core.client.utils

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class WorldStorageCodecTest {
    @Test
    fun `decode returns the encoded world bytes`() {
        val worldBytes = Random(42).nextBytes(1_000_000)

        val decoded = WorldStorageCodec.decode(WorldStorageCodec.encode(worldBytes))

        assertArrayEquals(worldBytes, decoded)
    }

    @Test
    fun `empty world bytes round trip`() {
        val decoded = WorldStorageCodec.decode(WorldStorageCodec.encode(ByteArray(0)))

        assertArrayEquals(ByteArray(0), decoded)
    }

    @Test
    fun `world bytes are stored as a single byte string`() {
        val worldBytes = ByteArray(1_000_000) { 0xFF.toByte() }

        val encoded = WorldStorageCodec.encode(worldBytes)

        assertTrue(encoded.size - worldBytes.size < 64) {
            "Envelope overhead is ${encoded.size - worldBytes.size} bytes"
        }
    }

    @Test
    fun `decode rejects bytes that are not a world envelope`() {
        assertThrows<Exception> {
            WorldStorageCodec.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
    }
}
