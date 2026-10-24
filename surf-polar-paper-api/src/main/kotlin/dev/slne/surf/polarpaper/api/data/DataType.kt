package dev.slne.surf.polarpaper.api.data

import kotlinx.serialization.KSerializer
import net.kyori.adventure.key.Key

/**
 * Identifies a typed data section by its [key] and describes how its value is serialized.
 * Two types are equal when their keys are equal, regardless of value type or serializer.
 */
open class DataType<T : Any>(val key: Key, val serializer: KSerializer<T>) {
    override fun equals(other: Any?) = other is DataType<*> && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "DataType($key)"
}
