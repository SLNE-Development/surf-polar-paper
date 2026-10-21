package dev.slne.surf.polarpaper.core.client.data

import dev.slne.surf.polarpaper.api.data.DataSections
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.core.client.utils.CborUtils
import kotlinx.serialization.KSerializer
import net.kyori.adventure.key.Key
import java.util.concurrent.ConcurrentHashMap

/**
 * [DataSections] backed by a map of CBOR-encoded sections keyed by `Key.asString()`.
 * Thread-safe, because hooks and save can run on different region threads.
 */
class MapDataSections(initial: Map<String, ByteArray> = emptyMap()) : DataSections {
    private val sections = ConcurrentHashMap(initial)

    override val keys: Set<Key>
        get() = sections.keys.mapTo(HashSet()) { Key.key(it) }

    override fun <T : Any> get(type: DataType<T>): T? = read(type.key, type.serializer)

    override fun <T : Any> set(type: DataType<T>, value: T?) {
        if (value == null) {
            remove(type.key)
        } else {
            sections[type.key.asString()] = CborUtils.cbor.encodeToByteArray(type.serializer, value)
        }
    }

    override fun remove(key: Key) {
        sections.remove(key.asString())
    }

    override fun contains(key: Key): Boolean = sections.containsKey(key.asString())

    /** Stores an already encoded section, e.g. one read from a stored world. */
    fun putRaw(key: String, bytes: ByteArray) {
        sections[key] = bytes
    }

    /** Snapshot of the encoded sections, for writing them out. */
    fun raw(): Map<String, ByteArray> = HashMap(sections)

    /** Decodes a section without needing a registered [DataType]. */
    fun <T : Any> read(key: Key, serializer: KSerializer<T>): T? =
        sections[key.asString()]?.let { CborUtils.cbor.decodeFromByteArray(serializer, it) }
}
