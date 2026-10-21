package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.polarpaper.api.data.ChunkDataHandler
import dev.slne.surf.polarpaper.api.data.DataType
import dev.slne.surf.polarpaper.api.migration.RawWorld
import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.SurfChunk
import dev.slne.surf.polarpaper.api.world.UnhandledWorldDataException
import dev.slne.surf.polarpaper.api.world.WorldMigrationException
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import dev.slne.surf.polarpaper.core.client.data.MapDataSections
import dev.slne.surf.polarpaper.core.client.data.SectionContainerCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.runtime.paper.access.SurfWorldAccess
import dev.slne.surf.polarpaper.runtime.paper.pipeline.PaperPlatform
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import live.minehub.polarpaper.core.world.PolarChunk
import live.minehub.polarpaper.core.world.PolarWorld
import live.minehub.polarpaper.core.world.PolarWorldAccess
import net.kyori.adventure.key.Key
import net.minecraft.SharedConstants
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.CopyOnWriteArrayList

class PlatformWorldBridgePaperTest {
    companion object {
        // PolarWorld reads the game version when it is created
        @JvmStatic
        @BeforeAll
        fun detectVersion() = SharedConstants.tryDetectVersion()
    }

    /** One timeline for the stages and the platform calls, so tests can assert their relative order. */
    private val events = CopyOnWriteArrayList<String>()

    /** A platform that keeps real Polar worlds and drives [SurfWorldAccess] the way Polar does, without a server. */
    private inner class FakePlatform : PaperPlatform {
        val bukkit = mockk<World>(relaxed = true)
        var polarWorld: PolarWorld? = null
        var access: SurfWorldAccess? = null
        val unloaded = mutableListOf<World>()
        val savedUserData = mutableListOf<ByteArray>()
        var savedBytes = byteArrayOf(9, 9)
        var refuseUnload = false
        private val chunks = HashMap<Pair<Int, Int>, Chunk>()

        val created get() = events.count { it == "create" }
        val forceLoads get() = events.count { it.startsWith("load:") }

        override suspend fun createWorld(polarWorld: PolarWorld, name: String, access: PolarWorldAccess): World? {
            events += "create"
            this.polarWorld = polarWorld
            this.access = access as SurfWorldAccess
            return bukkit
        }

        override fun findWorld(name: String): World? = bukkit.takeIf { polarWorld != null && unloaded.isEmpty() }

        override suspend fun unloadWorld(world: World): Boolean {
            events += "unload"
            if (refuseUnload) return false
            unloaded += world
            return true
        }

        override suspend fun loadChunk(world: World, x: Int, z: Int): Chunk {
            events += "load:$x,$z"
            val chunk = mockk<Chunk>(relaxed = true) {
                every { this@mockk.x } returns x
                every { this@mockk.z } returns z
            }
            chunks[x to z] = chunk
            // Polar restores the chunk data right after inserting the chunk
            access!!.load(chunk, polarWorld!!.chunkAt(x, z)?.userData())
            return chunk
        }

        override fun chunkIfLoaded(world: World, x: Int, z: Int): Chunk? = chunks[x to z]

        override fun loadedChunkCoordinates(world: World): List<Pair<Int, Int>> = chunks.keys.toList()

        /** A chunk that became live without going through Polar's load hook, like a generated one. */
        fun generate(x: Int, z: Int) {
            chunks[x to z] = mockk<Chunk>(relaxed = true) {
                every { this@mockk.x } returns x
                every { this@mockk.z } returns z
            }
        }

        override fun addTicket(world: World, x: Int, z: Int, plugin: Plugin) {
            events += "ticket+:$x,$z"
        }

        override fun removeTicket(world: World, x: Int, z: Int, plugin: Plugin) {
            events += "ticket-:$x,$z"
        }

        override suspend fun <T> onChunkThread(world: World, x: Int, z: Int, block: () -> T): T = block()

        override fun setWorldUserData(world: World, bytes: ByteArray) {
            events += "userData"
            savedUserData += bytes
        }

        /** Held by the next save only, to keep it running. */
        var saveGate: CompletableDeferred<Unit>? = null

        /** Loads a chunk with exactly these stored bytes, like Polar does for a chunk of the Polar world. */
        fun loadWithData(x: Int, z: Int, bytes: ByteArray) {
            val chunk = mockk<Chunk>(relaxed = true) {
                every { this@mockk.x } returns x
                every { this@mockk.z } returns z
            }
            chunks[x to z] = chunk
            access!!.load(chunk, bytes)
        }

        override suspend fun savePolar(world: World, polarWorld: PolarWorld, access: PolarWorldAccess): ByteArray {
            events += "savePolar"
            saveGate?.also { saveGate = null }?.await()
            return savedBytes
        }
    }

    private val platform = FakePlatform()

    // The tests hand over a prepared world instead of bytes: Polar reads and writes with a compression library that
    // only the server provides
    private var storedWorld: PolarWorld? = null
    private val bridge = PlatformWorldBridgePaper(platform, { requireNotNull(storedWorld) }, 300.milliseconds)
    private val storedBytes = byteArrayOf(1)

    private val listType = DataType(Key.key("test", "numbers"), ListSerializer(Int.serializer()))
    private val nexoKey = Key.key("nexo", "furniture")
    private val textType = DataType(Key.key("test", "text"), String.serializer())

    // Written as plain bytes, because nexo:furniture is reserved and cannot be registered as a custom type
    private val nexoBytes = byteArrayOf(1, 2, 3)

    private fun world(settings: PolarPaperSettings): SurfWorldImpl {
        val api = mockk<SurfPolarPaperApiImpl>(relaxed = true) {
            every { this@mockk.settings } returns settings
            every { plugin } returns mockk<Plugin>(relaxed = true)
        }
        return SurfWorldImpl(UUID.randomUUID(), "survival", 1, api, bridge)
    }

    private fun settings(vararg types: DataType<*>, handlers: List<ChunkDataHandler<*>> = emptyList()) =
        polarPaperSettings {
            gameMode("survival") { version = 100 }
            types.forEach { data(it) }
            handlers { handlers.forEach { add(it) } }
        }

    /** Stores a world with the given chunks, each holding the given encoded sections, and returns its fake bytes. */
    private fun polarBytes(vararg chunks: Triple<Int, Int, Map<String, ByteArray>>): ByteArray {
        storedWorld = PolarWorld(-4, 19, chunks.map { (x, z, sections) ->
            PolarChunk(x, z, 24).withUserData(SectionContainerCodec.encode(sections))
        })
        return storedBytes
    }

    private fun encoded(type: DataType<String>, value: String): ByteArray {
        val sections = MapDataSections()
        sections[type] = value
        return sections.raw().getValue(type.key.asString())
    }

    /** A migration that records its stages on the shared timeline. */
    private fun recording(
        to: Int,
        live: Boolean = false,
        raw: (RawWorld) -> Unit = {},
        onLive: (SurfChunk) -> Unit = {}
    ) = object : WorldMigration(to) {
        override fun migrateRaw(world: RawWorld) {
            events += "raw$to"
            raw(world)
        }

        override val hasLiveStage get() = live

        override fun migrateLive(chunk: SurfChunk) {
            events += "live$to:${chunk.x},${chunk.z}"
            onLive(chunk)
        }
    }

    @Test
    fun `raw stages run in version order before the platform world is created`() = runTest {
        val world = world(settings())

        bridge.loadWorld(world, null, listOf(recording(2), recording(3), recording(4)))

        assertEquals(listOf("raw2", "raw3", "raw4", "create"), events.toList())
    }

    @Test
    fun `a world migrated from 1 to 100 applies all 99 raw stages in order on the same data`() = runTest {
        val world = world(settings(listType))
        val migrations = (2..100).map { version ->
            object : WorldMigration(version) {
                override fun migrateRaw(world: RawWorld) {
                    // Migration 100 only sees what migration 2 wrote when all of them share the data
                    val numbers = world.data[listType].orEmpty()
                    check(version == 2 || numbers.first() == 2) { "Migration $version does not see migration 2" }
                    world.data[listType] = numbers + version
                }
            }
        }

        bridge.loadWorld(world, null, migrations)

        assertEquals((2..100).toList(), world.data[listType])
    }

    @Test
    fun `no force load happens without live stages`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, emptyMap()), Triple(1, 0, emptyMap()))

        bridge.loadWorld(world, bytes, listOf(recording(2)))

        assertEquals(0, platform.forceLoads)
        assertTrue(events.none { it.startsWith("ticket") })
    }

    @Test
    fun `live stages run for every chunk after all raw stages`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, emptyMap()), Triple(1, 0, emptyMap()), Triple(0, 1, emptyMap()))

        bridge.loadWorld(world, bytes, listOf(recording(2, live = true), recording(3, live = true)))

        val timeline = events.toList()
        val firstLive = timeline.indexOfFirst { it.startsWith("live") }
        assertTrue(timeline.indexOf("raw3") < timeline.indexOf("create"))
        assertTrue(firstLive > timeline.indexOf("create"))
        assertEquals(setOf("0,0", "1,0", "0,1"), timeline.filter { it.startsWith("live2:") }.map { it.removePrefix("live2:") }.toSet())
        for (chunk in listOf("0,0", "1,0", "0,1")) {
            assertTrue(timeline.indexOf("live2:$chunk") < timeline.indexOf("live3:$chunk"), "Order in chunk $chunk")
        }
        // Per chunk the ticket is held from before the load until after the last live stage
        for (chunk in listOf("0,0", "1,0", "0,1")) {
            val order = listOf("ticket+:$chunk", "load:$chunk", "live2:$chunk", "live3:$chunk", "ticket-:$chunk")
                .map { timeline.indexOf(it) }
            assertEquals(order.sorted(), order, "Ticket and stage order of chunk $chunk")
            assertTrue(order.all { it >= 0 })
        }
    }

    @Test
    fun `a section without handler aborts the load and unloads the platform world`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, mapOf(nexoKey.asString() to nexoBytes)))

        val exception = assertThrows<UnhandledWorldDataException> {
            bridge.loadWorld(world, bytes, listOf(recording(2, live = true)))
        }

        assertEquals(setOf(nexoKey), exception.keys)
        // The platform world exists during live stages, so it has to be unloaded again
        assertEquals(listOf(platform.bukkit), platform.unloaded)
        assertNull(bridge.chunk(world, 0, 0))
    }

    @Test
    fun `a section without handler aborts a load without live stages before the platform world is created`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, mapOf(nexoKey.asString() to nexoBytes)))

        assertThrows<UnhandledWorldDataException> { bridge.loadWorld(world, bytes, listOf(recording(2))) }

        assertEquals(0, platform.created)
    }

    @Test
    fun `unhandled keys of world data count too`() = runTest {
        val world = world(settings())
        val raw = mapOf(nexoKey.asString() to nexoBytes)
        storedWorld = PolarWorld(-4, 19).also { it.userData(SectionContainerCodec.encode(raw)) }

        val exception = assertThrows<UnhandledWorldDataException> { bridge.loadWorld(world, storedBytes, emptyList()) }

        assertEquals(setOf(nexoKey), exception.keys)
    }

    @Test
    fun `a nexo section removed by a live stage does not abort the load`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, mapOf(nexoKey.asString() to nexoBytes)))
        val removeNexo = recording(2, live = true, onLive = { it.data.remove(nexoKey) })

        bridge.loadWorld(world, bytes, listOf(removeNexo))

        assertTrue(platform.unloaded.isEmpty())
        assertSame(platform.bukkit, bridge.bukkitWorld(world))
    }

    @Test
    fun `a throwing raw stage is wrapped with its version and no platform world is created`() = runTest {
        val world = world(settings())
        val failure = IllegalStateException("boom")
        val migrations = listOf(recording(2), recording(3, raw = { throw failure }), recording(4))

        val exception = assertThrows<WorldMigrationException> { bridge.loadWorld(world, null, migrations) }

        assertEquals(3, exception.toVersion)
        assertSame(failure, exception.cause)
        assertEquals(world.uuid, exception.worldUuid)
        assertEquals(0, platform.created)
        assertFalse("raw4" in events)
    }

    @Test
    fun `a throwing live stage is wrapped with its version and unloads the platform world`() = runTest {
        val world = world(settings())
        val bytes = polarBytes(Triple(0, 0, emptyMap()))
        val migration = recording(2, live = true, onLive = { error("boom") })

        val exception = assertThrows<WorldMigrationException> { bridge.loadWorld(world, bytes, listOf(migration)) }

        assertEquals(2, exception.toVersion)
        assertEquals(listOf(platform.bukkit), platform.unloaded)
        assertEquals(1, events.count { it == "ticket-:0,0" })
    }

    @Test
    fun `save returns polar bytes including world data`() = runTest {
        val recording = object : ChunkDataHandler<String> {
            override val type = DataType(Key.key("test", "recorded"), String.serializer())
            override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): String {
                events += "prepare:${chunk.x},${chunk.z}"
                return "captured"
            }

            override fun restore(chunk: Chunk, value: String) = Unit
        }
        val world = world(settings(listType, handlers = listOf(recording)))
        val bytes = polarBytes(Triple(0, 0, emptyMap()))
        bridge.loadWorld(world, bytes, emptyList())
        platform.loadChunk(platform.bukkit, 0, 0)
        world.data[listType] = listOf(1, 2, 3)
        events.clear()

        val saved = bridge.saveWorld(world)

        assertArrayEquals(byteArrayOf(9, 9), saved)
        // The chunk is prepared before Polar writes, and the world data is in place by then
        assertEquals(listOf("prepare:0,0", "userData", "savePolar"), events.toList())
        val worldData = MapDataSections(SectionContainerCodec.decode(platform.savedUserData.single()))
        assertEquals(listOf(1, 2, 3), worldData[listType])
    }

    @Test
    fun `a failing chunk preparation fails the save before polar writes anything`() = runTest {
        val failing = object : ChunkDataHandler<String> {
            override val type = DataType(Key.key("test", "failing"), String.serializer())
            override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): String =
                throw IllegalStateException("cannot capture")

            override fun restore(chunk: Chunk, value: String) = Unit
        }
        val world = world(settings(handlers = listOf(failing)))
        bridge.loadWorld(world, polarBytes(Triple(0, 0, emptyMap())), emptyList())
        platform.loadChunk(platform.bukkit, 0, 0)
        events.clear()

        assertThrows<IllegalStateException> { bridge.saveWorld(world) }

        assertTrue("savePolar" !in events)
        assertTrue("userData" !in events)
    }

    @Test
    fun `saving a world that is not loaded fails`() = runTest {
        assertThrows<IllegalStateException> { bridge.saveWorld(world(settings())) }
    }

    @Test
    fun `chunk returns the live chunk with its sections`() = runTest {
        val world = world(settings(textType))
        val bytes = polarBytes(Triple(0, 0, mapOf(textType.key.asString() to encoded(textType, "chair"))))
        bridge.loadWorld(world, bytes, emptyList())
        assertNull(bridge.chunk(world, 0, 0))

        platform.loadChunk(platform.bukkit, 0, 0)

        assertEquals("chair", bridge.chunk(world, 0, 0)?.data?.get(textType))
    }

    @Test
    fun `unloadWorld unloads without saving and fails when bukkit refuses`() = runTest {
        val world = world(settings())
        bridge.loadWorld(world, null, emptyList())
        platform.refuseUnload = true

        assertThrows<IllegalStateException> { bridge.unloadWorld(world) }
        assertSame(platform.bukkit, bridge.bukkitWorld(world))

        platform.refuseUnload = false
        bridge.unloadWorld(world)

        assertEquals(listOf(platform.bukkit), platform.unloaded)
        assertTrue("savePolar" !in events)
        assertNull(bridge.bukkitWorld(world))
    }

    @Test
    fun `unloadWorld does nothing for a world that is not loaded`() = runTest {
        bridge.unloadWorld(world(settings()))

        assertTrue(events.isEmpty())
    }

    private val capturedType = DataType(Key.key("test", "captured"), String.serializer())

    private fun capturing() = object : ChunkDataHandler<String> {
        override val type = capturedType
        override fun capture(chunk: Chunk, entities: List<Entity>, claim: (Entity) -> Unit): String {
            events += "prepare:${chunk.x},${chunk.z}"
            return "captured"
        }

        override fun restore(chunk: Chunk, value: String) = Unit
    }

    @Test
    fun `a chunk generated after the load is prepared on save and keeps its section`() = runTest {
        val world = world(settings(handlers = listOf(capturing())))
        bridge.loadWorld(world, null, emptyList())
        // Not in the polar world, so no load hook ever registered it
        platform.generate(5, 5)
        assertNull(bridge.chunk(world, 9, 9))

        bridge.saveWorld(world)

        assertTrue("prepare:5,5" in events)
        assertEquals("captured", bridge.chunk(world, 5, 5)?.data?.get(capturedType))
    }

    @Test
    fun `concurrent saves of one world run one after another`() = runTest {
        val world = world(settings(handlers = listOf(capturing())))
        bridge.loadWorld(world, null, emptyList())
        platform.generate(0, 0)
        events.clear()
        val gate = CompletableDeferred<Unit>()
        platform.saveGate = gate

        val first = async(Dispatchers.Default) { bridge.saveWorld(world) }
        while (events.count { it == "savePolar" } < 1) yield()
        val second = async(Dispatchers.Default) { bridge.saveWorld(world) }
        // The second save must not start a round while the first one is still writing
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(1, events.count { it.startsWith("prepare:") })

        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, events.count { it == "savePolar" })
        assertEquals(listOf("prepare:0,0", "userData", "savePolar", "prepare:0,0", "userData", "savePolar"), events.toList())
    }

    @Test
    fun `a shutdown save does not wait forever for a save that holds the lock`() = runTest {
        val world = world(settings(handlers = listOf(capturing())))
        bridge.loadWorld(world, null, emptyList())
        platform.generate(0, 0)
        // An autosave that is stuck, like one queued behind the blocked main thread
        platform.saveGate = CompletableDeferred()
        val stuck = async(Dispatchers.Default) { bridge.saveWorld(world) }
        while (events.count { it == "savePolar" } < 1) yield()

        val saved = withContext(Dispatchers.Default) { bridge.saveWorldForShutdown(world) }

        assertArrayEquals(byteArrayOf(9, 9), saved)
        assertEquals(2, events.count { it == "savePolar" })
        stuck.cancel()
    }

    @Test
    fun `chunk returns null for an undecodable chunk and a save keeps its stored bytes`() = runTest {
        val world = world(settings())
        bridge.loadWorld(world, null, emptyList())
        // The container magic with an unsupported body
        val broken = byteArrayOf(0x53, 0x55, 0x52, 0x46, 0x01)
        platform.loadWithData(3, 3, broken)

        assertNull(bridge.chunk(world, 3, 3))
        bridge.saveWorld(world)

        // The access writes the stored bytes for such a chunk, see SurfWorldAccessTest
        assertTrue(platform.access!!.isUndecodable(3, 3))
    }

    @Test
    fun `an unloaded chunk loses its sections`() = runTest {
        val world = world(settings(textType))
        val bytes = polarBytes(Triple(0, 0, mapOf(textType.key.asString() to encoded(textType, "chair"))))
        bridge.loadWorld(world, bytes, emptyList())
        platform.loadChunk(platform.bukkit, 0, 0)
        assertEquals("chair", bridge.chunk(world, 0, 0)?.data?.get(textType))

        bridge.chunkUnloaded(platform.bukkit, 0, 0)

        assertNull(bridge.chunk(world, 0, 0)?.data?.get(textType))
    }
}
