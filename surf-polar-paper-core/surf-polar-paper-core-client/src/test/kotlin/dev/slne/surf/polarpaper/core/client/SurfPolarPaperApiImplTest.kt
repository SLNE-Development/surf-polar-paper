package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.fakes.FakePlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.fakes.InMemoryStorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class SurfPolarPaperApiImplTest {
    private val storage = InMemoryStorageProvider()
    private val bridge = FakePlatformWorldBridge()
    private val api = SurfPolarPaperApiImpl({ storage }, { bridge })

    private fun store(uuid: UUID, gameMode: String, worldBytes: ByteArray) {
        storage.stored[WorldCacheKey(uuid, gameMode)] = WorldStorageCodec.encode(worldBytes)
    }

    private fun storedWorldBytes(uuid: UUID, gameMode: String): ByteArray? =
        storage.stored[WorldCacheKey(uuid, gameMode)]?.let(WorldStorageCodec::decode)

    @Test
    fun `loadWorld decodes the stored bytes, loads the platform world and caches it`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1, 2, 3))

        val world = api.loadWorld(uuid, "survival")

        assertArrayEquals(byteArrayOf(1, 2, 3), world.worldBytes)
        assertEquals(listOf("load:$uuid"), bridge.calls)
        assertSame(world, api.findWorld(uuid, "survival"))
        assertTrue(world in api.loadedWorlds)
    }

    @Test
    fun `loadWorld returns the cached world without reading storage again`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))

        val first = api.loadWorld(uuid, "survival")
        val second = api.loadWorld(uuid, "survival")

        assertSame(first, second)
        assertEquals(1, storage.loads)
        assertEquals(1, bridge.count("load:"))
    }

    @Test
    fun `loadWorld fails for a world that does not exist and caches nothing`() = runTest {
        val uuid = UUID.randomUUID()

        assertThrows<IllegalStateException> { api.loadWorld(uuid, "survival") }

        assertNull(api.findWorld(uuid, "survival"))
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `loadWorld does not cache a world whose platform load failed`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        bridge.failOnLoad = RuntimeException("corrupt")

        val exception = assertThrows<IllegalStateException> { api.loadWorld(uuid, "survival") }

        assertEquals("corrupt", exception.cause?.message)
        assertNull(api.findWorld(uuid, "survival"))
    }

    @Test
    fun `invalid game mode identifiers are rejected before storage is touched`() = runTest {
        assertThrows<IllegalArgumentException> { api.loadWorld(UUID.randomUUID(), "../escape") }
        assertThrows<IllegalArgumentException> { api.createWorld(UUID.randomUUID(), "a/b", ByteArray(0)) }
        assertThrows<IllegalArgumentException> { api.listWorlds("") }

        assertEquals(0, storage.loads)
        assertEquals(0, storage.writes)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `createWorld creates the platform world, stores it encoded and caches it`() = runTest {
        val uuid = UUID.randomUUID()

        val world = api.createWorld(uuid, "survival", byteArrayOf(7, 7))

        assertEquals(listOf("create:$uuid", "save:$uuid"), bridge.calls)
        assertArrayEquals(byteArrayOf(7, 7), storedWorldBytes(uuid, "survival"))
        assertSame(world, api.findWorld(uuid, "survival"))
    }

    @Test
    fun `saveWorld stores the bytes produced by the platform save`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(4, 5, 6) }

        api.saveWorld(world)

        assertArrayEquals(byteArrayOf(4, 5, 6), world.worldBytes)
        assertArrayEquals(byteArrayOf(4, 5, 6), storedWorldBytes(uuid, "survival"))
    }

    @Test
    fun `unloadWorld with saveChanges saves exactly once and removes the world from the cache`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(2) }

        api.unloadWorld(world, saveChanges = true)

        assertEquals(1, bridge.count("save:"))
        assertEquals(1, bridge.count("unload:"))
        assertArrayEquals(byteArrayOf(2), storedWorldBytes(uuid, "survival"))
        assertNull(api.findWorld(uuid, "survival"))
        assertTrue(api.loadedWorlds.isEmpty())
    }

    @Test
    fun `unloadWorld without saveChanges neither saves nor writes`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")

        api.unloadWorld(world, saveChanges = false)

        assertEquals(0, bridge.count("save:"))
        assertEquals(0, storage.writes)
        assertNull(api.findWorld(uuid, "survival"))
    }

    @Test
    fun `deleteWorld removes a world that is not loaded without saving it`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))

        api.deleteWorld(SurfWorld(uuid, "survival", ByteArray(0)))

        assertTrue(storage.stored.isEmpty())
        assertEquals(0, bridge.count("save:"))
    }

    @Test
    fun `deleteWorld unloads a loaded world and removes it from storage`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")

        api.deleteWorld(world)

        assertTrue(storage.stored.isEmpty())
        assertTrue(bridge.platformWorlds.isEmpty())
        assertNull(api.findWorld(uuid, "survival"))
    }

    @Test
    fun `unloadAllWorlds keeps going after a failure and reports every failure`() = runTest {
        val uuids = List(3) { UUID.randomUUID() }
        uuids.forEach { store(it, "survival", byteArrayOf(1)) }
        val worlds = uuids.map { api.loadWorld(it, "survival") }
        bridge.failingUnloads += worlds[1]
        val unloadedByHook = mutableListOf<SurfWorld>()

        val exception = assertThrows<IllegalStateException> {
            api.unloadAllWorlds(saveChanges = false, afterHook = { unloadedByHook += it })
        }

        assertEquals(1, exception.suppressed.size)
        assertEquals(setOf(worlds[0], worlds[2]), unloadedByHook.toSet())
        assertEquals(listOf(worlds[1]), api.loadedWorlds.toList())
    }

    @Test
    fun `unloadAllWorlds unloads every world and calls both hooks`() = runTest {
        val uuids = List(2) { UUID.randomUUID() }
        uuids.forEach { store(it, "survival", byteArrayOf(1)) }
        uuids.forEach { api.loadWorld(it, "survival") }
        val before = mutableListOf<SurfWorld>()
        val after = mutableListOf<SurfWorld>()

        api.unloadAllWorlds(saveChanges = true, beforeHook = { before += it }, afterHook = { after += it })

        assertEquals(2, before.size)
        assertEquals(before.toSet(), after.toSet())
        assertEquals(2, bridge.count("save:"))
        assertTrue(api.loadedWorlds.isEmpty())
    }

    @Test
    fun `saveAllWorlds saves every loaded world and keeps them loaded`() = runTest {
        val uuids = List(2) { UUID.randomUUID() }
        uuids.forEach { store(it, "survival", byteArrayOf(1)) }
        uuids.forEach { api.loadWorld(it, "survival") }
        bridge.savedBytes = { byteArrayOf(5) }

        api.saveAllWorlds()

        uuids.forEach { assertArrayEquals(byteArrayOf(5), storedWorldBytes(it, "survival")) }
        assertEquals(0, bridge.count("unload:"))
        assertEquals(2, api.loadedWorlds.size)
    }

    @Test
    fun `saveAllWorlds keeps going after a failure and reports every failure`() = runTest {
        val uuids = List(3) { UUID.randomUUID() }
        uuids.forEach { store(it, "survival", byteArrayOf(1)) }
        val worlds = uuids.map { api.loadWorld(it, "survival") }
        bridge.savedBytes = { world -> if (world == worlds[1]) error("Polar failed") else byteArrayOf(5) }

        val exception = assertThrows<IllegalStateException> { api.saveAllWorlds() }

        assertEquals(1, exception.suppressed.size)
        assertArrayEquals(byteArrayOf(5), storedWorldBytes(uuids[0], "survival"))
        assertArrayEquals(byteArrayOf(5), storedWorldBytes(uuids[2], "survival"))
        assertArrayEquals(byteArrayOf(1), storedWorldBytes(uuids[1], "survival"))
    }

    @Test
    fun `listWorlds returns loaded worlds of the game mode as WorldInfo`() = runTest {
        val survival = UUID.randomUUID()
        val creative = UUID.randomUUID()
        val storedOnly = UUID.randomUUID()
        store(survival, "survival", byteArrayOf(1))
        store(creative, "creative", byteArrayOf(1))
        store(storedOnly, "survival", byteArrayOf(1))
        api.loadWorld(survival, "survival")
        api.loadWorld(creative, "creative")

        assertEquals(listOf(WorldInfo(survival, "survival")), api.listWorlds("survival", showLoadedOnly = true))
        assertEquals(
            setOf(WorldInfo(survival, "survival"), WorldInfo(storedOnly, "survival")),
            api.listWorlds("survival", showLoadedOnly = false).toSet()
        )
    }

    @Test
    fun `operations that need storage fail when no storage provider is connected`() = runTest {
        val disconnected = SurfPolarPaperApiImpl({ null }, { bridge })

        assertThrows<IllegalStateException> { disconnected.loadWorld(UUID.randomUUID(), "survival") }
        assertThrows<IllegalStateException> { disconnected.listWorlds("survival") }
    }
}
