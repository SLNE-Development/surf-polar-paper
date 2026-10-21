package dev.slne.surf.polarpaper.runtime.paper.handler

import com.nexomc.nexo.api.NexoFurniture
import com.nexomc.nexo.mechanics.furniture.placements.FurniturePlacement
import com.nexomc.nexo.mechanics.furniture.placements.PlacementType
import com.nexomc.nexo.mechanics.furniture.seats.FurnitureSeat
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.data.FurnitureRecord
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.plugin.Plugin
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** An integer block position. */
internal data class BlockPos(val x: Int, val y: Int, val z: Int)

/**
 * Undoes Nexo's `FurnitureMechanic.correctedSpawnLocation` (1.29.1) for the base entity location [x], [y], [z], which
 * Nexo derived from the placement block:
 * - the block center is used, except for fixed-transform furniture placed on top of a block (or on a wall with a
 *   solid block below it), which sits at the block's bottom;
 * - roof furniture hung from the ceiling ([roof] and [face] DOWN) is raised by 0.49;
 * - fixed-transform wall furniture without solid ground below is moved against its wall by `0.49 * scaleY * 2`.
 * [solidBelow] tells whether the block below the given block is solid.
 */
internal fun invertSpawnCorrection(
    x: Double, y: Double, z: Double, face: BlockFace,
    wall: Boolean, roof: Boolean, fixed: Boolean, scaleY: Float,
    solidBelow: (Int, Int, Int) -> Boolean
): BlockPos {
    val epsilon = 1e-6
    val by = floor(y - (if (roof && face == BlockFace.DOWN) 0.49 else 0.0) + epsilon).toInt()
    val plain = BlockPos(floor(x + epsilon).toInt(), by, floor(z + epsilon).toInt())

    if (!fixed || !wall || face.modY != 0) return plain
    // The wall shift only applies without solid ground below; check that against the unshifted candidate
    val shift = 0.49 * scaleY * 2
    val candidate = BlockPos(
        floor(x + face.modX * shift + epsilon).toInt(), by, floor(z + face.modZ * shift + epsilon).toInt()
    )
    return if (solidBelow(candidate.x, candidate.y - 1, candidate.z)) plain else candidate
}

/**
 * The Nexo calls the handler needs. Split from the handler because Nexo's mechanic classes cannot be loaded without a
 * running Nexo, so tests replace this instead of mocking Nexo itself.
 */
internal interface NexoFurnitureApi {
    /** The furniture id of [entity], or null when it is not a Nexo furniture base entity. */
    fun furnitureId(entity: Entity): String?

    /** Whether the furniture of [display] has a light that can be toggled. */
    fun hasToggleableLight(display: ItemDisplay): Boolean

    fun lightState(display: ItemDisplay): Boolean

    fun dye(display: ItemDisplay): Color?

    /** The standalone seat entities of the furniture. They are not passengers of the base entity. */
    fun seats(display: ItemDisplay): List<Entity>

    /** Whether [entity] is a Nexo furniture seat. */
    fun isSeat(entity: Entity): Boolean

    /** The block face the furniture was placed against, derived from its placement type and rotation. */
    fun placementFace(display: ItemDisplay): BlockFace

    /**
     * The block the furniture was placed in. Nexo derives the base entity location from this block on placement and
     * the derivation is not idempotent for every face, so the block is stored instead of the entity location.
     */
    fun placementBlock(display: ItemDisplay): BlockPos

    /** The persistent data of the base entity (storage, evolution, ...) without the seat references, or null if empty. */
    fun stateBytes(display: ItemDisplay): ByteArray?

    /** Merges [bytes] from [stateBytes] onto the persistent data of [display] without clearing it. */
    fun applyState(display: ItemDisplay, bytes: ByteArray)

    /** Places the furniture [id] without the space check, or returns null when Nexo does not know or refuses it. */
    fun place(id: String, location: Location, yaw: Float, face: BlockFace): ItemDisplay?

    fun dye(display: ItemDisplay, color: Color)

    fun toggleLight(display: ItemDisplay, on: Boolean)
}

private object RealNexoFurnitureApi : NexoFurnitureApi {
    override fun furnitureId(entity: Entity): String? {
        if (entity !is ItemDisplay || !NexoFurniture.isFurniture(entity as Entity)) return null
        return NexoFurniture.furnitureMechanic(entity as Entity)?.itemID
    }

    override fun hasToggleableLight(display: ItemDisplay): Boolean =
        NexoFurniture.furnitureMechanic(display as Entity)?.light?.toggleable == true

    override fun lightState(display: ItemDisplay): Boolean = NexoFurniture.lightState(display)

    override fun dye(display: ItemDisplay): Color? = NexoFurniture.furnitureDye(display)

    override fun seats(display: ItemDisplay): List<Entity> {
        val mechanic = NexoFurniture.furnitureMechanic(display as Entity) ?: return emptyList()
        return FurnitureSeat.getSeats(display, mechanic)
    }

    override fun isSeat(entity: Entity): Boolean = FurnitureSeat.isSeat(entity)

    override fun placementFace(display: ItemDisplay): BlockFace = when (FurniturePlacement.placementType(display)) {
        PlacementType.ROOF -> BlockFace.DOWN
        // Wall furniture faces away from its wall, and yaw 0 faces south
        PlacementType.WALL -> when (Math.floorMod(Math.round(display.location.yaw / 90f), 4)) {
            0 -> BlockFace.SOUTH
            1 -> BlockFace.WEST
            2 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
        else -> BlockFace.UP
    }

    override fun placementBlock(display: ItemDisplay): BlockPos {
        val location = display.location
        val mechanic = NexoFurniture.furnitureMechanic(display as Entity)
            ?: return BlockPos(location.blockX, location.blockY, location.blockZ)
        val world = location.world
        return invertSpawnCorrection(
            location.x, location.y, location.z, placementFace(display),
            mechanic.limitedPlacing?.isWall == true, mechanic.limitedPlacing?.isRoof == true,
            mechanic.properties.isFixedTransform, mechanic.properties.scale.y()
        ) { bx, by, bz -> world.getBlockAt(bx, by, bz).isSolid }
    }

    override fun stateBytes(display: ItemDisplay): ByteArray? {
        val pdc = display.persistentDataContainer
        val copy = pdc.adapterContext.newPersistentDataContainer()
        pdc.copyTo(copy, true)
        // Seat entities are recreated by Nexo, their uuids would point at nothing
        copy.remove(FurnitureSeat.SEAT_KEY)
        return if (copy.isEmpty) null else copy.serializeToBytes()
    }

    override fun applyState(display: ItemDisplay, bytes: ByteArray) {
        display.persistentDataContainer.readFromBytes(bytes, false)
    }

    // The space check is off: the furniture's own barrier blocks are part of the chunk and would block it
    override fun place(id: String, location: Location, yaw: Float, face: BlockFace): ItemDisplay? =
        NexoFurniture.furnitureMechanic(id)?.place(location, yaw, face, false)

    override fun dye(display: ItemDisplay, color: Color) = NexoFurniture.furnitureDye(display, color)

    override fun toggleLight(display: ItemDisplay, on: Boolean) {
        NexoFurniture.toggleLight(display, on)
    }
}

/** Records restored into a chunk that are not placed yet. */
private class Pending(val records: MutableList<FurnitureRecord>)

/**
 * Stores Nexo furniture as neutral [FurnitureRecord]s and places it again through Nexo.
 *
 * Every Nexo class is referenced from this file only; [BuiltInHandlers] creates the handler when Nexo is enabled, so
 * the plugin loads without Nexo.
 *
 * Nexo keeps one [ItemDisplay] base entity per piece. Its hitboxes are packet based and its barrier blocks and
 * interaction entities are recreated by Nexo when it places the furniture, so only the base entity is stored. The
 * seats are standalone interaction entities referenced from the base entity's persistent data, not passengers, so
 * [capture] claims them explicitly (and any other entity Nexo reports as a seat).
 *
 * [FurnitureRecord.properties] keys:
 * - `face`: the [BlockFace] name the furniture was placed against, passed to Nexo on restore.
 * - `block`: `x,y,z` of the block the furniture was placed in; restore places from the center of this block, because
 *   Nexo derives the base entity location from it and that is not idempotent for every face.
 * - `pdc`: Base64 of the base entity's persistent data container (storage contents, evolution, ...) without the seat
 *   references; merged back onto the placed entity.
 * - `dye`: the furniture dye as an ARGB int, only when the furniture is dyed.
 * - `light`: `true` or `false`, the state of furniture whose light is toggleable.
 *
 * The rotation is stored as the base entity yaw.
 *
 * Restore is deferred: Nexo spawns entities and registers hitboxes on placement, so [restore] queues the placement on
 * the chunk's region for the next tick. Until a record is placed it stays pending, and [capture] returns pending
 * records together with the captured ones, so a save in between does not erase the furniture. Records Nexo cannot
 * place (unknown id, refusal, an error), or that cannot be tried because the chunk is gone, stay pending, and every
 * capture returns them again, however often the chunk is saved. A chunk's pending records are released by
 * [chunkUnloaded] (the handler is created per world, so a world unload releases everything with the handler) or when
 * the chunk is restored again. A task only places the records of the entry that is still current, so a chunk that
 * unloaded and reloaded meanwhile is not placed twice.
 */
class NexoFurnitureHandler internal constructor(
    private val run: (Chunk, Runnable) -> Unit,
    private val nexoEnabled: () -> Boolean,
    private val nexo: NexoFurnitureApi,
    private val chunkLive: (Chunk) -> Boolean
) : ChunkDataHandler<List<FurnitureRecord>> {
    constructor(plugin: Plugin) : this(
        { chunk, task -> plugin.server.regionScheduler.run(plugin, chunk.world, chunk.x, chunk.z) { task.run() } },
        { Bukkit.getPluginManager().isPluginEnabled("Nexo") },
        RealNexoFurnitureApi,
        { chunk -> Bukkit.getWorld(chunk.world.uid) === chunk.world && chunk.world.isChunkLoaded(chunk.x, chunk.z) }
    )

    private val log = logger()
    private val pending = ConcurrentHashMap<Long, Pending>()

    override val type: DataType<List<FurnitureRecord>> = BuiltInData.NEXO_FURNITURE

    override fun isAvailable(): Boolean = nexoEnabled()

    override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): List<FurnitureRecord>? {
        val records = mutableListOf<FurnitureRecord>()
        for (entity in entities) {
            // Backstop for seats whose base entity is gone or not in this list
            if (nexo.isSeat(entity)) claim(entity)

            val id = nexo.furnitureId(entity) ?: continue
            val display = entity as ItemDisplay

            val properties = mutableMapOf<String, String>()
            properties["face"] = nexo.placementFace(display).name
            nexo.placementBlock(display).let { properties["block"] = "${it.x},${it.y},${it.z}" }
            nexo.stateBytes(display)?.let { properties["pdc"] = Base64.getEncoder().encodeToString(it) }
            nexo.dye(display)?.let { properties["dye"] = it.asARGB().toString() }
            if (nexo.hasToggleableLight(display)) properties["light"] = nexo.lightState(display).toString()

            val location = display.location
            records += FurnitureRecord(id, location.x, location.y, location.z, location.yaw, location.pitch, properties)
            claim(display)
            nexo.seats(display).forEach(claim)
        }

        val unplaced = pending[key(chunk)]?.let { entry -> synchronized(entry.records) { entry.records.toList() } }.orEmpty()
        return (unplaced + records).ifEmpty { null }
    }

    override fun chunkUnloaded(x: Int, z: Int) {
        pending.remove(key(x, z))
    }

    override fun restore(chunk: Chunk, value: List<FurnitureRecord>) {
        if (value.isEmpty()) return
        val entry = Pending(value.toMutableList())
        pending[key(chunk)] = entry
        run(chunk, Runnable { placePending(chunk, entry) })
    }

    private fun placePending(chunk: Chunk, entry: Pending) {
        // The chunk unloaded, or unloaded and reloaded with a newer entry, since this task was queued
        if (pending[key(chunk)] !== entry) return

        if (!chunkLive(chunk)) {
            log.atWarning().log(
                "Chunk %s, %s of world %s is not loaded any more, keeping its nexo furniture pending",
                chunk.x, chunk.z, chunk.world.name
            )
            return
        }

        for (record in synchronized(entry.records) { entry.records.toList() }) {
            val display = try {
                place(chunk, record)
            } catch (e: Exception) {
                log.atSevere().withCause(e)
                    .log("Could not place nexo furniture %s in chunk %s, %s: %s", record.id, chunk.x, chunk.z, e.message)
                null
            } ?: continue

            // Live from here on: the record must not be emitted again, even if reapplying its state fails
            synchronized(entry.records) { entry.records.remove(record) }
            try {
                applyState(display, record)
            } catch (e: Exception) {
                log.atSevere().withCause(e)
                    .log("Placed nexo furniture %s but could not restore its state: %s", record.id, e.message)
            }
        }
    }

    /** Places the furniture of [record] and returns it, or null when Nexo does not know or refused it. */
    private fun place(chunk: Chunk, record: FurnitureRecord): ItemDisplay? {
        val block = record.properties["block"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
        val location = if (block != null) {
            Location(chunk.world, block[0] + 0.5, block[1] + 0.5, block[2] + 0.5)
        } else {
            Location(chunk.world, record.x, record.y, record.z)
        }
        val face = record.properties["face"]?.let { name -> BlockFace.entries.firstOrNull { it.name == name } } ?: BlockFace.UP
        val display = nexo.place(record.id, location, record.yaw, face)
        if (display == null) {
            log.atWarning().log("Nexo does not know or refused furniture %s at %s, keeping it pending", record.id, location)
        }
        return display
    }

    private fun applyState(display: ItemDisplay, record: FurnitureRecord) {
        record.properties["pdc"]?.let { nexo.applyState(display, Base64.getDecoder().decode(it)) }
        record.properties["dye"]?.toIntOrNull()?.let { nexo.dye(display, Color.fromARGB(it)) }
        record.properties["light"]?.toBooleanStrictOrNull()?.let { nexo.toggleLight(display, it) }
    }

    private fun key(chunk: Chunk) = key(chunk.x, chunk.z)

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
