package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.polarpaper.api.world.SurfWorld
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import live.minehub.polarpaper.Polar
import live.minehub.polarpaper.core.source.BytesPolarSource
import live.minehub.polarpaper.core.source.PolarSource
import org.bukkit.Bukkit
import org.bukkit.World
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class PlatformWorldBridgePaperTest {
    private val globalRegionExecutor = Executors.newSingleThreadExecutor { Thread(it, "global-region") }
    private val bridge = PlatformWorldBridgePaper { globalRegionExecutor.asCoroutineDispatcher() }

    private val world = SurfWorld(UUID.randomUUID(), "survival", byteArrayOf(1, 2, 3))
    private val platformWorld = mockk<World>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Bukkit::class, Polar::class)
        every { Bukkit.getWorld(any<String>()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        globalRegionExecutor.shutdownNow()
    }

    private fun worldIsLoaded() {
        every { Bukkit.getWorld(world.name) } returns platformWorld
    }

    @Test
    fun `loadWorld creates the polar world from the world bytes`() = runTest {
        var loadedBytes: ByteArray? = null
        every { Polar.createWorld(any<PolarSource>(), world.name) } answers {
            loadedBytes = (firstArg<PolarSource>() as BytesPolarSource).bytes()
            CompletableFuture.completedFuture(platformWorld)
        }

        bridge.loadWorld(world)

        assertArrayEquals(byteArrayOf(1, 2, 3), loadedBytes)
    }

    @Test
    fun `loadWorld does nothing when the world is already loaded`() = runTest {
        worldIsLoaded()

        bridge.loadWorld(world)

        verify(exactly = 0) { Polar.createWorld(any<PolarSource>(), any<String>()) }
    }

    @Test
    fun `loadWorld fails with an IllegalStateException when polar returns no world`() = runTest {
        every { Polar.createWorld(any<PolarSource>(), world.name) } returns CompletableFuture.completedFuture(null)

        assertThrows<IllegalStateException> { bridge.loadWorld(world) }
    }

    @Test
    fun `createWorld loads the world`() = runTest {
        every { Polar.createWorld(any<PolarSource>(), world.name) } returns CompletableFuture.completedFuture(platformWorld)

        bridge.createWorld(world)

        verify(exactly = 1) { Polar.createWorld(any<PolarSource>(), world.name) }
    }

    @Test
    fun `saveWorld stores the polar bytes in the world`() = runTest {
        worldIsLoaded()
        every { Polar.saveWorld(platformWorld, any<PolarSource>()) } answers {
            (secondArg<PolarSource>() as BytesPolarSource).bytes(byteArrayOf(9, 9))
            CompletableFuture.completedFuture(null)
        }

        bridge.saveWorld(world)

        assertArrayEquals(byteArrayOf(9, 9), world.worldBytes)
    }

    @Test
    fun `saveWorld fails for a world that is not loaded`() = runTest {
        assertThrows<IllegalStateException> { bridge.saveWorld(world) }
    }

    @Test
    fun `unloadWorld unloads on the global region without saving`() = runTest {
        worldIsLoaded()
        var unloadThread: String? = null
        every { Bukkit.unloadWorld(platformWorld, false) } answers {
            unloadThread = Thread.currentThread().name
            true
        }

        bridge.unloadWorld(world)

        assertEquals("global-region", unloadThread)
        verify(exactly = 0) { Polar.saveWorld(any(), any<PolarSource>()) }
    }

    @Test
    fun `unloadWorld does nothing for a world that is not loaded`() = runTest {
        bridge.unloadWorld(world)

        verify(exactly = 0) { Bukkit.unloadWorld(any<World>(), any()) }
    }

    @Test
    fun `unloadWorld fails when bukkit refuses to unload`() = runTest {
        worldIsLoaded()
        every { Bukkit.unloadWorld(platformWorld, false) } returns false

        assertThrows<IllegalStateException> { bridge.unloadWorld(world) }
    }

    @Test
    fun `getPlatformWorld looks the world up by name`() {
        worldIsLoaded()

        assertSame(platformWorld, bridge.getPlatformWorld<World>(world))
    }
}
