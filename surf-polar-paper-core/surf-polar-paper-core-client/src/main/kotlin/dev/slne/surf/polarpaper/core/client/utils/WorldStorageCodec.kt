@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.polarpaper.core.client.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * Encodes world bytes into the CBOR envelope that every storage provider persists,
 * and decodes them back.
 */
object WorldStorageCodec {
    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = "cbor"
    const val CONTENT_TYPE = "application/cbor"

    fun encode(worldBytes: ByteArray): ByteArray = CborUtils.encode(
        StoredWorld(
            formatVersion = FORMAT_VERSION,
            worldBytes = worldBytes
        )
    )

    fun decode(bytes: ByteArray): ByteArray {
        val storedWorld = CborUtils.decode<StoredWorld>(bytes)

        require(storedWorld.formatVersion == FORMAT_VERSION) {
            "Unsupported world storage format version ${storedWorld.formatVersion}, expected $FORMAT_VERSION"
        }

        return storedWorld.worldBytes
    }

    @Serializable
    private class StoredWorld(
        val formatVersion: Int,

        // Without @ByteString the payload is encoded as an array of integers instead of a single byte string
        @ByteString
        val worldBytes: ByteArray
    )
}
