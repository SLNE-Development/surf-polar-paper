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
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
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

class NexoFurnitureHandlerTest {
    private class FakeNexo : NexoFurnitureApi {
        val ids = mutableMapOf<Entity, String>()
        val dyes = mutableMapOf<ItemDisplay, Color>()
        val seatsOf = mutableMapOf<ItemDisplay, List<Entity>>()
        val seatEntities = mutableSetOf<Entity>()
        val faces = mutableMapOf<ItemDisplay, BlockFace>()
        val states = mutableMapOf<ItemDisplay, ByteArray>()
        val placed = mutableListOf<Place>()
        val appliedDyes = mutableListOf<Color>()
        val appliedStates = mutableListOf<ByteArray>()
        val toggled = mutableListOf<Boolean>()
        var known = setOf<String>()
        var failWith: RuntimeException? = null
        val display = mockk<ItemDisplay>(relaxed = true)

        data class Place(val id: String, val location: Location, val yaw: Float, val face: BlockFace)

        override fun furnitureId(entity: Entity) = ids[entity]
        override fun hasToggleableLight(display: ItemDisplay) = false
        override fun lightState(display: ItemDisplay) = false
        override fun dye(display: ItemDisplay) = dyes[display]
        override fun seats(display: ItemDisplay) = seatsOf[display].orEmpty()
        override fun isSeat(entity: Entity) = entity in seatEntities
        override fun placementFace(display: ItemDisplay) = faces[display] ?: BlockFace.UP
        override fun stateBytes(display: ItemDisplay) = states[display]
        override fun applyState(display: ItemDisplay, bytes: ByteArray) {
            appliedStates += bytes
        }

        override fun place(id: String, location: Location, yaw: Float, face: BlockFace): ItemDisplay? {
            failWith?.let { throw it }
            if (id !in known) return null
            placed += Place(id, location, yaw, face)
            return display
        }

        override fun dye(display: ItemDisplay, color: Color) {
            appliedDyes += color
        }

        override fun toggleLight(display: ItemDisplay, on: Boolean) {
            toggled += on
        }
    }

    private val worldId = UUID.randomUUID()
    private val world = mockk<World>(relaxed = true) { every { uid } returns worldId }
    private val chunk = mockk<Chunk>(relaxed = true) {
        every { this@mockk.world } returns this@NexoFurnitureHandlerTest.world
        every { x } returns 4
        every { z } returns -2
    }
    private val nexo = FakeNexo()
    private var live = true
    private val queued = mutableListOf<Runnable>()
    private val handler = NexoFurnitureHandler({ _, task -> task.run() }, { true }, nexo, { live })

    // A handler whose placement task only runs when the test says so
    private val deferred = NexoFurnitureHandler({ _, task -> queued += task }, { true }, nexo, { live })

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun display(x: Double) = mockk<ItemDisplay>(relaxed = true) {
        every { location } returns Location(world, x, 64.5, 3.5, 90f, 10f)
    }

    @Test
    fun `captures and claims nexo furniture as neutral records`() {
        val chair = display(1.5)
        val table = display(2.5)
        val other = mockk<Entity>(relaxed = true)
        nexo.ids[chair] = "oak_chair"
        nexo.ids[table] = "table"
        nexo.dyes[chair] = Color.fromARGB(255, 10, 20, 30)
        nexo.faces[table] = BlockFace.NORTH
        nexo.states[chair] = byteArrayOf(1, 2, 3)
        val claimed = mutableListOf<Entity>()

        val records = handler.capture(chunk, listOf(chair, other, table)) { claimed += it }

        assertEquals(listOf<Entity>(chair, table), claimed)
        assertEquals(
            listOf(
                FurnitureRecord(
                    "oak_chair", 1.5, 64.5, 3.5, 90f, 10f,
                    mapOf(
                        "face" to "UP",
                        "pdc" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                        "dye" to Color.fromARGB(255, 10, 20, 30).asARGB().toString()
                    )
                ),
                FurnitureRecord("table", 2.5, 64.5, 3.5, 90f, 10f, mapOf("face" to "NORTH"))
            ),
            records
        )
    }

    @Test
    fun `claims the seats of furniture and stray seat entities`() {
        val chair = display(1.5)
        val seat = mockk<Entity>(relaxed = true)
        val stray = mockk<Entity>(relaxed = true)
        nexo.ids[chair] = "oak_chair"
        nexo.seatsOf[chair] = listOf(seat)
        nexo.seatEntities += stray
        val claimed = mutableListOf<Entity>()

        handler.capture(chunk, listOf(chair, stray)) { claimed += it }

        assertTrue(seat in claimed)
        assertTrue(stray in claimed)
        assertTrue(chair in claimed)
    }

    @Test
    fun `capture stores no section without furniture`() {
        assertNull(handler.capture(chunk, listOf(mockk<Entity>(relaxed = true))) { })
    }

    @Test
    fun `restore places furniture by id at the stored position and rotation`() {
        nexo.known = setOf("oak_chair")
        val dye = Color.fromARGB(255, 10, 20, 30)
        val properties = mapOf(
            "dye" to dye.asARGB().toString(),
            "light" to "true",
            "face" to "WEST",
            "pdc" to Base64.getEncoder().encodeToString(byteArrayOf(9, 8))
        )

        handler.restore(chunk, listOf(FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f, properties)))

        val placed = nexo.placed.single()
        assertEquals("oak_chair", placed.id)
        assertEquals(Triple(1.5, 64.5, 3.5), Triple(placed.location.x, placed.location.y, placed.location.z))
        assertEquals(world, placed.location.world)
        assertEquals(90f, placed.yaw)
        assertEquals(BlockFace.WEST, placed.face)
        assertEquals(listOf(dye), nexo.appliedDyes)
        assertEquals(listOf(true), nexo.toggled)
        assertArrayEquals(byteArrayOf(9, 8), nexo.appliedStates.single())
    }

    @Test
    fun `restore skips furniture nexo does not know`() {
        handler.restore(chunk, listOf(FurnitureRecord("gone", 0.0, 0.0, 0.0, 0f, 0f)))

        assertTrue(nexo.placed.isEmpty())
    }

    @Test
    fun `a save before the deferred placement keeps the restored records`() {
        val records = listOf(FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f, mapOf("face" to "UP")))

        deferred.restore(chunk, records)

        assertEquals(records, deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `the deferred placement removes placed records and keeps failed ones pending`() {
        nexo.known = setOf("oak_chair")
        val chair = FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f)
        val gone = FurnitureRecord("gone", 2.5, 64.5, 3.5, 0f, 0f)

        deferred.restore(chunk, listOf(chair, gone))
        queued.single().run()

        assertEquals(listOf("oak_chair"), nexo.placed.map { it.id })
        assertEquals(listOf(gone), deferred.capture(chunk, emptyList()) { })
        // Written back once, so the entry is gone
        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `the deferred placement keeps everything pending when the chunk is not live`() {
        nexo.known = setOf("oak_chair")
        val chair = FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f)
        live = false

        deferred.restore(chunk, listOf(chair))
        queued.single().run()

        assertTrue(nexo.placed.isEmpty())
        assertEquals(listOf(chair), deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `a failing record stays pending and does not stop the others`() {
        nexo.known = setOf("oak_chair", "table")
        val chair = FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f)
        val table = FurnitureRecord("table", 2.5, 64.5, 3.5, 0f, 0f)
        nexo.failWith = IllegalStateException("boom")

        deferred.restore(chunk, listOf(chair, table))
        queued.single().run()
        assertEquals(listOf(chair, table), deferred.capture(chunk, emptyList()) { })

        nexo.failWith = null
        deferred.restore(chunk, listOf(chair, table))
        queued.last().run()
        assertEquals(listOf("oak_chair", "table"), nexo.placed.map { it.id })
    }

    @Test
    fun `unplugged nexo registers an unavailable stub with the same type`() {
        val manager = mockk<PluginManager> { every { isPluginEnabled("Nexo") } returns false }
        mockkStatic(Bukkit::class)
        every { Bukkit.getPluginManager() } returns manager

        val handlers = BuiltInHandlers.resolve(setOf(BuiltInHandler.NEXO_FURNITURE), mockk(relaxed = true))

        assertEquals(1, handlers.size)
        assertFalse(handlers.single().isAvailable())
        assertEquals(BuiltInData.NEXO_FURNITURE, handlers.single().type)
    }

    @Test
    fun `unavailable without nexo`() {
        assertFalse(NexoFurnitureHandler({ _, _ -> }, { false }, nexo, { true }).isAvailable())
        assertTrue(handler.isAvailable())
    }
}
