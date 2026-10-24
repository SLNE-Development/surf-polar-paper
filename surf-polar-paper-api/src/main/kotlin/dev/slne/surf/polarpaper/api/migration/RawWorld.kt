package dev.slne.surf.polarpaper.api.migration

import dev.slne.surf.polarpaper.api.data.DataSections

/** A world as stored, without any Bukkit objects. Used by raw migration stages. */
interface RawWorld {
    val minSection: Int
    val maxSection: Int
    val chunks: Collection<RawChunk>

    fun chunk(x: Int, z: Int): RawChunk?

    fun removeChunk(x: Int, z: Int)

    /** World-level data sections. */
    val data: DataSections
}

/** A chunk as stored. Block coordinates are local (x/z 0..15) with a world y. */
interface RawChunk {
    val x: Int
    val z: Int

    /** Returns the block state string, e.g. `minecraft:oak_log[axis=y]`. */
    fun getBlock(x: Int, y: Int, z: Int): String

    fun setBlock(x: Int, y: Int, z: Int, state: String)

    val blockEntities: MutableList<RawBlockEntity>
    val data: DataSections
}

/** A block entity with local x/z and world y. [snbt] is the entity data as SNBT, if any. */
data class RawBlockEntity(val x: Int, val y: Int, val z: Int, val id: String?, val snbt: String?)
