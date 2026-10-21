package dev.slne.surf.polarpaper.api.world

import dev.slne.surf.polarpaper.api.data.DataSections
import org.bukkit.Chunk

/** A live chunk of a [SurfWorld] together with its persisted data sections. */
interface SurfChunk {
    val world: SurfWorld
    val x: Int
    val z: Int
    val bukkitChunk: Chunk
    val data: DataSections
}
