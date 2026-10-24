@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.polarpaper.core.client.data

import dev.slne.surf.polarpaper.core.client.utils.CborUtils
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * Encodes chunk data sections into the Polar user data bytes: a magic prefix followed by CBOR.
 * The magic lets callers tell a container from legacy user data written by older versions.
 */
object SectionContainerCodec {
    const val FORMAT_VERSION = 1

    /** "SURF" */
    private val MAGIC = byteArrayOf(0x53, 0x55, 0x52, 0x46)

    fun isContainer(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encode(sections: Map<String, ByteArray>): ByteArray =
        MAGIC + CborUtils.encode(Container(FORMAT_VERSION, sections.mapValues { Section(it.value) }))

    fun decode(bytes: ByteArray?): Map<String, ByteArray> {
        if (bytes == null || bytes.isEmpty()) return emptyMap()
        require(isContainer(bytes)) { "Bytes are not a section container" }

        val container = CborUtils.decode<Container>(bytes.copyOfRange(MAGIC.size, bytes.size))
        require(container.formatVersion == FORMAT_VERSION) {
            "Unsupported section container version ${container.formatVersion}, expected $FORMAT_VERSION"
        }
        return container.sections.mapValues { it.value.bytes }
    }

    @Serializable
    private class Container(
        val formatVersion: Int,
        val sections: Map<String, Section>
    )

    // Wrapper because @ByteString only applies to properties; without it bytes are encoded as an integer array
    @Serializable
    private class Section(@ByteString val bytes: ByteArray)
}
