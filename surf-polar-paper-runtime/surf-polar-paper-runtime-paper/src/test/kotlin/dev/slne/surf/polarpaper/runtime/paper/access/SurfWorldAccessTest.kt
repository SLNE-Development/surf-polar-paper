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
import dev.slne.surf.polarpaper.runtime.paper.fixtures.entityNbt
import dev.slne.surf.polarpaper.runtime.paper.handler.EntityCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.builtins.serializer
import live.minehub.polarpaper.core.util.MemorySegmentWriter
import net.kyori.adventure.key.Key
import net.minecraft.world.level.chunk.ChunkAccess
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

    private var existing: ByteArray? = null
    private var chunkOf: (ChunkAccess) -> Chunk = { chunk }
    private var chunkEntities: Array<Entity> = emptyArray()
    private val chunkAccess = mockk<ChunkAccess>(relaxed = true)

    private fun access(vararg handlers: ChunkDataHandler<*>, entities: EntitiesHandler? = null): SurfWorldAccess {
        val settings = polarPaperSettings {
            loadHooks.forEach { onChunkLoad(it) }
            saveHooks.forEach { onChunkSave(it) }
        }
        val world = mockk<SurfWorldImpl>(relaxed = true) {
            every { api.settings } returns settings
        }
        return SurfWorldAccess(world, handlers.toList(), entities, live, chunkOf, { _, _ -> existing })
    }

    private fun entity() = mockk<Entity>(relaxed = true) {
        every { location } returns Location(null, 1.0, 2.0, 3.0, 0f, 0f)
        every { isPersistent } returns true
    }

    private fun write(access: SurfWorldAccess): ByteArray? {
        MemorySegmentWriter(64).use { writer ->
            access.saveChunkData(chunkAccess, emptyMap(), emptyArray(), writer)
            return writer.writtenBytes.takeIf { it.isNotEmpty() }
        }
    }

    /** Prepares and writes the chunk the way the bridge and Polar do, then decodes what was written. */
    private fun saved(access: SurfWorldAccess, vararg entities: Entity): MapDataSections {
        chunkEntities = arrayOf(*entities)
        every { chunk.entities } returns chunkEntities
        access.prepareChunkSave(chunk)
        return MapDataSections(SectionContainerCodec.decode(write(access)))
    }

    private fun load(access: SurfWorldAccess, bytes: ByteArray?) =
        access.loadChunkData(bukkitWorld, chunkAccess, bytes)

    private val codec = object : EntityCodec {
        override fun serialize(entity: Entity): ByteArray? = entityNbt("minecraft:pig")
        override fun spawn(chunk: Chunk, stored: List<StoredEntity>) {}
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
        load(access, bytes)
        assertNull(handler.restored)

        assertEquals("old", saved(access)[stringType])
    }

    @Test
    fun `handlers restore their section when the chunk becomes live`() {
        val handler = StringHandler(stringType)
        val sections = MapDataSections()
        sections[stringType] = "stored"

        access(handler).let { load(it, SectionContainerCodec.encode(sections.raw())) }

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

        access(handler).let { load(it, SectionContainerCodec.encode(sections.raw())) }

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

        access(failing, other).let { load(it, SectionContainerCodec.encode(sections.raw())) }

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
    fun `a failing save hook fails the prepare`() {
        saveHooks += { throw IllegalStateException("boom") }
        every { chunk.entities } returns emptyArray()

        assertThrows(IllegalStateException::class.java) { access(StringHandler(stringType)).prepareChunkSave(chunk) }
    }

    @Test
    fun `saveChunkData never throws and falls back to the existing user data`() {
        existing = byteArrayOf(1, 2, 3)
        val handler = mockk<ChunkDataHandler<String>> {
            every { type } returns stringType
            every { isAvailable() } returns true
            every { capture(any(), any(), any()) } throws IllegalStateException("boom")
        }
        val access = access(handler)
        every { chunk.entities } returns emptyArray()
        assertThrows(IllegalStateException::class.java) { access.prepareChunkSave(chunk) }

        // Nothing live and nothing prepared: the existing bytes are kept
        assertArrayEquals(byteArrayOf(1, 2, 3), write(access))
    }

    @Test
    fun `saveChunkData does not throw when the writer fails`() {
        val failing = mockk<MemorySegmentWriter>()
        every { failing.write(any<ByteArray>()) } throws IllegalStateException("boom")
        existing = byteArrayOf(1)

        access().saveChunkData(chunkAccess, emptyMap(), emptyArray(), failing)
    }

    @Test
    fun `a chunk whose data cannot be decoded keeps its stored bytes on save`() {
        existing = byteArrayOf(9, 9)
        val access = access(StringHandler(stringType))
        load(access, byteArrayOf(0x53, 0x55, 0x52, 0x46, 0x01))
        every { chunk.entities } returns emptyArray()

        access.prepareChunkSave(chunk)

        assertArrayEquals(byteArrayOf(9, 9), write(access))
    }

    @Test
    fun `beginSaveRound drops prepared bytes of an earlier round`() {
        existing = byteArrayOf(4)
        val access = access(StringHandler(stringType))
        every { chunk.entities } returns emptyArray()
        access.prepareChunkSave(chunk)

        access.beginSaveRound()

        // The prepared bytes are gone; the chunk was registered by the prepare, so its live sections are written
        assertTrue(SectionContainerCodec.isContainer(write(access)))
    }

    @Test
    fun `a failed prepare clears the prepared bytes`() {
        existing = byteArrayOf(5)
        var fail = false
        saveHooks += { if (fail) throw IllegalStateException("boom") }
        val access = access(StringHandler(stringType))
        every { chunk.entities } returns emptyArray()
        access.prepareChunkSave(chunk)
        fail = true

        assertThrows(IllegalStateException::class.java) { access.prepareChunkSave(chunk) }
        // The first prepare registered the chunk; the failed one left no prepared bytes behind
        assertTrue(SectionContainerCodec.isContainer(write(access)))
    }

    @Test
    fun `saveChunkData falls back to the existing data when the bukkit chunk is unavailable`() {
        existing = byteArrayOf(6)
        chunkOf = { throw IllegalStateException("not a level chunk") }

        assertArrayEquals(byteArrayOf(6), write(access()))
    }

    @Test
    fun `an unprepared live chunk is written as it is`() {
        val sections = MapDataSections()
        sections[plainType] = "live"
        live.put(2, -3, sections)

        assertEquals("live", MapDataSections(SectionContainerCodec.decode(write(access())))[plainType])
    }

    @Test
    fun `a passenger of a claimed vehicle is not stored`() {
        val passenger = entity()
        val vehicle = entity().also { every { it.passengers } returns listOf(passenger) }
        val handler = StringHandler(stringType).also { it.toClaim = listOf(vehicle) }

        val result = saved(access(handler, entities = EntitiesHandler(codec)), vehicle, passenger)

        assertNull(result[BuiltInData.ENTITIES])
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
    fun `the written bytes round trip through loadChunkData`() {
        val handler = StringHandler(stringType)
        val access = access(handler)
        every { chunk.entities } returns emptyArray()
        access.prepareChunkSave(chunk)
        val bytes = write(access)!!

        load(access, bytes)

        assertArrayEquals(bytes, SectionContainerCodec.encode(live.sections(2, -3)!!.raw()))
        assertEquals("captured", handler.restored)
    }
}
