package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import dev.slne.surf.polarpaper.core.client.fakes.FakePlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.fakes.InMemoryStorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SurfPolarPaperApiImplTest {
    private val storage = InMemoryStorageProvider()
    private val bridge = FakePlatformWorldBridge()
    private val plugin = mockk<Plugin> { every { name } returns "TestPlugin" }

    private fun migration(to: Int) = object : WorldMigration(to) {}

    private fun settings(currentVersion: Int = 3, autosave: Boolean = false): PolarPaperSettings = polarPaperSettings {
        gameMode("survival") {
            version = currentVersion
            (2..currentVersion).forEach { migration(migration(it)) }
        }
        if (autosave) autosave(10.seconds)
    }

    private fun api(settings: PolarPaperSettings = settings()) =
        SurfPolarPaperApiImpl(plugin, settings, { storage }, { bridge })

    private val api = api()

    @AfterEach
    fun cleanRegistry() {
        ApiInstances.all.forEach { ApiInstances.unregister(it) }
    }

    private fun store(uuid: UUID, gameMode: String, worldBytes: ByteArray, version: Int = 3) {
        storage.stored[WorldCacheKey(uuid, gameMode)] = WorldStorageCodec.encode(version, worldBytes)
    }

    private fun storedWorldBytes(uuid: UUID, gameMode: String): ByteArray? =
        storage.stored[WorldCacheKey(uuid, gameMode)]?.let { WorldStorageCodec.decode(it).worldBytes }

    private fun storedVersion(uuid: UUID, gameMode: String): Int? =
        storage.stored[WorldCacheKey(uuid, gameMode)]?.let { WorldStorageCodec.decode(it).worldVersion }

    @Test
    fun `loading a world stored at the current version runs no migrations and does not save`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1, 2, 3), version = 3)

        val world = api.loadWorld(uuid, "survival")

        assertEquals(listOf("load:$uuid:[]"), bridge.calls)
        assertEquals(0, storage.writes)
        assertEquals(3, world.version)
        assertSame(world, api.findWorld(uuid, "survival"))
        assertTrue(world in api.loadedWorlds)
    }

    @Test
    fun `loading an older world passes the planned migrations and saves at the current version`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1), version = 1)
        bridge.savedBytes = { byteArrayOf(9) }

        val world = api.loadWorld(uuid, "survival")

        assertEquals(listOf("load:$uuid:[2, 3]", "save:$uuid"), bridge.calls)
        assertEquals(3, world.version)
        assertEquals(3, storedVersion(uuid, "survival"))
        assertArrayEquals(byteArrayOf(9), storedWorldBytes(uuid, "survival"))
    }

    @Test
    fun `loading a newer world throws and leaves storage untouched and the lock released`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1), version = 4)
        val before = storage.stored.getValue(WorldCacheKey(uuid, "survival")).copyOf()
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })

        assertThrows<WorldVersionTooNewException> { api.loadWorld(uuid, "survival") }

        assertArrayEquals(before, storage.stored.getValue(WorldCacheKey(uuid, "survival")))
        assertEquals(0, storage.writes)
        assertTrue(bridge.calls.isEmpty())
        assertTrue(locks.held.isEmpty())
        assertEquals(1, locks.unlocks)
    }

    @Test
    fun `a failing bridge load leaves storage byte-identical, releases the lock and is not cached`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1), version = 1)
        val before = storage.stored.getValue(WorldCacheKey(uuid, "survival")).copyOf()
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        bridge.failOnLoad = RuntimeException("corrupt")

        val exception = assertThrows<IllegalStateException> { api.loadWorld(uuid, "survival") }

        assertEquals("corrupt", exception.cause?.message)
        assertArrayEquals(before, storage.stored.getValue(WorldCacheKey(uuid, "survival")))
        assertTrue(locks.held.isEmpty())
        assertNull(api.findWorld(uuid, "survival"))
        assertEquals(1, bridge.count("unload:"))
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
    fun `create stamps the current version unless a template version is given`() = runTest {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        api.createWorld(first, "survival")
        api.createWorld(second, "survival", byteArrayOf(5), worldVersion = 1)

        assertEquals("load:$first:[]", bridge.calls.first { it.startsWith("load:$first") })
        assertEquals("load:$second:[2, 3]", bridge.calls.first { it.startsWith("load:$second") })
        assertEquals(3, storedVersion(first, "survival"))
        assertEquals(3, storedVersion(second, "survival"))
        assertSame(api.findWorld(second, "survival")!!.api, api)
    }

    @Test
    fun `invalid game mode identifiers are rejected before storage is touched`() = runTest {
        assertThrows<IllegalArgumentException> { api.loadWorld(UUID.randomUUID(), "../escape") }
        assertThrows<IllegalArgumentException> { api.createWorld(UUID.randomUUID(), "a/b") }
        assertThrows<IllegalArgumentException> { api.listWorlds("") }

        assertEquals(0, storage.loads)
        assertEquals(0, storage.writes)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `an instance cannot load a game mode it does not own`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "creative", byteArrayOf(1))

        assertThrows<IllegalArgumentException> { api.loadWorld(uuid, "creative") }
        assertThrows<IllegalArgumentException> { api.createWorld(uuid, "creative") }
        assertThrows<IllegalArgumentException> { api.listWorlds("creative") }

        assertEquals(0, storage.loads)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun `creating a second instance for an owned game mode throws and registers nothing`() {
        val factory = SurfPolarPaperApiFactoryImpl()
        val other = mockk<Plugin> { every { name } returns "OtherPlugin" }
        val first = factory.create(plugin, settings())

        assertThrows<GameModeAlreadyOwnedException> {
            factory.create(
                other,
                polarPaperSettings {
                    gameMode("lobby") { version = 1 }
                    gameMode("survival") { version = 1 }
                }
            )
        }

        assertEquals(listOf(first), ApiInstances.all)
        assertSame(first, ApiInstances.ownerOf("survival"))
        // The claim for "lobby" made before the conflict was rolled back
        assertNull(ApiInstances.ownership.ownerOf("lobby"))
        assertEquals("TestPlugin", ApiInstances.ownership.ownerOf("survival"))
    }

    @Test
    fun `close saves and unloads own worlds and frees the game modes`() = runTest {
        val factory = SurfPolarPaperApiFactoryImpl()
        val created = factory.create(plugin, settings()) as SurfPolarPaperApiImpl
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(7) }

        closing.close()
        closing.close() // idempotent
        created.close()

        assertArrayEquals(byteArrayOf(7), storedWorldBytes(uuid, "survival"))
        assertEquals(1, bridge.count("unload:"))
        assertTrue(closing.loadedWorlds.isEmpty())
        assertNull(ApiInstances.ownership.ownerOf("survival"))
        assertTrue(ApiInstances.all.isEmpty())
    }

    @Test
    fun `autosave saves loaded worlds every interval`() = runTest {
        val api = SurfPolarPaperApiImpl(plugin, settings(autosave = true), { storage }, { bridge }, autosaveScope = backgroundScope)
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        api.loadWorld(uuid, "survival")

        advanceTimeBy(10.seconds + 1.seconds)
        assertEquals(1, bridge.count("save:"))

        advanceTimeBy(10.seconds)
        assertEquals(2, bridge.count("save:"))

        api.close()
        advanceTimeBy(30.seconds)
        assertEquals(3, bridge.count("save:")) // the save of close() only
    }

    @Test
    fun `saveWorld stores the bytes produced by the platform save with the world version`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(4, 5, 6) }

        api.saveWorld(world)

        assertArrayEquals(byteArrayOf(4, 5, 6), storedWorldBytes(uuid, "survival"))
        assertEquals(3, storedVersion(uuid, "survival"))
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

        api.deleteWorld(SurfWorldImpl(uuid, "survival", 0, api, bridge))

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
        val worlds = uuids.map { api.loadWorld(it, "survival") as SurfWorldImpl }
        bridge.failingUnloads += worlds[1]

        val exception = assertThrows<IllegalStateException> { api.unloadAllWorlds(saveChanges = false) }

        assertEquals(1, exception.suppressed.size)
        assertEquals(listOf(worlds[1]), api.loadedWorlds.toList())
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
        val worlds = uuids.map { api.loadWorld(it, "survival") as SurfWorldImpl }
        bridge.savedBytes = { world -> if (world == worlds[1]) error("Polar failed") else byteArrayOf(5) }

        val exception = assertThrows<IllegalStateException> { api.saveAllWorlds() }

        assertEquals(1, exception.suppressed.size)
        assertArrayEquals(byteArrayOf(5), storedWorldBytes(uuids[0], "survival"))
        assertArrayEquals(byteArrayOf(5), storedWorldBytes(uuids[2], "survival"))
        assertArrayEquals(byteArrayOf(1), storedWorldBytes(uuids[1], "survival"))
    }

    @Test
    fun `listWorlds returns loaded worlds of the game mode as WorldInfo`() = runTest {
        val loaded = UUID.randomUUID()
        val storedOnly = UUID.randomUUID()
        store(loaded, "survival", byteArrayOf(1))
        store(storedOnly, "survival", byteArrayOf(1))
        api.loadWorld(loaded, "survival")

        assertEquals(listOf(WorldInfo(loaded, "survival")), api.listWorlds("survival", showLoadedOnly = true))
        assertEquals(
            setOf(WorldInfo(loaded, "survival"), WorldInfo(storedOnly, "survival")),
            api.listWorlds("survival", showLoadedOnly = false).toSet()
        )
    }

    @Test
    fun `operations that need storage fail when no storage provider is connected`() = runTest {
        val disconnected = SurfPolarPaperApiImpl(plugin, settings(), { null }, { bridge })

        assertThrows<IllegalStateException> { disconnected.loadWorld(UUID.randomUUID(), "survival") }
        assertThrows<IllegalStateException> { disconnected.listWorlds("survival") }
    }
}
