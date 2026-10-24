package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import live.minehub.polarpaper.core.userdata.EntitySerializer
import live.minehub.polarpaper.core.world.PolarEntity
import live.minehub.polarpaper.paper_26_3.EntitySerializerImpl
import net.minecraft.core.UUIDUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntitySpawnRequest
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ChunkPos
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Turns entities into Polar entity bytes and back. Split from the handler so tests do not need a server. */
interface EntityCodec {
    /** The entity as Polar entity bytes, or null when the entity cannot be stored (removed, lightning, no id). */
    fun serialize(entity: Entity): ByteArray?

    /** Adds the stored entities, with their passengers, to the live [chunk] without firing spawn events. */
    fun spawn(chunk: Chunk, stored: List<StoredEntity>)
}

/** The codec backed by Polar's [EntitySerializer], so legacy-imported entities restore through the same path. */
class PolarEntityCodec(
    private val plugin: Plugin,
    private val serializer: EntitySerializer = EntitySerializerImpl()
) : EntityCodec {
    override fun serialize(entity: Entity): ByteArray? = serializer.entityToBytes(entity, plugin)

    override fun spawn(chunk: Chunk, stored: List<StoredEntity>) {
        val world = chunk.world
        val level = (world as CraftWorld).handle
        val spawned = mutableListOf<net.minecraft.world.entity.Entity>()

        for (entry in stored) {
            val location = Location(world, entry.x, entry.y, entry.z, entry.yaw, entry.pitch)
            val root = PolarEntity(entry.x, entry.y, entry.z, entry.yaw, entry.pitch, entry.nbt)
                .toNMSEntity(serializer, world, location)
            if (root == null) {
                log.atWarning().log("Could not recreate entity %s at %s", entry.type, location)
                continue
            }

            // The serializer only recreates the root; its passengers are stored inside its NBT
            val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(entry.nbt)))
            for (passengerTag in tag.getListOrEmpty("Passengers").filterIsInstance<CompoundTag>()) {
                val passenger = EntityType.loadEntityRecursive(
                    passengerTag, level, EntitySpawnRequest(EntitySpawnReason.LOAD, false), EntityProcessor.NOP
                )
                if (passenger == null) {
                    log.atWarning().log("Could not recreate a passenger of %s at %s", entry.type, location)
                    continue
                }
                passenger.startRiding(root, true, false)
            }

            spawned += root
            root.indirectPassengers.forEach { spawned += it }
        }

        // Like Polar's own loader: adds the entities to the chunk without spawn events or spawn checks
        level.`moonrise$getEntityLookup`().addEntityChunkEntities(spawned, ChunkPos(chunk.x, chunk.z))
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

    /**
     * Writes [BuiltInData.ENTITIES] and [BuiltInData.CHUNK_PDC] from the live chunk; empty results remove the section.
     * Runs on the chunk's owning thread.
     */
    fun capture(
        chunk: Chunk,
        unclaimed: List<Entity>,
        sections: MapDataSections,
        claimed: Collection<Entity> = emptyList()
    ) {
        val claimedIds = claimed.mapTo(HashSet()) { it.uniqueId }
        val stored = unclaimed
            // A passenger is stored inside the NBT of its root vehicle
            .filter { it.isPersistent && !it.isInsideVehicle }
            .mapNotNull { entity ->
                val nbt = codec.serialize(entity)
                if (nbt == null) {
                    log.atFine().log("Skipping entity %s of chunk %s, %s: it cannot be serialized", entity, chunk.x, chunk.z)
                    return@mapNotNull null
                }

                val location = entity.location
                // A vehicle's NBT carries its passengers; one a handler claimed must not be restored a second time
                val stored = if (claimedIds.isEmpty()) nbt else stripClaimedPassengers(nbt, claimedIds)
                StoredEntity(entityType(nbt), location.x, location.y, location.z, location.yaw, location.pitch, stored)
            }
        sections[BuiltInData.ENTITIES] = stored.ifEmpty { null }

        val pdc = chunk.persistentDataContainer
        sections[BuiltInData.CHUNK_PDC] = if (pdc.isEmpty) null else pdc.serializeToBytes()
    }

    /** Spawns the stored entities and reads the persistent data container back. Runs on the tick thread. */
    fun restore(chunk: Chunk, sections: MapDataSections) {
        sections[BuiltInData.CHUNK_PDC]?.let { chunk.persistentDataContainer.readFromBytes(it) }

        sections[BuiltInData.ENTITIES]?.takeIf { it.isNotEmpty() }?.let { codec.spawn(chunk, it) }
    }

    private fun stripClaimedPassengers(nbt: ByteArray, claimed: Set<UUID>): ByteArray {
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(nbt)))
        if (!stripPassengers(tag, claimed)) return nbt

        val out = ByteArrayOutputStream()
        NbtIo.write(tag, DataOutputStream(out))
        return out.toByteArray()
    }

    /** Removes claimed passengers from [tag], recursively. Returns whether anything was removed. */
    private fun stripPassengers(tag: CompoundTag, claimed: Set<UUID>): Boolean {
        val passengers = tag.getListOrEmpty("Passengers").filterIsInstance<CompoundTag>()
        if (passengers.isEmpty()) return false

        var changed = false
        val kept = ListTag()
        for (passenger in passengers) {
            val id = passenger.getIntArray("UUID").map { UUIDUtil.uuidFromIntArray(it) }.orElse(null)
            if (id != null && id in claimed) {
                changed = true
            } else {
                if (stripPassengers(passenger, claimed)) changed = true
                kept.add(passenger)
            }
        }
        if (changed) {
            if (kept.isEmpty()) tag.remove("Passengers") else tag.put("Passengers", kept)
        }
        return changed
    }

    private fun entityType(nbt: ByteArray): String =
        NbtIo.read(DataInputStream(ByteArrayInputStream(nbt))).getStringOr("id", "")
}
