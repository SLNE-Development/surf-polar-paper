package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import live.minehub.polarpaper.core.userdata.EntitySerializer
import live.minehub.polarpaper.core.userdata.EntityUtil
import live.minehub.polarpaper.core.world.PolarEntity
import live.minehub.polarpaper.paper_26_3.EntitySerializerImpl
import net.minecraft.nbt.NbtIo
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Turns entities into Polar entity bytes and back. Split from the handler so tests do not need a server. */
interface EntityCodec {
    fun serialize(entity: Entity): ByteArray
    fun spawn(world: World, stored: StoredEntity)
}

/** The codec backed by Polar's [EntitySerializer], so legacy-imported entities restore through the same path. */
class PolarEntityCodec(
    private val plugin: Plugin,
    private val serializer: EntitySerializer = EntitySerializerImpl()
) : EntityCodec {
    override fun serialize(entity: Entity): ByteArray =
        serializer.entityToBytes(entity, plugin) ?: error("Entity serializer returned no bytes for $entity")

    override fun spawn(world: World, stored: StoredEntity) {
        val location = Location(world, stored.x, stored.y, stored.z, stored.yaw, stored.pitch)
        val polarEntity = PolarEntity(stored.x, stored.y, stored.z, stored.yaw, stored.pitch, stored.nbt)
        val nms = polarEntity.toNMSEntity(serializer, world, location)
        if (nms == null) {
            log.atWarning().log("Could not recreate entity %s at %s", stored.type, location)
            return
        }
        EntityUtil.spawnEntity(nms.bukkitEntity, world)
    }

    private companion object {
        val log = logger()
    }
}

/**
 * Stores the entities no handler claimed, and the chunk's persistent data container.
 * It is not a [dev.slne.surf.polarpaper.api.data.ChunkDataHandler] because it has to run last, on what is left over.
 */
class EntitiesHandler internal constructor(private val codec: EntityCodec) {
    constructor(plugin: Plugin) : this(PolarEntityCodec(plugin))

    private val log = logger()

    /** Writes [BuiltInData.ENTITIES] and [BuiltInData.CHUNK_PDC] from the live chunk; empty results remove the section. */
    fun capture(chunk: Chunk, unclaimed: List<Entity>, sections: MapDataSections) {
        val stored = unclaimed.filter { it.isPersistent }.mapNotNull { entity ->
            val nbt = codec.serialize(entity)
            val type = entityType(nbt)
            if (type.isBlank()) {
                // Without a type the entity could never be spawned again
                log.atWarning().log("Skipping entity %s of chunk %s, %s: it has no type", entity, chunk.x, chunk.z)
                return@mapNotNull null
            }

            val location = entity.location
            StoredEntity(type, location.x, location.y, location.z, location.yaw, location.pitch, nbt)
        }
        sections[BuiltInData.ENTITIES] = stored.ifEmpty { null }

        val pdc = chunk.persistentDataContainer
        sections[BuiltInData.CHUNK_PDC] = if (pdc.isEmpty) null else pdc.serializeToBytes()
    }

    /** Spawns the stored entities and reads the persistent data container back. Failures are not caught here. */
    fun restore(chunk: Chunk, sections: MapDataSections) {
        sections[BuiltInData.CHUNK_PDC]?.let { chunk.persistentDataContainer.readFromBytes(it) }

        sections[BuiltInData.ENTITIES]?.forEach { codec.spawn(chunk.world, it) }
    }

    private fun entityType(nbt: ByteArray): String =
        NbtIo.read(DataInputStream(ByteArrayInputStream(nbt))).getStringOr("id", "")
}
