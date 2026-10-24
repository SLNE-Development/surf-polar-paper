package dev.slne.surf.polarpaper.api.data

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity

/**
 * Captures plugin-specific chunk state into a data section on save and restores it on load.
 */
interface ChunkDataHandler<T : Any> {
    val type: DataType<T>

    /** Whether the backing plugin is present. Unavailable handlers are skipped. */
    fun isAvailable(): Boolean = true

    /**
     * Runs on the chunk's owning region thread (the main thread on Paper) while the world is prepared for saving,
     * before the data is written. Entities passed to [claim] are not stored by the entities handler, and neither
     * are their passengers. Returning null stores no section.
     */
    fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): T?

    /** Runs on the tick thread while the chunk is loaded, right after it became live. */
    fun restore(chunk: Chunk, value: T)

    /**
     * Called when the chunk at [x], [z] of [world] unloaded. Polar leaves unloaded chunks out of later saves, so
     * nothing captured from here on would be written; handlers that keep per-chunk state, such as records that are
     * not restored yet, release it here. Runs on the thread that fires the ChunkUnloadEvent: the main thread on Paper, the chunk's
     * region thread on Folia. An exception is logged and does not stop the other handlers. Does nothing by default.
     */
    fun chunkUnloaded(world: World, x: Int, z: Int) = Unit
}
