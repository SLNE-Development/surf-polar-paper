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
    private val editing = HashMap<Int, EditedSection>()

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
        val sectionIndex = sectionIndex(y)
        val position = blockIndex(x, y, z)
        val section = sections[sectionIndex]
        // blockData must not be read for a single-state palette
        if (editing[sectionIndex] == null && section.blockPalette().size == 1) return section.blockPalette()[0]

        val edited = editing.getOrPut(sectionIndex) { open(section) }
        return edited.palette[edited.indices[position]]
    }

    override fun setBlock(x: Int, y: Int, z: Int, state: String) {
        val sectionIndex = sectionIndex(y)
        val position = blockIndex(x, y, z)
        val edited = editing.getOrPut(sectionIndex) { open(sections[sectionIndex]) }

        var stateIndex = edited.palette.indexOf(state)
        if (stateIndex < 0) {
            edited.palette.add(state)
            stateIndex = edited.palette.lastIndex
        }

        // Nothing to write when the block already has this state
        if (edited.indices[position] == stateIndex) return
        edited.indices[position] = stateIndex
        edited.dirty = true
    }

    /** Builds the chunk to store: edited sections and block entities, data sections as a container. */
    fun toPolarChunk(): PolarChunk {
        val sectionsChanged = editing.values.any { it.dirty }
        val blockEntitiesChanged = blockEntities != originalBlockEntities
        val now = sectionData.raw()
        val dataChanged = now.keys != originalData.keys ||
                now.any { (key, bytes) -> !bytes.contentEquals(originalData[key]) }
        val isContainer = SectionContainerCodec.isContainer(source.userData())
        if (!sectionsChanged && !blockEntitiesChanged && !dataChanged && isContainer) return source

        val storedBlockEntities = if (blockEntitiesChanged) {
            blockEntities.map {
                require(it.x in 0..15 && it.z in 0..15) { "Block entity x/z must be 0..15, got x=${it.x} z=${it.z}" }
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
            if (sectionsChanged) relitSections() else sections,
            storedBlockEntities,
            source.heightmaps(),
            SectionContainerCodec.encode(now)
        )
    }

    // Polar only relights a chunk on load when every section has MISSING light; it trusts stored light otherwise.
    // So after an edit every section of the chunk drops its light.
    private fun relitSections(): Array<PolarSection> = Array(sections.size) { index ->
        val section = sections[index]
        val edited = editing[index]
        val (palette, packed) = if (edited != null && edited.dirty) {
            pack(edited)
        } else {
            val palette = section.blockPalette()
            palette to if (palette.size > 1) section.blockData() else null
        }

        PolarSection(
            palette,
            packed,
            section.biomePalette(),
            // biomeData must not be read for a single-state palette
            if (section.biomePalette().size > 1) section.biomeData() else null,
            LightContent.MISSING,
            null,
            LightContent.MISSING,
            null
        )
    }

    /** Packs an edited section, dropping palette entries that no block uses any more. */
    private fun pack(edited: EditedSection): Pair<Array<String>, LongArray?> {
        val used = edited.indices.distinct().sorted()
        val palette = used.map { edited.palette[it] }.toTypedArray()
        if (palette.size == 1) return palette to null

        val remap = IntArray(edited.palette.size)
        used.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }
        val remapped = IntArray(BLOCKS_PER_SECTION) { remap[edited.indices[it]] }
        // Bits per entry follow the palette size
        return palette to PaletteUtil.pack(remapped, maxOf(1, PaletteUtil.bitsToRepresent(palette.size - 1)))
    }

    private fun open(section: PolarSection): EditedSection {
        val palette = section.blockPalette().toMutableList()
        val indices = if (palette.size == 1) IntArray(BLOCKS_PER_SECTION) else unpack(section.blockData())
        return EditedSection(palette, indices)
    }

    /** A section's blocks unpacked for editing, so each access is O(1) instead of a full unpack. */
    private class EditedSection(val palette: MutableList<String>, val indices: IntArray) {
        var dirty = false
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
