package dev.slne.surf.polarpaper.runtime.paper.raw

import dev.slne.surf.polarpaper.api.data.DataSections
import dev.slne.surf.polarpaper.api.migration.RawChunk
import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import live.minehub.polarpaper.core.world.PolarWorld

/**
 * A [PolarWorld] opened for raw migration stages. Chunks are wrapped when first accessed; [flush] writes the
 * opened ones back into the Polar world.
 */
class PolarRawWorld(private val polarWorld: PolarWorld) : RawWorld {
    override val minSection: Int get() = polarWorld.minSection().toInt()
    override val maxSection: Int get() = polarWorld.maxSection().toInt()

    private val wrapped = HashMap<Long, PolarRawChunk>()

    // World user data that is not a container is Polar's own world data and has no sections to start from
    private val worldData = MapDataSections(
        if (SectionContainerCodec.isContainer(polarWorld.userData())) {
            SectionContainerCodec.decode(polarWorld.userData())
        } else {
            emptyMap()
        }
    )

    override val data: DataSections get() = worldData

    override val chunks: Collection<RawChunk>
        get() = polarWorld.chunks().toList().map { wrap(it.x(), it.z())!! }

    override fun chunk(x: Int, z: Int): RawChunk? = wrap(x, z)

    override fun removeChunk(x: Int, z: Int) {
        polarWorld.removeChunkAt(x, z)
        wrapped.remove(key(x, z))
    }

    /** Writes every opened chunk and the world data back into the Polar world. */
    fun flush() {
        for (chunk in wrapped.values) {
            polarWorld.updateChunkAt(chunk.x, chunk.z, chunk.toPolarChunk())
        }

        // Polar's own world user data (schematic offset, only used by Polar's info and schematic tooling, not for
        // loading) is replaced once the world has data sections of its own
        val sections = worldData.raw()
        if (sections.isNotEmpty() || SectionContainerCodec.isContainer(polarWorld.userData())) {
            polarWorld.userData(SectionContainerCodec.encode(sections))
        }
    }

    private fun wrap(x: Int, z: Int): PolarRawChunk? {
        wrapped[key(x, z)]?.let { return it }
        if (!polarWorld.hasChunkAt(x, z)) return null
        return PolarRawChunk(polarWorld.chunkAt(x, z)!!, minSection).also { wrapped[key(x, z)] = it }
    }

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
