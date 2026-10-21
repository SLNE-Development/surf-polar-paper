package dev.slne.surf.polarpaper.runtime.paper.raw

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import live.minehub.polarpaper.core.userdata.EntityUtil
import live.minehub.polarpaper.core.util.MemorySegmentWriter
import live.minehub.polarpaper.core.world.PolarChunk
import live.minehub.polarpaper.core.world.PolarEntity
import live.minehub.polarpaper.core.world.PolarWorld
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class LegacyUserDataImporterTest {
    companion object {
        // PolarWorld reads the game version when it is created
        @JvmStatic
        @BeforeAll
        fun detectVersion() = SharedConstants.tryDetectVersion()
    }


    private fun entityNbt(id: String): ByteArray {
        val tag = CompoundTag().apply { putString("id", id) }
        val out = ByteArrayOutputStream()
        NbtIo.write(tag, DataOutputStream(out))
        return out.toByteArray()
    }

    /** Builds user data the way Polar's EntitiesWorldAccess writes it: version 2, entities, pdc. */
    private fun legacyBytes(pdc: ByteArray): Pair<ByteArray, ByteArray> {
        val nbt = entityNbt("minecraft:pig")
        val entities = listOf(PolarEntity(1.5, 70.0, 2.5, 90f, 10f, nbt))
        MemorySegmentWriter(256).use { writer ->
            writer.writeByte(2)
            EntityUtil.writeEntities(entities, writer)
            writer.writeByteArray(pdc)
            return writer.writtenBytes to nbt
        }
    }

    private fun worldWith(userData: ByteArray) =
        PolarWorld(-4, 19, listOf(PolarChunk(0, 0, arrayOf(), arrayOf(), arrayOfNulls<IntArray>(32), userData)))

    @Test
    fun `legacy entities user data imports into surf entities and chunk pdc`() {
        val pdc = byteArrayOf(7, 8, 9)
        val (bytes, nbt) = legacyBytes(pdc)

        val sections = MapDataSections(LegacyUserDataImporter.importChunk(bytes, 2, -1))

        // Polar stores positions relative to the chunk, sections hold world coordinates
        assertEquals(
            listOf(StoredEntity("minecraft:pig", 33.5, 70.0, -13.5, 90f, 10f, nbt)),
            sections[BuiltInData.ENTITIES]
        )
        assertArrayEquals(pdc, sections[BuiltInData.CHUNK_PDC])
    }

    @Test
    fun `empty legacy user data imports as no sections`() {
        assertTrue(LegacyUserDataImporter.importChunk(null, 0, 0).isEmpty())
        assertTrue(LegacyUserDataImporter.importChunk(ByteArray(0), 0, 0).isEmpty())
    }

    @Test
    fun `normalize rewrites legacy user data into a container`() {
        val world = worldWith(legacyBytes(byteArrayOf(1)).first)

        LegacyUserDataImporter.normalize(world)

        val sections = SectionContainerCodec.decode(world.chunkAt(0, 0)!!.userData())
        assertEquals(setOf("surf:entities", "surf:chunk-pdc"), sections.keys)
    }

    @Test
    fun `container user data is left untouched by normalize`() {
        val container = SectionContainerCodec.encode(mapOf("a:b" to byteArrayOf(1)))
        val world = worldWith(container)

        LegacyUserDataImporter.normalize(world)

        assertArrayEquals(container, world.chunkAt(0, 0)!!.userData())
    }
}
