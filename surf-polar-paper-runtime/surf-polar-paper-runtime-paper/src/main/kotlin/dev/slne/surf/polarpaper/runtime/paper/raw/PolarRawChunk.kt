package dev.slne.surf.polarpaper.runtime.paper.raw

import dev.slne.surf.polarpaper.api.data.DataSections
import dev.slne.surf.polarpaper.api.migration.RawBlockEntity
import dev.slne.surf.polarpaper.api.migration.RawChunk
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import live.minehub.polarpaper.core.util.CoordConversion
import live.minehub.polarpaper.core.util.PaletteUtil
import live.minehub.polarpaper.core.world.PolarChunk
import live.minehub.polarpaper.core.world.PolarSection
import live.minehub.polarpaper.core.world.PolarSection.LightContent
import net.minecraft.nbt.TagParser

/**
 * A [PolarChunk] opened for editing. Polar chunks are immutable records, so changes are kept here and written back
 * with [toPolarChunk].
 *
 * Block states are the strings of the Polar section palettes, so no Bukkit or NMS block objects are involved.
 */
class PolarRawChunk(
    private val source: PolarChunk,
    private val minSection: Int
) : RawChunk {
    override val x: Int get() = source.x()
    override val z: Int get() = source.z()

    private val sections: Array<PolarSection> = source.sections().copyOf()
    private var sectionsChanged = false

    override val blockEntities: MutableList<RawBlockEntity> = source.blockEntities().mapTo(ArrayList()) {
        RawBlockEntity(
            CoordConversion.chunkBlockIndexGetX(it.index()),
            CoordConversion.chunkBlockIndexGetY(it.index()),
            CoordConversion.chunkBlockIndexGetZ(it.index()),
            it.id(),
            it.data()?.toString()
        )
    }
    private val originalBlockEntities = blockEntities.toList()

    private val sectionData = MapDataSections(initialSections(source))
    private val originalData = sectionData.raw()

    override val data: DataSections get() = sectionData

    override fun getBlock(x: Int, y: Int, z: Int): String {
        val section = sections[sectionIndex(y)]
        val palette = section.blockPalette()
        val position = blockIndex(x, y, z)
        // blockData must not be read for a single-state palette
        if (palette.size == 1) return palette[0]

        return palette[unpack(section.blockData())[position]]
    }

    override fun setBlock(x: Int, y: Int, z: Int, state: String) {
        val sectionIndex = sectionIndex(y)
        val position = blockIndex(x, y, z)
        val section = sections[sectionIndex]
        val oldPalette = section.blockPalette()
        val singleState = oldPalette.size == 1

        val indices = if (singleState) IntArray(BLOCKS_PER_SECTION) else unpack(section.blockData())
        val palette = oldPalette.toMutableList()
        var stateIndex = palette.indexOf(state)
        if (stateIndex < 0) {
            palette.add(state)
            stateIndex = palette.lastIndex
        }

        // Nothing to write when the block already has this state
        if (indices[position] == stateIndex) return
        indices[position] = stateIndex

        // The light and biome accessors assert that their data exists, so they are only read when it does
        // Bits per entry follow the palette size, so adding a state may need a wider repack
        val bits = maxOf(1, PaletteUtil.bitsToRepresent(palette.size - 1))
        sections[sectionIndex] = PolarSection(
            palette.toTypedArray(),
            PaletteUtil.pack(indices, bits),
            section.biomePalette(),
            if (section.biomePalette().size > 1) section.biomeData() else null,
            section.blockLightContent(),
            if (section.blockLightContent() == LightContent.PRESENT) section.blockLight() else null,
            section.skyLightContent(),
            if (section.skyLightContent() == LightContent.PRESENT) section.skyLight() else null
        )
        sectionsChanged = true
    }

    /** Builds the chunk to store: edited sections and block entities, data sections as a container. */
    fun toPolarChunk(): PolarChunk {
        val blockEntitiesChanged = blockEntities != originalBlockEntities
        val now = sectionData.raw()
        val dataChanged = now.keys != originalData.keys ||
                now.any { (key, bytes) -> !bytes.contentEquals(originalData[key]) }
        val isContainer = SectionContainerCodec.isContainer(source.userData())
        if (!sectionsChanged && !blockEntitiesChanged && !dataChanged && isContainer) return source

        val storedBlockEntities = if (blockEntitiesChanged) {
            blockEntities.map {
                PolarChunk.BlockEntity(
                    CoordConversion.chunkBlockIndex(it.x, it.y, it.z),
                    it.id,
                    it.snbt?.let { snbt -> TagParser.parseCompoundFully(snbt) }
                )
            }.toTypedArray()
        } else {
            source.blockEntities()
        }

        return PolarChunk(
            source.x(),
            source.z(),
            sections,
            storedBlockEntities,
            source.heightmaps(),
            SectionContainerCodec.encode(now)
        )
    }

    private fun sectionIndex(y: Int): Int {
        val index = (y shr 4) - minSection
        require(index in sections.indices) { "Block y=$y is outside of the world" }
        return index
    }

    private fun blockIndex(x: Int, y: Int, z: Int): Int {
        require(x in 0..15 && z in 0..15) { "Local block coordinates must be 0..15, got x=$x z=$z" }
        return ((y and 15) shl 8) or (z shl 4) or x
    }

    private fun unpack(packed: LongArray): IntArray {
        val bits = PaletteUtil.getBitsForLongLength(packed.size, BLOCKS_PER_SECTION)
        return IntArray(BLOCKS_PER_SECTION).also { PaletteUtil.unpack(it, packed, bits) }
    }

    private companion object {
        const val BLOCKS_PER_SECTION = 4096

        fun initialSections(chunk: PolarChunk): Map<String, ByteArray> =
            if (SectionContainerCodec.isContainer(chunk.userData())) {
                SectionContainerCodec.decode(chunk.userData())
            } else {
                LegacyUserDataImporter.importChunk(chunk.userData(), chunk.x(), chunk.z())
            }
    }
}
