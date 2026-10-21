package dev.slne.surf.polarpaper.runtime.paper.raw

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.DataType
import kotlinx.serialization.builtins.ByteArraySerializer
import net.kyori.adventure.key.Key
import dev.slne.surf.polarpaper.api.migration.RawBlockEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import live.minehub.polarpaper.core.util.CoordConversion
import live.minehub.polarpaper.core.util.PaletteUtil
import live.minehub.polarpaper.core.world.PolarChunk
import live.minehub.polarpaper.core.world.PolarSection
import live.minehub.polarpaper.core.world.PolarSection.LightContent
import live.minehub.polarpaper.core.world.PolarWorld
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PolarRawChunkTest {
    companion object {
        private val WORLD_KEY = DataType(Key.key("test", "world"), ByteArraySerializer())

        // PolarWorld reads the game version when it is created
        @JvmStatic
        @BeforeAll
        fun detectVersion() = SharedConstants.tryDetectVersion()
    }


    private val minSection = -4

    // Light arrays cannot be built outside a server, so the sections carry none
    private fun section(palette: Array<String> = arrayOf("minecraft:air"), data: LongArray? = null) =
        PolarSection(
            palette, data, arrayOf("minecraft:plains"), null,
            LightContent.MISSING, null, LightContent.MISSING, null
        )

    private fun chunk(
        sections: Array<PolarSection> = Array(24) { section() },
        blockEntities: Array<PolarChunk.BlockEntity> = emptyArray(),
        userData: ByteArray? = null
    ) = PolarChunk(0, 0, sections, blockEntities, arrayOfNulls<IntArray>(32), userData)

    @Test
    fun `getBlock reads the state from a single-state section`() {
        val sections = Array(24) { section() }
        sections[4] = section(arrayOf("minecraft:stone"))
        val raw = PolarRawChunk(chunk(sections), minSection)

        assertEquals("minecraft:stone", raw.getBlock(3, 5, 9))
        assertEquals("minecraft:air", raw.getBlock(3, -20, 9))
    }

    @Test
    fun `setBlock grows the palette and keeps other blocks`() {
        val raw = PolarRawChunk(chunk(), minSection)
        val states = listOf("minecraft:stone", "minecraft:oak_log[axis=y]", "minecraft:dirt")
        val positions = listOf(Triple(0, 64, 0), Triple(15, 70, 15), Triple(7, 79, 3))

        positions.forEachIndexed { i, (x, y, z) -> raw.setBlock(x, y, z, states[i]) }

        // All three positions are in the section y 64..79, so it is checked in full
        for (y in 64..79) for (x in 0..15) for (z in 0..15) {
            val expected = positions.indexOf(Triple(x, y, z)).let { if (it >= 0) states[it] else "minecraft:air" }
            assertEquals(expected, raw.getBlock(x, y, z), "at $x $y $z")
        }

        // Air plus three states fit 2 bits, more states force a wider repack
        for (i in 0..4) raw.setBlock(i, 66, 1, "minecraft:state_$i")
        assertEquals("minecraft:state_4", raw.getBlock(4, 66, 1))
        assertEquals("minecraft:stone", raw.getBlock(0, 64, 0))
        assertEquals("minecraft:oak_log[axis=y]", raw.getBlock(15, 70, 15))
        assertEquals("minecraft:dirt", raw.getBlock(7, 79, 3))
    }

    @Test
    fun `setBlock to the existing state of a single-state section changes nothing`() {
        val polarChunk = chunk(userData = SectionContainerCodec.encode(emptyMap()))
        val raw = PolarRawChunk(polarChunk, minSection)

        raw.setBlock(1, 1, 1, "minecraft:air")

        assertSame(polarChunk, raw.toPolarChunk())
    }

    @Test
    fun `packed states use the vanilla bit layout`() {
        // 17 states need 5 bits
        val raw = PolarRawChunk(chunk(), minSection)
        for (i in 0 until 17) raw.setBlock(i % 16, 0, i / 16, "minecraft:state_$i")
        val section = raw.toPolarChunk().sections()[-minSection]
        val indices = IntArray(4096)
        PaletteUtil.unpack(indices, section.blockData(), 5)

        assertEquals("minecraft:state_16", section.blockPalette()[indices[(0 shl 8) or (1 shl 4) or 0]])
    }

    @Test
    fun `block entities round-trip through snbt`() {
        val tag = CompoundTag().apply {
            putString("CustomName", "chest")
            putInt("Count", 3)
        }
        val index = CoordConversion.chunkBlockIndex(5, -10, 12)
        val raw = PolarRawChunk(
            chunk(blockEntities = arrayOf(PolarChunk.BlockEntity(index, "minecraft:chest", tag))),
            minSection
        )

        val entity = raw.blockEntities.single()
        assertEquals(5, entity.x)
        assertEquals(-10, entity.y)
        assertEquals(12, entity.z)
        assertEquals("minecraft:chest", entity.id)

        raw.blockEntities[0] = RawBlockEntity(2, 100, 3, "minecraft:chest", entity.snbt)
        val stored = raw.toPolarChunk().blockEntities().single()

        assertEquals(CoordConversion.chunkBlockIndex(2, 100, 3), stored.index())
        assertEquals(tag, stored.data())
    }

    @Test
    fun `flush writes sections as a container into chunk and world user data`() {
        val world = PolarWorld(minSection.toByte(), 19.toByte(), listOf(chunk()))
        val rawWorld = PolarRawWorld(world)

        val rawChunk = rawWorld.chunk(0, 0)!!
        rawChunk.data[BuiltInData.CHUNK_PDC] = byteArrayOf(1, 2, 3)
        rawChunk.setBlock(1, 0, 1, "minecraft:stone")
        rawWorld.data[WORLD_KEY] = byteArrayOf(9)
        rawWorld.flush()

        val stored = world.chunkAt(0, 0)!!
        assertTrue(SectionContainerCodec.isContainer(stored.userData()))
        val chunkSections = MapDataSections(SectionContainerCodec.decode(stored.userData()))
        assertArrayEquals(byteArrayOf(1, 2, 3), chunkSections[BuiltInData.CHUNK_PDC])
        assertEquals("minecraft:stone", PolarRawChunk(stored, minSection).getBlock(1, 0, 1))

        assertTrue(SectionContainerCodec.isContainer(world.userData()))
        val worldSections = MapDataSections(SectionContainerCodec.decode(world.userData()))
        assertArrayEquals(byteArrayOf(9), worldSections[WORLD_KEY])
    }

    @Test
    fun `editing a block drops stored light so polar relights the chunk`() {
        // Real light arrays need a server, FULL and EMPTY are the stored-light states that can be built here
        val sections = Array(24) {
            PolarSection(arrayOf("minecraft:air"), null, arrayOf("minecraft:plains"), null, LightContent.FULL, null, LightContent.EMPTY, null)
        }
        val raw = PolarRawChunk(chunk(sections), minSection)

        raw.setBlock(1, 64, 1, "minecraft:stone")

        val stored = raw.toPolarChunk().sections()
        assertTrue(stored.all { it.blockLightContent() == LightContent.MISSING && it.skyLightContent() == LightContent.MISSING })
    }

    @Test
    fun `replacing every block of a section keeps the palette minimal`() {
        val raw = PolarRawChunk(chunk(), minSection)
        raw.setBlock(0, 64, 0, "minecraft:stone")
        raw.setBlock(1, 64, 0, "minecraft:stone")
        raw.setBlock(2, 64, 0, "minecraft:dirt")

        for (x in 0..1) raw.setBlock(x, 64, 0, "minecraft:glass")

        val section = raw.toPolarChunk().sections()[(64 shr 4) - minSection]
        assertEquals(setOf("minecraft:air", "minecraft:dirt", "minecraft:glass"), section.blockPalette().toSet())
        assertEquals(3, section.blockPalette().size)
        assertEquals("minecraft:glass", PolarRawChunk(raw.toPolarChunk(), minSection).getBlock(1, 64, 0))
        assertEquals("minecraft:dirt", PolarRawChunk(raw.toPolarChunk(), minSection).getBlock(2, 64, 0))
    }

    @Test
    fun `setBlock on a section with a multi-entry biome palette preserves biomes`() {
        val biomeData = LongArray(1) { 0b01L }
        val sections = Array(24) {
            PolarSection(arrayOf("minecraft:air"), null, arrayOf("minecraft:plains", "minecraft:desert"), biomeData, LightContent.MISSING, null, LightContent.MISSING, null)
        }
        val raw = PolarRawChunk(chunk(sections), minSection)

        raw.setBlock(0, 0, 0, "minecraft:stone")

        val section = raw.toPolarChunk().sections()[-minSection]
        assertEquals(listOf("minecraft:plains", "minecraft:desert"), section.biomePalette().toList())
        assertArrayEquals(biomeData, section.biomeData())
    }

    @Test
    fun `block entities outside the chunk are rejected`() {
        val raw = PolarRawChunk(chunk(), minSection)
        raw.blockEntities.add(RawBlockEntity(16, 0, 0, "minecraft:chest", null))

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { raw.toPolarChunk() }
    }

    @Test
    fun `flush leaves a non-container world user data untouched when world sections are empty`() {
        val world = PolarWorld(minSection.toByte(), 19.toByte(), listOf(chunk()))
        val polarData = byteArrayOf(5, 6, 7)
        world.userData(polarData)

        PolarRawWorld(world).apply { chunk(0, 0)!!.setBlock(0, 0, 0, "minecraft:stone") }.flush()

        assertArrayEquals(polarData, world.userData())
    }
}
