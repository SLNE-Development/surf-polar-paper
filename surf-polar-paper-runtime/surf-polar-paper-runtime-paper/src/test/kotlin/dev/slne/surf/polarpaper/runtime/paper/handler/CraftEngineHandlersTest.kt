package dev.slne.surf.polarpaper.runtime.paper.handler

import dev.slne.surf.polarpaper.api.data.BuiltInData
import dev.slne.surf.polarpaper.api.data.FurnitureRecord
import dev.slne.surf.polarpaper.api.settings.BuiltInHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class CraftEngineHandlersTest {
    private class FakeCraftEngine : CraftEngineFurnitureApi {
        val ids = mutableMapOf<Entity, String>()
        val colliders = mutableSetOf<Entity>()
        val seats = mutableSetOf<Entity>()
        val states = mutableMapOf<Entity, ByteArray>()
        val extras = mutableMapOf<Entity, ByteArray>()
        val placed = mutableListOf<Place>()
        val appliedExtras = mutableListOf<ByteArray>()
        var known = setOf<String>()
        val mappings = mutableMapOf<String, String>()
        var failExtra: RuntimeException? = null
        val entity = mockk<Entity>(relaxed = true)

        class Place(val id: String, val location: Location, val state: ByteArray?)

        override fun furnitureId(entity: Entity) = ids[entity]
        override fun isCollider(entity: Entity) = entity in colliders
        override fun isSeat(entity: Entity) = entity in seats
        override fun stateBytes(entity: Entity) = states[entity]
        override fun extraBytes(entity: Entity) = extras[entity]

        override fun place(id: String, location: Location, state: ByteArray?): Entity? {
            if (id !in known) return null
            placed += Place(id, location, state)
            return entity
        }

        override fun resolveId(id: String) = mappings[id] ?: id
        override fun syncCustomBlocks() = true

        override fun applyExtra(entity: Entity, bytes: ByteArray) {
            failExtra?.let { throw it }
            appliedExtras += bytes
        }
    }

    private val worldId = UUID.randomUUID()
    private val world = mockk<World>(relaxed = true) { every { uid } returns worldId }
    private val chunk = mockk<Chunk>(relaxed = true) {
        every { this@mockk.world } returns this@CraftEngineHandlersTest.world
        every { x } returns 4
        every { z } returns -2
    }
    private val craftEngine = FakeCraftEngine()
    private var live = true
    private val queued = mutableListOf<Runnable>()
    private val handler = CraftEngineFurnitureHandler({ _, task -> task.run() }, { true }, craftEngine, { live })
    private val deferred = CraftEngineFurnitureHandler({ _, task -> queued += task }, { true }, craftEngine, { live })

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun furniture(x: Double) = mockk<Entity>(relaxed = true) {
        every { location } returns Location(world, x, 64.5, 3.5, 90f, 10f)
    }

    @Test
    fun `captures and claims craftengine furniture as neutral records`() {
        val chair = furniture(1.5)
        val table = furniture(2.5)
        val collider = mockk<Entity>(relaxed = true)
        val seat = mockk<Entity>(relaxed = true)
        val other = mockk<Entity>(relaxed = true)
        craftEngine.ids[chair] = "default:chair"
        craftEngine.ids[table] = "default:table"
        craftEngine.colliders += collider
        craftEngine.seats += seat
        craftEngine.states[chair] = byteArrayOf(1, 2, 3)
        craftEngine.extras[chair] = byteArrayOf(4, 5)
        val claimed = mutableListOf<Entity>()

        val records = handler.capture(chunk, listOf(chair, collider, other, seat, table)) { claimed += it }

        assertEquals(listOf(chair, collider, seat, table), claimed)
        assertEquals(
            listOf(
                FurnitureRecord(
                    "default:chair", 1.5, 64.5, 3.5, 90f, 10f,
                    mapOf(
                        "data" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                        "pdc" to Base64.getEncoder().encodeToString(byteArrayOf(4, 5))
                    )
                ),
                FurnitureRecord("default:table", 2.5, 64.5, 3.5, 90f, 10f)
            ),
            records
        )
    }

    @Test
    fun `capture stores no section without furniture`() {
        assertNull(handler.capture(chunk, listOf(mockk<Entity>(relaxed = true))) { })
    }

    @Test
    fun `restores furniture by id`() {
        craftEngine.known = setOf("default:chair")
        val properties = mapOf(
            "data" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            "pdc" to Base64.getEncoder().encodeToString(byteArrayOf(9, 8))
        )

        handler.restore(chunk, listOf(FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f, properties)))

        val placed = craftEngine.placed.single()
        assertEquals("default:chair", placed.id)
        assertEquals(Triple(1.5, 64.5, 3.5), Triple(placed.location.x, placed.location.y, placed.location.z))
        assertEquals(Pair(90f, 10f), Pair(placed.location.yaw, placed.location.pitch))
        assertEquals(world, placed.location.world)
        assertArrayEquals(byteArrayOf(1, 2, 3), placed.state)
        assertArrayEquals(byteArrayOf(9, 8), craftEngine.appliedExtras.single())
    }

    @Test
    fun `restore skips and keeps pending furniture craftengine does not know`() {
        val gone = FurnitureRecord("gone", 0.0, 0.0, 0.0, 0f, 0f)

        deferred.restore(chunk, listOf(gone))
        queued.single().run()

        assertTrue(craftEngine.placed.isEmpty())
        repeat(3) { assertEquals(listOf(gone), deferred.capture(chunk, emptyList()) { }) }
    }

    @Test
    fun `a save before the deferred placement keeps the restored records`() {
        val records = listOf(FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f))

        deferred.restore(chunk, records)

        assertEquals(records, deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `the deferred placement removes placed records and keeps failed ones pending`() {
        craftEngine.known = setOf("default:chair")
        val chair = FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f)
        val gone = FurnitureRecord("gone", 2.5, 64.5, 3.5, 0f, 0f)

        deferred.restore(chunk, listOf(chair, gone))
        queued.single().run()

        assertEquals(listOf("default:chair"), craftEngine.placed.map { it.id })
        assertEquals(listOf(gone), deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `unloading the chunk releases its pending records`() {
        deferred.restore(chunk, listOf(FurnitureRecord("gone", 2.5, 64.5, 3.5, 0f, 0f)))

        deferred.chunkUnloaded(4, -2)

        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `a task of an older restore does not place again after a reload`() {
        craftEngine.known = setOf("default:chair")
        val chair = FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f)

        deferred.restore(chunk, listOf(chair))
        deferred.chunkUnloaded(4, -2)
        deferred.restore(chunk, listOf(chair))
        queued.forEach { it.run() }

        assertEquals(1, craftEngine.placed.size)
    }

    @Test
    fun `a record is not emitted twice when reapplying its extra data fails`() {
        craftEngine.known = setOf("default:chair")
        craftEngine.failExtra = IllegalStateException("boom")
        val properties = mapOf("pdc" to Base64.getEncoder().encodeToString(byteArrayOf(1)))

        deferred.restore(chunk, listOf(FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f, properties)))
        queued.single().run()

        assertEquals(1, craftEngine.placed.size)
        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `the deferred placement keeps everything pending when the chunk is not live`() {
        craftEngine.known = setOf("default:chair")
        val chair = FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f)
        live = false

        deferred.restore(chunk, listOf(chair))
        queued.single().run()

        assertTrue(craftEngine.placed.isEmpty())
        assertEquals(listOf(chair), deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `unplugged craftengine registers an unavailable stub with the same type`() {
        val manager = mockk<PluginManager> { every { isPluginEnabled("CraftEngine") } returns false }
        mockkStatic(Bukkit::class)
        every { Bukkit.getPluginManager() } returns manager

        val handlers = BuiltInHandlers.resolve(setOf(BuiltInHandler.CRAFT_ENGINE), mockk(relaxed = true))

        assertEquals(1, handlers.size)
        assertFalse(handlers.single().isAvailable())
        assertEquals(BuiltInData.CRAFT_ENGINE_FURNITURE, handlers.single().type)
    }

    @Test
    fun `unavailable without craftengine`() {
        assertFalse(CraftEngineFurnitureHandler({ _, _ -> }, { false }, craftEngine, { true }).isAvailable())
        assertTrue(handler.isAvailable())
    }

    @Test
    fun `applies craftengine id mappings and drops records mapped to removal`() {
        craftEngine.known = setOf("default:new_chair")
        craftEngine.mappings["default:old_chair"] = "default:new_chair"
        craftEngine.mappings["default:removed"] = ""

        deferred.restore(
            chunk,
            listOf(
                FurnitureRecord("default:old_chair", 1.5, 64.5, 3.5, 0f, 0f),
                FurnitureRecord("default:removed", 2.5, 64.5, 3.5, 0f, 0f)
            )
        )
        queued.single().run()

        assertEquals(listOf("default:new_chair"), craftEngine.placed.map { it.id })
        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `claims a collider and a seat whose base entity is not in the list`() {
        val collider = mockk<Entity>(relaxed = true)
        val seat = mockk<Entity>(relaxed = true)
        craftEngine.colliders += collider
        craftEngine.seats += seat
        val claimed = mutableListOf<Entity>()

        assertNull(handler.capture(chunk, listOf(collider, seat)) { claimed += it })

        assertEquals(listOf(collider, seat), claimed)
    }

    @Test
    fun `a piece spawned before onPlace threw or returned null counts as placed`() {
        val spawned = mockk<Entity>(relaxed = true)

        assertEquals(spawned, placeRecovering({ throw IllegalStateException("onPlace") }) { spawned })
        assertEquals(spawned, placeRecovering({ null }) { spawned })
        assertNull(placeRecovering({ null }) { null })
        assertTrue(runCatching { placeRecovering({ throw IllegalStateException("x") }) { null } }.isFailure)
    }

    @Test
    fun `a chunk that is not live yet is retried until it becomes live`() {
        craftEngine.known = setOf("default:chair")
        val record = FurnitureRecord("default:chair", 1.5, 64.5, 3.5, 90f, 10f)
        live = false

        deferred.restore(chunk, listOf(record))
        queued.removeFirst().run()
        queued.removeFirst().run()
        assertTrue(craftEngine.placed.isEmpty())

        live = true
        queued.removeFirst().run()

        assertEquals(listOf("default:chair"), craftEngine.placed.map { it.id })
        assertNull(deferred.capture(chunk, emptyList()) { })
    }
}
