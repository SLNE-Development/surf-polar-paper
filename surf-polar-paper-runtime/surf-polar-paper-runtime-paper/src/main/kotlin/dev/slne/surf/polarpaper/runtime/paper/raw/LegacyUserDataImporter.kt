package dev.slne.surf.polarpaper.runtime.paper.raw

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import live.minehub.polarpaper.core.userdata.EntityUtil
import live.minehub.polarpaper.core.util.MemorySegmentReader
import live.minehub.polarpaper.core.world.PolarWorld
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.foreign.MemorySegment

/**
 * Reads the chunk user data that Polar's `EntitiesWorldAccess` writes: a version byte, the entities
 * (count, then x/y/z relative to the chunk, yaw, pitch and the entity NBT bytes) and, from version 2, the chunk's
 * persistent data container as a byte array.
 */
object LegacyUserDataImporter {
    private const val PDC_VERSION = 2
    private const val CHUNK_SIZE = 16

    /** Converts legacy user data into data sections. Empty or null user data has no sections. */
    fun importChunk(userData: ByteArray?, chunkX: Int, chunkZ: Int): Map<String, ByteArray> {
        if (userData == null || userData.isEmpty()) return emptyMap()

        val sections = MapDataSections()
        try {
            val reader = MemorySegmentReader(MemorySegment.ofArray(userData))
            val version = reader.readByte().toInt()

            val entities = EntityUtil.getEntities(reader).mapNotNull { entity ->
                val type = NbtIo.read(DataInputStream(ByteArrayInputStream(entity.bytes())))
                    .getStringOr("id", "")
                // An entity without a type cannot be spawned again
                if (type.isBlank()) return@mapNotNull null

                StoredEntity(
                    type,
                    entity.x() + chunkX * CHUNK_SIZE,
                    entity.y(),
                    entity.z() + chunkZ * CHUNK_SIZE,
                    entity.yaw(),
                    entity.pitch(),
                    entity.bytes()
                )
            }
            if (entities.isNotEmpty()) sections[BuiltInData.ENTITIES] = entities

            if (version >= PDC_VERSION) {
                val pdc = reader.readByteArray()
                if (pdc.isNotEmpty()) sections[BuiltInData.CHUNK_PDC] = pdc
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to import legacy user data of chunk $chunkX, $chunkZ", e)
        }
        return sections.raw()
    }

    /** Rewrites the user data of every chunk that is not a section container into container form. */
    fun normalize(polarWorld: PolarWorld) {
        for (chunk in polarWorld.chunks().toList()) {
            if (SectionContainerCodec.isContainer(chunk.userData())) continue

            val sections = importChunk(chunk.userData(), chunk.x(), chunk.z())
            polarWorld.updateChunkAt(chunk.x(), chunk.z(), chunk.withUserData(SectionContainerCodec.encode(sections)))
        }
    }
}
