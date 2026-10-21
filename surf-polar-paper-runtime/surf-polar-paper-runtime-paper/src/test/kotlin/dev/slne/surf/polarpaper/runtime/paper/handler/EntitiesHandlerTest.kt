package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.runtime.paper.fixtures.entityNbt
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EntitiesHandlerTest {
    private class FakeCodec : EntityCodec {
        val spawned = mutableListOf<StoredEntity>()
        var result: ByteArray? = entityNbt("minecraft:pig")
        override fun serialize(entity: Entity): ByteArray? = result
        override fun spawn(chunk: Chunk, stored: List<StoredEntity>) {
            spawned += stored
        }
    }

    private val pdc = mockk<PersistentDataContainer>(relaxed = true)
    private val chunk = mockk<Chunk>(relaxed = true) {
        every { persistentDataContainer } returns pdc
    }

    private fun entity(x: Double, persistent: Boolean = true, riding: Boolean = false) = mockk<Entity>(relaxed = true) {
        every { location } returns Location(null, x, 70.0, 3.5, 90f, 10f)
        every { isPersistent } returns persistent
        every { isInsideVehicle } returns riding
    }

    @Test
    fun `captures unclaimed entities in world coordinates`() {
        val sections = MapDataSections()
        EntitiesHandler(FakeCodec()).capture(chunk, listOf(entity(33.5)), sections)

        val stored = sections[BuiltInData.ENTITIES]!!
        assertEquals(1, stored.size)
        assertEquals("minecraft:pig", stored[0].type)
        assertEquals(33.5, stored[0].x)
        assertEquals(3.5, stored[0].z)
        assertArrayEquals(entityNbt("minecraft:pig"), stored[0].nbt)
    }

    @Test
    fun `non persistent entities are not stored`() {
        val sections = MapDataSections()
        EntitiesHandler(FakeCodec()).capture(chunk, listOf(entity(1.0, persistent = false)), sections)

        assertNull(sections[BuiltInData.ENTITIES])
    }

    @Test
    fun `a passenger is stored only inside its vehicle`() {
        val sections = MapDataSections()
        EntitiesHandler(FakeCodec()).capture(chunk, listOf(entity(1.0), entity(1.0, riding = true)), sections)

        assertEquals(1, sections[BuiltInData.ENTITIES]!!.size)
    }

    @Test
    fun `entities the serializer rejects are skipped`() {
        val codec = FakeCodec().also { it.result = null }
        val sections = MapDataSections()
        EntitiesHandler(codec).capture(chunk, listOf(entity(1.0)), sections)

        assertNull(sections[BuiltInData.ENTITIES])
    }

    @Test
    fun `an empty chunk stores neither entities nor pdc`() {
        every { pdc.isEmpty } returns true
        val sections = MapDataSections()
        sections[BuiltInData.ENTITIES] = listOf(StoredEntity("minecraft:pig", 0.0, 0.0, 0.0, 0f, 0f, byteArrayOf(1)))
        sections[BuiltInData.CHUNK_PDC] = byteArrayOf(1)

        EntitiesHandler(FakeCodec()).capture(chunk, emptyList(), sections)

        assertNull(sections[BuiltInData.ENTITIES])
        assertNull(sections[BuiltInData.CHUNK_PDC])
    }

    @Test
    fun `captures the chunk pdc`() {
        every { pdc.isEmpty } returns false
        every { pdc.serializeToBytes() } returns byteArrayOf(7, 8)
        val sections = MapDataSections()

        EntitiesHandler(FakeCodec()).capture(chunk, emptyList(), sections)

        assertArrayEquals(byteArrayOf(7, 8), sections[BuiltInData.CHUNK_PDC])
    }

    @Test
    fun `restores entities and pdc`() {
        val codec = FakeCodec()
        val sections = MapDataSections()
        val stored = StoredEntity("minecraft:pig", 1.0, 2.0, 3.0, 4f, 5f, entityNbt("minecraft:pig"))
        sections[BuiltInData.ENTITIES] = listOf(stored)
        sections[BuiltInData.CHUNK_PDC] = byteArrayOf(9)

        EntitiesHandler(codec).restore(chunk, sections)

        assertEquals(listOf(stored), codec.spawned)
        verify { pdc.readFromBytes(byteArrayOf(9)) }
    }
}
