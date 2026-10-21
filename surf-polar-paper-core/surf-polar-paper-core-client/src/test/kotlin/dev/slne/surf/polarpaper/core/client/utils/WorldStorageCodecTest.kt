@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.polarpaper.core.client.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class WorldStorageCodecTest {
    @Serializable
    private class V1Envelope(val formatVersion: Int, @ByteString val worldBytes: ByteArray)

    @Test
    fun `encodes and decodes the world version with the bytes`() {
        val worldBytes = Random(42).nextBytes(1_000_000)

        val decoded = WorldStorageCodec.decode(WorldStorageCodec.encode(7, worldBytes))

        assertEquals(7, decoded.worldVersion)
        assertArrayEquals(worldBytes, decoded.worldBytes)
    }

    @Test
    fun `empty world bytes round trip`() {
        val decoded = WorldStorageCodec.decode(WorldStorageCodec.encode(0, ByteArray(0)))

        assertArrayEquals(ByteArray(0), decoded.worldBytes)
    }

    @Test
    fun `world bytes are stored as a single byte string`() {
        val worldBytes = ByteArray(1_000_000) { 0xFF.toByte() }

        val encoded = WorldStorageCodec.encode(0, worldBytes)

        assertTrue(encoded.size - worldBytes.size < 64) {
            "Envelope overhead is ${encoded.size - worldBytes.size} bytes"
        }
    }

    @Test
    fun `rejects format version 1`() {
        val v1 = CborUtils.encode(V1Envelope(1, byteArrayOf(1, 2, 3)))

        val e = assertThrows<IllegalArgumentException> { WorldStorageCodec.decode(v1) }
        assertEquals("Unsupported world storage format version 1, expected 2", e.message)
    }

    @Test
    fun `decode rejects bytes that are not a world envelope`() {
        assertThrows<Exception> {
            WorldStorageCodec.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }
    }
}
