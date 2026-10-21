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
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexoFurnitureHandlerTest {
    private class FakeNexo : NexoFurnitureApi {
        val ids = mutableMapOf<Entity, String>()
        val dyes = mutableMapOf<ItemDisplay, Color>()
        val placed = mutableListOf<Triple<String, Location, Float>>()
        val appliedDyes = mutableListOf<Color>()
        val toggled = mutableListOf<Boolean>()
        var known = setOf<String>()
        val display = mockk<ItemDisplay>(relaxed = true)

        override fun furnitureId(entity: Entity) = ids[entity]
        override fun hasToggleableLight(display: ItemDisplay) = false
        override fun lightState(display: ItemDisplay) = false
        override fun dye(display: ItemDisplay) = dyes[display]
        override fun place(id: String, location: Location, yaw: Float): ItemDisplay? {
            if (id !in known) return null
            placed += Triple(id, location, yaw)
            return display
        }

        override fun dye(display: ItemDisplay, color: Color) {
            appliedDyes += color
        }

        override fun toggleLight(display: ItemDisplay, on: Boolean) {
            toggled += on
        }
    }

    private val world = mockk<World>(relaxed = true)
    private val chunk = mockk<Chunk>(relaxed = true) { every { this@mockk.world } returns this@NexoFurnitureHandlerTest.world }
    private val nexo = FakeNexo()
    private val handler = NexoFurnitureHandler({ _, task -> task.run() }, { true }, nexo)

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
        val claimed = mutableListOf<Entity>()

        val records = handler.capture(chunk, listOf(chair, other, table)) { claimed += it }

        assertEquals(listOf<Entity>(chair, table), claimed)
        assertEquals(
            listOf(
                FurnitureRecord(
                    "oak_chair", 1.5, 64.5, 3.5, 90f, 10f,
                    mapOf("dye" to Color.fromARGB(255, 10, 20, 30).asARGB().toString())
                ),
                FurnitureRecord("table", 2.5, 64.5, 3.5, 90f, 10f)
            ),
            records
        )
    }

    @Test
    fun `capture stores no section without furniture`() {
        assertNull(handler.capture(chunk, listOf(mockk<Entity>(relaxed = true))) { })
    }

    @Test
    fun `restore places furniture by id at the stored position and rotation`() {
        nexo.known = setOf("oak_chair")
        val dye = Color.fromARGB(255, 10, 20, 30)
        val properties = mapOf("dye" to dye.asARGB().toString(), "light" to "true")

        handler.restore(chunk, listOf(FurnitureRecord("oak_chair", 1.5, 64.5, 3.5, 90f, 10f, properties)))

        val (id, location, yaw) = nexo.placed.single()
        assertEquals("oak_chair", id)
        assertEquals(Triple(1.5, 64.5, 3.5), Triple(location.x, location.y, location.z))
        assertEquals(world, location.world)
        assertEquals(90f, yaw)
        assertEquals(listOf(dye), nexo.appliedDyes)
        assertEquals(listOf(true), nexo.toggled)
    }

    @Test
    fun `restore skips furniture nexo does not know`() {
        handler.restore(chunk, listOf(FurnitureRecord("gone", 0.0, 0.0, 0.0, 0f, 0f)))

        assertTrue(nexo.placed.isEmpty())
    }

    @Test
    fun `unavailable without nexo`() {
        val manager = mockk<PluginManager> { every { isPluginEnabled("Nexo") } returns false }
        mockkStatic(Bukkit::class)
        every { Bukkit.getPluginManager() } returns manager

        val handlers = BuiltInHandlers.resolve(setOf(BuiltInHandler.NEXO_FURNITURE), mockk(relaxed = true))

        assertEquals(1, handlers.size)
        assertFalse(handlers.single().isAvailable())
        assertEquals(BuiltInData.NEXO_FURNITURE, handlers.single().type)
        assertFalse(NexoFurnitureHandler({ _, _ -> }, { false }, nexo).isAvailable())
        assertTrue(handler.isAvailable())
    }
}
