package dev.slne.surf.polarpaper.api.data

import org.bukkit.Chunk
import org.bukkit.entity.Entity

/**
 * Captures plugin-specific chunk state into a data section on save and restores it on load.
 */
interface ChunkDataHandler<T : Any> {
    val type: DataType<T>

    /** Whether the backing plugin is present. Unavailable handlers are skipped. */
    fun isAvailable(): Boolean = true

    /**
     * Runs on the chunk's region thread while saving. Entities passed to [claim] are not stored
     * by the entities handler. Returning null stores no section.
     */
    fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): T?

    /** Runs on the chunk's region thread once the chunk is live. */
    fun restore(chunk: Chunk, value: T)
}
