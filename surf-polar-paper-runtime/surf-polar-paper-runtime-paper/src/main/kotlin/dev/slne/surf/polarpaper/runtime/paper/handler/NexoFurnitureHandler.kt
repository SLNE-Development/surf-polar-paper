package dev.slne.surf.polarpaper.runtime.paper.handler

import com.nexomc.nexo.api.NexoFurniture
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

    /** Places the furniture [id] without the space check, or returns null when Nexo does not know or refuses it. */
    fun place(id: String, location: Location, yaw: Float): ItemDisplay?

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

    // The space check is off: the furniture's own barrier blocks are part of the chunk and would block it
    override fun place(id: String, location: Location, yaw: Float): ItemDisplay? =
        NexoFurniture.furnitureMechanic(id)?.place(location, yaw, BlockFace.UP, false)

    override fun dye(display: ItemDisplay, color: Color) = NexoFurniture.furnitureDye(display, color)

    override fun toggleLight(display: ItemDisplay, on: Boolean) {
        NexoFurniture.toggleLight(display, on)
    }
}

/**
 * Stores Nexo furniture as neutral [FurnitureRecord]s and places it again through Nexo.
 *
 * Every Nexo class is referenced from this file only; [BuiltInHandlers] creates the handler when Nexo is enabled, so
 * the plugin loads without Nexo.
 *
 * Nexo keeps one [ItemDisplay] base entity per piece. Its hitboxes are packet based and its barrier blocks and
 * interaction entities are recreated by Nexo when it places the furniture, so only the base entity is stored. Seats
 * and other entities riding on it are claimed with it by the caller.
 *
 * [FurnitureRecord.properties] keys:
 * - `dye`: the furniture dye as an ARGB int, only when the furniture is dyed.
 * - `light`: `true` or `false`, the state of furniture whose light is toggleable.
 *
 * The rotation is stored as the base entity yaw. The pitch is stored for completeness, but Nexo derives it from the
 * placement face and the face is not recorded, so furniture is restored as if placed on top of a block.
 */
class NexoFurnitureHandler internal constructor(
    private val run: (Chunk, Runnable) -> Unit,
    private val nexoEnabled: () -> Boolean,
    private val nexo: NexoFurnitureApi
) : ChunkDataHandler<List<FurnitureRecord>> {
    constructor(plugin: Plugin) : this(
        { chunk, task -> plugin.server.regionScheduler.run(plugin, chunk.world, chunk.x, chunk.z) { task.run() } },
        { Bukkit.getPluginManager().isPluginEnabled("Nexo") },
        RealNexoFurnitureApi
    )

    private val log = logger()

    override val type: DataType<List<FurnitureRecord>> = BuiltInData.NEXO_FURNITURE

    override fun isAvailable(): Boolean = nexoEnabled()

    override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): List<FurnitureRecord>? {
        val records = mutableListOf<FurnitureRecord>()
        for (entity in entities) {
            val id = nexo.furnitureId(entity) ?: continue
            val display = entity as ItemDisplay

            val properties = mutableMapOf<String, String>()
            nexo.dye(display)?.let { properties["dye"] = it.asARGB().toString() }
            if (nexo.hasToggleableLight(display)) properties["light"] = nexo.lightState(display).toString()

            val location = display.location
            records += FurnitureRecord(id, location.x, location.y, location.z, location.yaw, location.pitch, properties)
            claim(display)
        }
        return records.ifEmpty { null }
    }

    /**
     * Places the furniture one tick later on the chunk's region instead of inside the load pass, because Nexo spawns
     * entities and registers its hitboxes on placement, which needs a live chunk.
     */
    override fun restore(chunk: Chunk, value: List<FurnitureRecord>) {
        run(chunk, Runnable { value.forEach { place(chunk, it) } })
    }

    private fun place(chunk: Chunk, record: FurnitureRecord) {
        val location = Location(chunk.world, record.x, record.y, record.z)
        val display = nexo.place(record.id, location, record.yaw)
        if (display == null) {
            log.atWarning().log("Nexo does not know or could not place furniture %s at %s, skipping it", record.id, location)
            return
        }

        record.properties["dye"]?.toIntOrNull()?.let { nexo.dye(display, Color.fromARGB(it)) }
        record.properties["light"]?.toBooleanStrictOrNull()?.let { nexo.toggleLight(display, it) }
    }
}
