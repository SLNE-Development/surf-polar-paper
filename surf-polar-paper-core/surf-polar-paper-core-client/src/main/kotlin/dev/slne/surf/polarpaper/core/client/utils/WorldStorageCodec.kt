@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.polarpaper.core.client.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/** A decoded storage envelope: the world's migration version and its Polar bytes. */
data class DecodedWorld(val worldVersion: Int, val worldBytes: ByteArray)

/**
 * Encodes world bytes into the CBOR envelope that every storage provider persists,
 * and decodes them back.
 */
object WorldStorageCodec {
    const val FORMAT_VERSION = 2
    const val FILE_EXTENSION = "cbor"
    const val CONTENT_TYPE = "application/cbor"

    fun encode(worldVersion: Int, worldBytes: ByteArray): ByteArray = CborUtils.encode(
        StoredWorld(
            formatVersion = FORMAT_VERSION,
            worldVersion = worldVersion,
            worldBytes = worldBytes
        )
    )

    fun decode(bytes: ByteArray): DecodedWorld {
        val storedWorld = CborUtils.decode<StoredWorld>(bytes)

        require(storedWorld.formatVersion == FORMAT_VERSION) {
            "Unsupported world storage format version ${storedWorld.formatVersion}, expected $FORMAT_VERSION"
        }

        return DecodedWorld(storedWorld.worldVersion, storedWorld.worldBytes)
    }

    @Serializable
    private class StoredWorld(
        val formatVersion: Int,
        val worldVersion: Int,

        // Without @ByteString the payload is encoded as an array of integers instead of a single byte string
        @ByteString
        val worldBytes: ByteArray
    )
}
