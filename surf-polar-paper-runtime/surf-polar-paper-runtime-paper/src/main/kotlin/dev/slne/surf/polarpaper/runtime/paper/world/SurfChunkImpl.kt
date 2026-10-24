package dev.slne.surf.polarpaper.runtime.paper.world

import dev.slne.surf.polarpaper.api.data.DataSections
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import org.bukkit.Chunk

/** A live chunk. [data] is the very map that is written back when the chunk is saved. */
class SurfChunkImpl(
    override val world: SurfWorldImpl,
    override val bukkitChunk: Chunk,
    override val data: MapDataSections
) : SurfChunk {
    override val x: Int get() = bukkitChunk.x
    override val z: Int get() = bukkitChunk.z

    override fun toString() = "SurfChunk(world=$world, x=$x, z=$z)"
}
