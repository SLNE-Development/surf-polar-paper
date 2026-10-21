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
     * Runs on the chunk's owning region thread (the main thread on Paper) while the world is prepared for saving,
     * before the data is written. Entities passed to [claim] are not stored by the entities handler, and neither
     * are their passengers. Returning null stores no section.
     */
    fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): T?

    /** Runs on the tick thread while the chunk is loaded, right after it became live. */
    fun restore(chunk: Chunk, value: T)
}
