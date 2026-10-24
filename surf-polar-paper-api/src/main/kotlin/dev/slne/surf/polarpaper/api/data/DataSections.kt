package dev.slne.surf.polarpaper.api.data

import net.kyori.adventure.key.Key

/**
 * Keyed, typed data sections attached to a world or a chunk and persisted with it.
 */
interface DataSections {
    val keys: Set<Key>

    operator fun <T : Any> get(type: DataType<T>): T?

    /** Stores [value] under the type's key. A null [value] removes the section. */
    operator fun <T : Any> set(type: DataType<T>, value: T?)

    fun remove(key: Key)

    fun contains(key: Key): Boolean
}
