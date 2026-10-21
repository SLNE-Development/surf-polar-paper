package dev.slne.surf.polarpaper.runtime.paper.access

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.data.StoredEntity
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.handler.EntitiesHandler
import dev.slne.surf.polarpaper.runtime.paper.handler.EntitiesHandlerTest
import dev.slne.surf.polarpaper.runtime.paper.handler.EntityCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.builtins.serializer
import live.minehub.polarpaper.core.util.MemorySegmentWriter
import net.kyori.adventure.key.Key
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfWorldAccessTest {
    private val stringType = DataType(Key.key("test", "string"), String.serializer())
    private val plainType = DataType(Key.key("test", "plain"), String.serializer())

    private class StringHandler(
        override val type: DataType<String>,
        private val available: Boolean = true,
        private val captured: String? = "captured",
        private val log: MutableList<String> = mutableListOf()
    ) : ChunkDataHandler<String> {
        var seenEntities: List<Entity> = emptyList()
        var restored: String? = null
        var toClaim: List<Entity> = emptyList()

        override fun isAvailable() = available
        override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): String? {
            log += "capture"
            seenEntities = entities
            toClaim.forEach(claim)
            return captured
        }

        override fun restore(chunk: Chunk, value: String) {
            log += "restore"
            restored = value
        }
    }

    private val bukkitWorld = mockk<World>(relaxed = true)
    private val pdc = mockk<PersistentDataContainer>(relaxed = true) { every { isEmpty } returns true }
    private val chunk = mockk<Chunk>(relaxed = true) {
        every { x } returns 2
        every { z } returns -3
        every { world } returns bukkitWorld
        every { persistentDataContainer } returns pdc
    }
    private val live = LiveChunkRegistry()

    private val loadHooks = mutableListOf<(SurfChunk) -> Unit>()
    private val saveHooks = mutableListOf<(SurfChunk) -> Unit>()

    private fun access(vararg handlers: ChunkDataHandler<*>, entities: EntitiesHandler? = null): SurfWorldAccess {
        val settings = polarPaperSettings {
            loadHooks.forEach { onChunkLoad(it) }
            saveHooks.forEach { onChunkSave(it) }
        }
        val world = mockk<SurfWorldImpl>(relaxed = true) {
            every { api.settings } returns settings
        }
        return SurfWorldAccess(world, handlers.toList(), entities, live)
    }

    private fun entity() = mockk<Entity>(relaxed = true) {
        every { location } returns Location(null, 1.0, 2.0, 3.0, 0f, 0f)
        every { isPersistent } returns true
    }

    private fun saved(access: SurfWorldAccess, vararg entities: Entity): MapDataSections {
        MemorySegmentWriter(64).use { writer ->
            access.saveChunk(chunk, arrayOf(*entities), writer)
            return MapDataSections(SectionContainerCodec.decode(writer.writtenBytes))
        }
    }

    private val codec = object : EntityCodec {
        override fun serialize(entity: Entity) = EntitiesHandlerTest.entityNbt("minecraft:pig")
        override fun spawn(world: World, stored: StoredEntity) {}
    }

    @Test
    fun `claimed entities are not stored as generic entities`() {
        val claimed = entity()
        val free = entity()
        val handler = StringHandler(stringType).also { it.toClaim = listOf(claimed) }

        val result = saved(access(handler, entities = EntitiesHandler(codec)), claimed, free)

        assertEquals(1, result[BuiltInData.ENTITIES]!!.size)
        assertEquals(listOf(claimed, free), handler.seenEntities)
    }

    @Test
    fun `sections without a handler are written back unchanged on save`() {
        val sections = MapDataSections()
        sections[plainType] = "kept"
        live.put(2, -3, sections)

        val result = saved(access(StringHandler(stringType)))

        assertEquals("kept", result[plainType])
        assertEquals("captured", result[stringType])
    }

    @Test
    fun `unavailable handlers do not restore and keep their section`() {
        val handler = StringHandler(stringType, available = false)
        val sections = MapDataSections()
        sections[stringType] = "old"
        val bytes = SectionContainerCodec.encode(sections.raw())

        val access = access(handler)
        access.populateChunkData(chunk, bytes)
        assertNull(handler.restored)

        assertEquals("old", saved(access)[stringType])
    }

    @Test
    fun `handlers restore their section when the chunk becomes live`() {
        val handler = StringHandler(stringType)
        val sections = MapDataSections()
        sections[stringType] = "stored"

        access(handler).populateChunkData(chunk, SectionContainerCodec.encode(sections.raw()))

        assertEquals("stored", handler.restored)
        assertEquals("stored", live.sections(2, -3)!![stringType])
    }

    @Test
    fun `load hooks run with the live chunk after restoring`() {
        val log = mutableListOf<String>()
        val handler = StringHandler(stringType, log = log)
        var hooked: SurfChunk? = null
        loadHooks += {
            log += "hook"
            hooked = it
        }
        val sections = MapDataSections()
        sections[stringType] = "stored"

        access(handler).populateChunkData(chunk, SectionContainerCodec.encode(sections.raw()))

        assertEquals(listOf("restore", "hook"), log)
        assertEquals(2, hooked!!.x)
        assertSame(chunk, hooked!!.bukkitChunk)
    }

    @Test
    fun `a failing handler does not stop the remaining restores`() {
        val failing = mockk<ChunkDataHandler<String>> {
            every { type } returns stringType
            every { isAvailable() } returns true
            every { restore(any(), any()) } throws IllegalStateException("boom")
        }
        val other = StringHandler(DataType(Key.key("test", "other"), String.serializer()))
        val sections = MapDataSections()
        sections[stringType] = "a"
        sections[other.type] = "b"
        var hookRan = false
        loadHooks += { hookRan = true }

        access(failing, other).populateChunkData(chunk, SectionContainerCodec.encode(sections.raw()))

        assertEquals("b", other.restored)
        assertTrue(hookRan)
    }

    @Test
    fun `chunk save hooks run before capture`() {
        val log = mutableListOf<String>()
        val handler = StringHandler(stringType, log = log)
        saveHooks += { log += "hook" }

        saved(access(handler))

        assertEquals(listOf("hook", "capture"), log)
    }

    @Test
    fun `a failing save hook propagates`() {
        saveHooks += { throw IllegalStateException("boom") }

        val access = access(StringHandler(stringType))
        MemorySegmentWriter(64).use { writer ->
            assertThrows(IllegalStateException::class.java) { access.saveChunk(chunk, emptyArray(), writer) }
        }
    }

    @Test
    fun `a handler returning null removes its section`() {
        val sections = MapDataSections()
        sections[stringType] = "old"
        live.put(2, -3, sections)

        val result = saved(access(StringHandler(stringType, captured = null)))

        assertFalse(result.contains(stringType.key))
    }

    @Test
    fun `players are never passed to handlers or stored`() {
        val player = mockk<Player>(relaxed = true) { every { isPersistent } returns true }
        val pig = entity()
        val handler = StringHandler(stringType)

        val result = saved(access(handler, entities = EntitiesHandler(codec)), player, pig)

        assertEquals(listOf<Entity>(pig), handler.seenEntities)
        assertEquals(1, result[BuiltInData.ENTITIES]!!.size)
        verify(exactly = 0) { player.location }
    }

    @Test
    fun `the written bytes round trip through populate`() {
        val handler = StringHandler(stringType)
        MemorySegmentWriter(64).use { writer ->
            val access = access(handler)
            access.saveChunk(chunk, emptyArray(), writer)
            val bytes = writer.writtenBytes
            access.populateChunkData(chunk, bytes)
            assertArrayEquals(bytes, SectionContainerCodec.encode(live.sections(2, -3)!!.raw()))
        }
    }
}
