package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.data.FurnitureRecord
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData
import net.momirealms.craftengine.core.plugin.config.Config
import net.momirealms.craftengine.core.util.Key
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The CraftEngine calls the handler needs. Split from the handler because CraftEngine's classes cannot be loaded
 * without a running CraftEngine, so tests replace this instead of mocking CraftEngine itself.
 */
internal interface CraftEngineFurnitureApi {
    /** The furniture id of [entity], or null when it is not a CraftEngine furniture base entity. */
    fun furnitureId(entity: Entity): String?

    /** Whether [entity] is a physical collider entity of a furniture piece (or a copy of one). */
    fun isCollider(entity: Entity): Boolean

    /** Whether [entity] is a seat entity spawned for a furniture piece. */
    fun isSeat(entity: Entity): Boolean

    /**
     * CraftEngine's own per-instance data of the furniture (variant, source item, behavior data), serialized, or null.
     * Unsaved behavior state is flushed into the entity first.
     */
    fun stateBytes(entity: Entity): ByteArray?

    /** The rest of the base entity's persistent data (data of other plugins), serialized, or null if empty. */
    fun extraBytes(entity: Entity): ByteArray?

    /**
     * Places the furniture [id] at [location] (position and rotation are taken from it exactly) with the data from
     * [stateBytes], without a space check or sound. Returns the base entity, or null when CraftEngine does not know
     * the id.
     */
    fun place(id: String, location: Location, state: ByteArray?): Entity?

    /**
     * Applies CraftEngine's invalid furniture mappings to [id]: the mapped id, [id] itself when unmapped, or an empty
     * string when CraftEngine removes such furniture.
     */
    fun resolveId(id: String): String

    /** Whether CraftEngine's `chunk-system.sync-custom-blocks-on-chunk-load` is on. */
    fun syncCustomBlocks(): Boolean

    /** Merges [bytes] from [extraBytes] onto the persistent data of [entity] without clearing it. */
    fun applyExtra(entity: Entity, bytes: ByteArray)

    /**
     * Whether [chunk] settled as an entity ticking chunk. While a chunk is still changing its load level, its entities
     * can leave and enter the world again within a tick, which CraftEngine does not always follow: it unregisters the
     * furniture on the first and skips the second in a world it does not store itself.
     */
    fun chunkReady(chunk: Chunk): Boolean

    /**
     * Whether CraftEngine tracks [entity] as a live furniture. When it does not, asks CraftEngine to register the entity as
     * it does for a single entity that enters the world, and returns whether that worked.
     *
     * CraftEngine registers furniture when its entity is added to the world and unregisters it when it is removed. A
     * chunk that is still being set up can remove and add the entity again within the same tick, and CraftEngine skips
     * the second add until it finished restoring the chunk's entities, which leaves a placed piece untracked.
     */
    fun ensureTracked(entity: Entity): Boolean
}

private object RealCraftEngineFurnitureApi : CraftEngineFurnitureApi {
    override fun furnitureId(entity: Entity): String? {
        if (!CraftEngineFurniture.isFurniture(entity)) return null
        return entity.persistentDataContainer.get(BukkitFurnitureManager.FURNITURE_KEY, PersistentDataType.STRING)
    }

    override fun isCollider(entity: Entity): Boolean =
        CraftEngineFurniture.isCollisionEntity(entity) ||
            entity.persistentDataContainer.has(BukkitFurnitureManager.FURNITURE_COLLISION, PersistentDataType.BYTE)

    override fun isSeat(entity: Entity): Boolean = CraftEngineFurniture.isSeat(entity)

    override fun stateBytes(entity: Entity): ByteArray? {
        // CraftEngine only writes behavior data into the entity on save or unload
        CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity)?.saveIfDirty()
        return entity.persistentDataContainer.get(
            BukkitFurnitureManager.FURNITURE_EXTRA_DATA_KEY, PersistentDataType.BYTE_ARRAY
        )
    }

    override fun extraBytes(entity: Entity): ByteArray? {
        val pdc = entity.persistentDataContainer
        val copy = pdc.adapterContext.newPersistentDataContainer()
        pdc.copyTo(copy, true)
        // CraftEngine's own keys are recreated by placing the furniture
        copy.remove(BukkitFurnitureManager.FURNITURE_KEY)
        copy.remove(BukkitFurnitureManager.FURNITURE_EXTRA_DATA_KEY)
        return if (copy.isEmpty) null else copy.serializeToBytes()
    }

    override fun resolveId(id: String): String =
        if (Config.handleInvalidFurniture()) Config.furnitureMappings()[id] ?: id else id

    override fun syncCustomBlocks(): Boolean = Config.syncCustomBlocks()

    override fun place(id: String, location: Location, state: ByteArray?): Entity? {
        val definition = CraftEngineFurniture.byId(Key.of(id)) ?: return null
        val data = if (state != null) {
            FurniturePersistentData.fromBytes(state)
        } else {
            FurniturePersistentData.ofVariant(definition.anyVariantName())
        }
        // CraftEngine spawns the entity before running third-party onPlace code, so a throw or a null result can
        // still leave a placed piece behind
        return placeRecovering({ CraftEngineFurniture.place(location, definition, data, false)?.bukkitEntity() }) {
            location.world.getNearbyEntities(location, 0.01, 0.01, 0.01).firstOrNull { furnitureId(it) == id }
        }
    }

    override fun applyExtra(entity: Entity, bytes: ByteArray) {
        entity.persistentDataContainer.readFromBytes(bytes, false)
    }

    override fun chunkReady(chunk: Chunk): Boolean = chunk.loadLevel == Chunk.LoadLevel.ENTITY_TICKING

    override fun ensureTracked(entity: Entity): Boolean {
        if (CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity) != null) return true
        (entity as? ItemDisplay)?.let { BukkitFurnitureManager.instance().handleFurnitureEntityAdded(it) }
        return CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity) != null
    }
}

/**
 * Runs [place]; when it throws or returns null, [find] looks for a piece it spawned anyway. Returns the placed entity,
 * null when nothing was placed, or rethrows when it threw and nothing was spawned.
 */
internal fun placeRecovering(place: () -> Entity?, find: () -> Entity?): Entity? {
    val placed = try {
        place()
    } catch (e: Exception) {
        return find() ?: throw e
    }
    return placed ?: find()
}

/** Records restored into a chunk that are not placed yet. */
private class PendingFurniture(val records: MutableList<FurnitureRecord>)

/**
 * Stores CraftEngine furniture as neutral [FurnitureRecord]s and places it again through CraftEngine.
 *
 * Every CraftEngine class is referenced from this file only; [BuiltInHandlers] creates the handler when CraftEngine is
 * enabled, so the plugin loads without CraftEngine.
 *
 * CraftEngine 26.9.1 keeps one `ItemDisplay` base entity per piece whose persistent data holds the furniture id and a
 * serialized data compound (variant, source item, behavior data). Elements and hitboxes are packet based. The
 * physical colliders (interaction or boat entities) are recreated on placement and the seats are non-persistent
 * entities spawned while a player sits, so [capture] claims every collider and seat it is handed besides the base
 * entity, which keeps them out of the entities section.
 *
 * [FurnitureRecord.properties] keys:
 * - `data`: Base64 of CraftEngine's own per-instance data, which includes the variant (the successor of the placement
 *   anchor type), the source item and the behavior data; passed to CraftEngine on restore.
 * - `pdc`: Base64 of the remaining persistent data of the base entity (data of other plugins); merged back after
 *   placement.
 *
 * The record keeps the original id; CraftEngine's invalid furniture mappings are applied when placing, and a mapping
 * to an empty id drops the record, as CraftEngine would remove the piece.
 *
 * Position and rotation are stored as the exact base entity location: CraftEngine uses the location it is given
 * as is, it does not derive it from a block.
 *
 * Restore is deferred: placing spawns entities, so [restore] queues the placement on the chunk's region for the next
 * tick. Until a record is placed it stays pending, and [capture] returns pending records together with the captured
 * ones. Records CraftEngine cannot place (unknown id, an error), or that cannot be tried because the chunk is gone,
 * stay pending and every capture returns them again. A chunk's pending records are released by [chunkUnloaded] (the
 * handler is created per world) or when the chunk is restored again. A task only places the records of the entry that
 * is still current.
 */
class CraftEngineFurnitureHandler internal constructor(
    private val run: (Chunk, Runnable) -> Unit,
    private val craftEngineEnabled: () -> Boolean,
    private val craftEngine: CraftEngineFurnitureApi,
    private val chunkLive: (Chunk) -> Boolean
) : ChunkDataHandler<List<FurnitureRecord>> {
    constructor(plugin: Plugin) : this(
        { chunk, task -> plugin.server.regionScheduler.run(plugin, chunk.world, chunk.x, chunk.z) { task.run() } },
        { Bukkit.getPluginManager().isPluginEnabled("CraftEngine") },
        RealCraftEngineFurnitureApi,
        // The entities of a chunk load after its blocks, and furniture placed before that is not registered by the
        // furniture plugin when its entities load
        { chunk ->
            Bukkit.getWorld(chunk.world.uid) === chunk.world && chunk.world.isChunkLoaded(chunk.x, chunk.z) &&
                chunk.isEntitiesLoaded
        }
    )

    private val log = logger()
    private val pending = ConcurrentHashMap<Long, PendingFurniture>()

    init {
        // Without it CraftEngine's sections and renderers of Polar chunks stay empty
        val sync = try {
            craftEngine.syncCustomBlocks()
        } catch (e: Throwable) {
            true
        }
        if (!sync) {
            log.atWarning().log(
                "CraftEngine's chunk-system.sync-custom-blocks-on-chunk-load is false: custom block entities and " +
                    "renderers in Polar worlds will not be rebuilt on chunk load"
            )
        }
    }

    override val type: DataType<List<FurnitureRecord>> = BuiltInData.CRAFT_ENGINE_FURNITURE

    override fun isAvailable(): Boolean = craftEngineEnabled()

    override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): List<FurnitureRecord>? {
        val records = mutableListOf<FurnitureRecord>()
        for (entity in entities) {
            val id = craftEngine.furnitureId(entity)
            if (id == null) {
                // Colliders and seats are recreated with their furniture
                if (craftEngine.isCollider(entity) || craftEngine.isSeat(entity)) claim(entity)
                continue
            }

            val properties = mutableMapOf<String, String>()
            craftEngine.stateBytes(entity)?.let { properties["data"] = Base64.getEncoder().encodeToString(it) }
            craftEngine.extraBytes(entity)?.let { properties["pdc"] = Base64.getEncoder().encodeToString(it) }

            val location = entity.location
            records += FurnitureRecord(id, location.x, location.y, location.z, location.yaw, location.pitch, properties)
            claim(entity)
        }

        val unplaced = pending[key(chunk)]?.let { entry -> synchronized(entry.records) { entry.records.toList() } }.orEmpty()
        return (unplaced + records).ifEmpty { null }
    }

    override fun chunkUnloaded(x: Int, z: Int) {
        pending.remove(key(x, z))
    }

    override fun restore(chunk: Chunk, value: List<FurnitureRecord>) {
        if (value.isEmpty()) return
        val entry = PendingFurniture(value.toMutableList())
        pending[key(chunk)] = entry
        run(chunk, Runnable { placePending(chunk, entry, 1) })
    }

    private fun placePending(chunk: Chunk, entry: PendingFurniture, attempt: Int) {
        // The chunk unloaded, or unloaded and reloaded with a newer entry, since this task was queued
        if (pending[key(chunk)] !== entry) return

        if (!chunkLive(chunk) || !craftEngine.chunkReady(chunk)) {
            // A chunk is handed to the load hook before it is fully loaded, so it can take a few ticks to become live
            if (attempt < MAX_ATTEMPTS) {
                run(chunk, Runnable { placePending(chunk, entry, attempt + 1) })
                return
            }
            log.atWarning().log(
                "Chunk %s, %s of world %s is not loaded any more, keeping its craftengine furniture pending",
                chunk.x, chunk.z, chunk.world.name
            )
            return
        }

        for (record in synchronized(entry.records) { entry.records.toList() }) {
            val resolved = craftEngine.resolveId(record.id)
            if (resolved.isEmpty()) {
                // CraftEngine's own mapping removes this furniture
                log.atWarning().log("Dropping craftengine furniture %s: CraftEngine maps it to removal", record.id)
                synchronized(entry.records) { entry.records.remove(record) }
                continue
            }
            val entity = try {
                place(chunk, record, resolved)
            } catch (e: Exception) {
                log.atSevere().withCause(e).log(
                    "Could not place craftengine furniture %s in chunk %s, %s: %s", record.id, chunk.x, chunk.z, e.message
                )
                null
            } ?: continue

            // Live from here on: the record must not be emitted again, even if reapplying its state fails
            synchronized(entry.records) { entry.records.remove(record) }
            run(chunk, Runnable { verifyTracked(chunk, entity, record.id, 1) })
            try {
                record.properties["pdc"]?.let { craftEngine.applyExtra(entity, Base64.getDecoder().decode(it)) }
            } catch (e: Exception) {
                log.atSevere().withCause(e)
                    .log("Placed craftengine furniture %s but could not restore its extra data: %s", record.id, e.message)
            }
        }
    }

    /** Makes sure CraftEngine still tracks the placed [entity] a tick later, see [CraftEngineFurnitureApi.ensureTracked]. */
    private fun verifyTracked(chunk: Chunk, entity: Entity, id: String, attempt: Int) {
        if (!entity.isValid) return
        val tracked = try {
            craftEngine.ensureTracked(entity)
        } catch (e: Exception) {
            log.atWarning().withCause(e).log("Could not check whether CraftEngine tracks the furniture %s: %s", id, e.message)
            return
        }
        if (tracked) return

        if (attempt < MAX_ATTEMPTS) {
            run(chunk, Runnable { verifyTracked(chunk, entity, id, attempt + 1) })
        } else {
            log.atWarning().log("CraftEngine does not track the placed furniture %s at chunk %s, %s", id, chunk.x, chunk.z)
        }
    }

    /** Places the furniture of [record] and returns its base entity, or null when CraftEngine does not know it. */
    private fun place(chunk: Chunk, record: FurnitureRecord, id: String): Entity? {
        val location = Location(chunk.world, record.x, record.y, record.z, record.yaw, record.pitch)
        val state = record.properties["data"]?.let { Base64.getDecoder().decode(it) }
        val entity = craftEngine.place(id, location, state)
        if (entity == null) {
            log.atWarning().log("CraftEngine does not know furniture %s at %s, keeping it pending", record.id, location)
        }
        return entity
    }

    private fun key(chunk: Chunk) = key(chunk.x, chunk.z)

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    private companion object {
        /** Ticks to wait for a chunk to become live before leaving its records pending. */
        const val MAX_ATTEMPTS = 200
    }
}
