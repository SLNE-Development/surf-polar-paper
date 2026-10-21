package dev.slne.surf.polarpaper.api.data

import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import net.kyori.adventure.key.Key

/** An entity stored in world coordinates. [nbt] holds the Polar entity serializer bytes. */
@Serializable
data class StoredEntity(
    val type: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    @ByteString val nbt: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredEntity) return false
        return type == other.type && x == other.x && y == other.y && z == other.z &&
                yaw == other.yaw && pitch == other.pitch && nbt.contentEquals(other.nbt)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        result = 31 * result + yaw.hashCode()
        result = 31 * result + pitch.hashCode()
        result = 31 * result + nbt.contentHashCode()
        return result
    }
}

/** A furniture piece of a furniture plugin, in world coordinates. */
@Serializable
data class FurnitureRecord(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val properties: Map<String, String> = emptyMap()
)

/** A custom block of a block plugin, in world coordinates. */
@Serializable
data class CustomBlockRecord(
    val id: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val properties: Map<String, String> = emptyMap()
)

/** Data types of the built-in handlers. Their keys are reserved and cannot be used by custom types. */
object BuiltInData {
    val ENTITIES = DataType(Key.key("surf", "entities"), ListSerializer(StoredEntity.serializer()))
    val CHUNK_PDC = DataType(Key.key("surf", "chunk-pdc"), ByteArraySerializer())
    val NEXO_FURNITURE = DataType(Key.key("nexo", "furniture"), ListSerializer(FurnitureRecord.serializer()))
    val CRAFT_ENGINE_FURNITURE =
        DataType(Key.key("craftengine", "furniture"), ListSerializer(FurnitureRecord.serializer()))
    val CRAFT_ENGINE_BLOCKS =
        DataType(Key.key("craftengine", "blocks"), ListSerializer(CustomBlockRecord.serializer()))

    /** All built-in keys, enabled or not. */
    val RESERVED_KEYS: Set<Key> =
        setOf(ENTITIES.key, CHUNK_PDC.key, NEXO_FURNITURE.key, CRAFT_ENGINE_FURNITURE.key, CRAFT_ENGINE_BLOCKS.key)
}
