package dev.slne.surf.polarpaper.runtime.paper.access

import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import java.util.concurrent.ConcurrentHashMap

/**
 * The data sections of the live chunks of one world, keyed by chunk. Entries are added when a chunk becomes
 * live and must be removed when it unloads, otherwise the sections of unloaded chunks stay in memory.
 */
class LiveChunkRegistry {
    private val chunks = ConcurrentHashMap<Long, MapDataSections>()

    fun sections(x: Int, z: Int): MapDataSections? = chunks[key(x, z)]

    /** The sections of the chunk, created empty for a chunk that became live without stored data (e.g. generated). */
    fun getOrCreate(x: Int, z: Int): MapDataSections = chunks.computeIfAbsent(key(x, z)) { MapDataSections() }

    /** Registers [sections] for a chunk that has none yet. */
    fun register(x: Int, z: Int, sections: MapDataSections) {
        chunks.putIfAbsent(key(x, z), sections)
    }

    fun put(x: Int, z: Int, sections: MapDataSections) {
        chunks[key(x, z)] = sections
    }

    fun remove(x: Int, z: Int) {
        chunks.remove(key(x, z))
    }

    /** Snapshot of all live chunks. */
    fun all(): Map<Long, MapDataSections> = HashMap(chunks)

    companion object {
        /** Packs the coordinates the same way as `Chunk.getChunkKey`. */
        fun key(x: Int, z: Int): Long = (x.toLong() and 0xFFFFFFFFL) or ((z.toLong() and 0xFFFFFFFFL) shl 32)

        /** The chunk x of a key made by [key]. */
        fun chunkX(key: Long): Int = key.toInt()

        /** The chunk z of a key made by [key]. */
        fun chunkZ(key: Long): Int = (key ushr 32).toInt()
    }
}
