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
        var failState: RuntimeException? = null
        val display = mockk<ItemDisplay>(relaxed = true)

        data class Place(val id: String, val location: Location, val yaw: Float, val face: BlockFace)

        override fun furnitureId(entity: Entity) = ids[entity]
        override fun hasToggleableLight(display: ItemDisplay) = false
        override fun lightState(display: ItemDisplay) = false
        override fun dye(display: ItemDisplay) = dyes[display]
        override fun seats(display: ItemDisplay) = seatsOf[display].orEmpty()
        override fun isSeat(entity: Entity) = entity in seatEntities
        override fun placementFace(display: ItemDisplay) = faces[display] ?: BlockFace.UP
        override fun placementBlock(display: ItemDisplay) = BlockPos(display.location.blockX, 64, 3)
        override fun stateBytes(display: ItemDisplay) = states[display]
        override fun applyState(display: ItemDisplay, bytes: ByteArray) {
            failState?.let { throw it }
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
                        "block" to "1,64,3",
                        "pdc" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                        "dye" to Color.fromARGB(255, 10, 20, 30).asARGB().toString()
                    )
                ),
                FurnitureRecord("table", 2.5, 64.5, 3.5, 90f, 10f, mapOf("face" to "NORTH", "block" to "2,64,3"))
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
    }

    @Test
    fun `an unplaceable record survives any number of saves`() {
        val gone = FurnitureRecord("gone", 2.5, 64.5, 3.5, 0f, 0f)

        deferred.restore(chunk, listOf(gone))
        queued.single().run()

        repeat(3) { assertEquals(listOf(gone), deferred.capture(chunk, emptyList()) { }) }
    }

    @Test
    fun `unloading the chunk releases its pending records`() {
        deferred.restore(chunk, listOf(FurnitureRecord("gone", 2.5, 64.5, 3.5, 0f, 0f)))

        deferred.chunkUnloaded(4, -2)

        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `a task of an older restore does not place again after a reload`() {
        nexo.known = setOf("oak_chair")
        val chair = FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f)

        deferred.restore(chunk, listOf(chair))
        deferred.chunkUnloaded(4, -2)
        deferred.restore(chunk, listOf(chair))
        queued.forEach { it.run() }

        assertEquals(1, nexo.placed.size)
    }

    @Test
    fun `a record is not emitted twice when reapplying its state fails`() {
        nexo.known = setOf("oak_chair")
        nexo.failState = IllegalStateException("boom")
        val properties = mapOf("pdc" to Base64.getEncoder().encodeToString(byteArrayOf(1)))

        deferred.restore(chunk, listOf(FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f, properties)))
        queued.single().run()

        assertEquals(1, nexo.placed.size)
        assertNull(deferred.capture(chunk, emptyList()) { })
    }

    @Test
    fun `restore places from the center of the stored block`() {
        nexo.known = setOf("lamp")

        handler.restore(chunk, listOf(FurnitureRecord("lamp", 1.5, 70.2, 3.5, 0f, 0f, mapOf("block" to "1,64,3"))))

        val location = nexo.placed.single().location
        assertEquals(Triple(1.5, 64.5, 3.5), Triple(location.x, location.y, location.z))
    }

    @Test
    fun `inverts the spawn correction for floor roof and wall furniture`() {
        val air = { _: Int, _: Int, _: Int -> false }
        // Floor, block center and fixed transform at the block bottom
        assertEquals(BlockPos(1, 64, 3), invertSpawnCorrection(1.5, 64.5, 3.5, BlockFace.UP, false, false, false, 1f, air))
        assertEquals(BlockPos(1, 64, 3), invertSpawnCorrection(1.5, 64.0, 3.5, BlockFace.UP, false, false, true, 1f, air))
        // Roof furniture is raised by 0.49
        assertEquals(BlockPos(1, 64, 3), invertSpawnCorrection(1.5, 64.5 + 0.49, 3.5, BlockFace.DOWN, false, true, false, 1f, air))
        // Fixed wall furniture facing south is moved against its wall by 0.98 * scale, which leaves its block for scale 0.6
        val shift = 0.49 * 0.6 * 2
        assertEquals(BlockPos(1, 64, 3), invertSpawnCorrection(1.5, 64.5, 3.5 - shift, BlockFace.SOUTH, true, false, true, 0.6f, air))
        assertEquals(BlockPos(1, 64, 3), invertSpawnCorrection(1.5, 64.5, 3.5, BlockFace.SOUTH, true, false, true, 0.6f) { _, _, _ -> true })
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
    fun `derives the face of fixed furniture from nexo's pitch and yaw`() {
        fun face(wall: Boolean, roof: Boolean, floor: Boolean, pitch: Float, yaw: Float) =
            deriveFace(null, wall, roof, floor, fixed = true, pitch = pitch, yaw = yaw)
        // roof furniture on a ceiling
        assertEquals(BlockFace.DOWN, face(wall = false, roof = true, floor = false, pitch = 90f, yaw = 0f))
        assertEquals(BlockFace.DOWN, face(wall = false, roof = true, floor = true, pitch = 90f, yaw = 0f))
        // wall+roof on a north wall: pitch 0, yaw 90 * ordinal - 180
        assertEquals(BlockFace.NORTH, face(wall = true, roof = true, floor = false, pitch = 0f, yaw = 180f))
        // wall+floor on a wall, and on the floor
        assertEquals(BlockFace.WEST, face(wall = true, roof = false, floor = true, pitch = 0f, yaw = 90f))
        assertEquals(BlockFace.EAST, face(wall = true, roof = false, floor = true, pitch = 0f, yaw = -90f))
        assertEquals(BlockFace.UP, face(wall = true, roof = false, floor = true, pitch = -90f, yaw = 33f))
        // wall+roof+floor on a wall
        assertEquals(BlockFace.SOUTH, face(wall = true, roof = true, floor = true, pitch = 0f, yaw = 0f))
        // wall-only on a wall
        assertEquals(BlockFace.WEST, face(wall = true, roof = false, floor = false, pitch = 0f, yaw = 90f))
        // floor furniture on the floor
        assertEquals(BlockFace.UP, face(wall = false, roof = false, floor = true, pitch = -90f, yaw = 0f))
        assertEquals(BlockFace.UP, face(wall = false, roof = false, floor = false, pitch = 0f, yaw = 0f))
    }

    @Test
    fun `derives the face of non-fixed furniture from single flags only`() {
        fun face(wall: Boolean, roof: Boolean, floor: Boolean, yaw: Float = 0f) =
            deriveFace(null, wall, roof, floor, fixed = false, pitch = 0f, yaw = yaw)
        assertEquals(BlockFace.DOWN, face(wall = false, roof = true, floor = false))
        assertEquals(BlockFace.WEST, face(wall = true, roof = false, floor = false, yaw = 90f))
        assertEquals(BlockFace.UP, face(wall = false, roof = false, floor = true))
        assertEquals(BlockFace.UP, face(wall = false, roof = false, floor = false))
        // mixed flags
        assertEquals(BlockFace.UP, face(wall = true, roof = true, floor = false, yaw = 90f))
        assertEquals(BlockFace.UP, face(wall = true, roof = false, floor = true, yaw = 90f))
        assertEquals(BlockFace.UP, face(wall = false, roof = true, floor = true))
        assertEquals(BlockFace.UP, face(wall = true, roof = true, floor = true, yaw = 90f))
    }

    @Test
    fun `a stored placement type wins over the derived face`() {
        assertEquals(
            BlockFace.UP, deriveFace("FLOOR", wall = false, roof = true, floor = false, fixed = true, pitch = 90f, yaw = 0f)
        )
        assertEquals(
            BlockFace.DOWN, deriveFace("ROOF", wall = true, roof = false, floor = false, fixed = false, pitch = 0f, yaw = 0f)
        )
        assertEquals(
            BlockFace.EAST, deriveFace("WALL", wall = false, roof = false, floor = true, fixed = true, pitch = -90f, yaw = -90f)
        )
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
