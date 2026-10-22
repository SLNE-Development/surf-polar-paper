package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.migration.WorldMigration
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.api.settings.polarPaperSettings
import dev.slne.surf.polarpaper.api.world.GameModeAlreadyOwnedException
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.api.world.WorldVersionTooNewException
import dev.slne.surf.polarpaper.core.client.fakes.FakeLocks
import dev.slne.surf.polarpaper.core.client.fakes.FakePlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.fakes.InMemoryStorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.client.world.SurfWorldImpl
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
import kotlin.coroutines.CoroutineContext
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
    fun `close saves own worlds, unloads them, releases their locks and frees the game modes`() = runTest {
        val factory = SurfPolarPaperApiFactoryImpl()
        val created = factory.create(plugin, settings()) as SurfPolarPaperApiImpl
        val locks = FakeLocks()
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(7) }

        closing.close()
        closing.close() // idempotent
        created.close()

        assertArrayEquals(byteArrayOf(7), storedWorldBytes(uuid, "survival"))
        assertEquals(listOf("save:$uuid", "unload:$uuid"), bridge.calls.drop(1))
        assertTrue(world !in bridge.platformWorlds)
        assertTrue(locks.held.isEmpty())
        assertNull(ApiInstances.ownership.ownerOf("survival"))
        assertTrue(ApiInstances.all.isEmpty())
    }

    @Test
    fun `close keeps a world that bukkit refuses to unload saved, locked and loaded and still frees the game modes`() = runTest {
        val locks = FakeLocks()
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        ApiInstances.register(closing)
        val refused = UUID.randomUUID()
        val other = UUID.randomUUID()
        store(refused, "survival", byteArrayOf(1))
        store(other, "survival", byteArrayOf(1))
        val refusedWorld = closing.loadWorld(refused, "survival") as SurfWorldImpl
        closing.loadWorld(other, "survival")
        bridge.failingUnloads += refusedWorld
        bridge.savedBytes = { byteArrayOf(7) }

        closing.close()

        // Saved before the unload was tried, and the other world was not held back
        assertArrayEquals(byteArrayOf(7), storedWorldBytes(refused, "survival"))
        assertEquals(setOf(WorldCacheKey(refused, "survival")), locks.held)
        assertTrue(refusedWorld in bridge.platformWorlds)
        assertEquals(1, bridge.platformWorlds.size)
        assertTrue(ApiInstances.all.isEmpty())
    }

    @Test
    fun `close does not unload a world it could not save`() = runTest {
        val locks = FakeLocks()
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { error("cannot save") }

        assertThrows<IllegalStateException> { closing.close() }

        assertEquals(0, bridge.count("unload:"))
        assertEquals(1, locks.held.size)
    }

    @Test
    fun `closeForShutdown only saves and never unloads`() = runTest {
        val locks = FakeLocks()
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(7) }

        closing.closeForShutdown()

        assertArrayEquals(byteArrayOf(7), storedWorldBytes(uuid, "survival"))
        assertEquals(0, bridge.count("unload:"))
        assertEquals(1, locks.held.size)
        assertNull(ApiInstances.ownership.ownerOf("survival"))
    }

    @Test
    fun `closeForShutdown saves even when a save of the same world never finishes`() = runTest {
        val closing = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(7) }
        // An autosave that holds the world and never gets to finish
        bridge.saveGate = CompletableDeferred()
        val stuck = async { closing.saveWorld(world) }
        runCurrent()

        closing.closeForShutdown()

        assertArrayEquals(byteArrayOf(7), storedWorldBytes(uuid, "survival"))
        assertEquals(2, bridge.count("save:"))
        stuck.cancel()
    }

    @Test
    fun `a closed instance rejects every further call`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        api.close()

        val calls = listOf<suspend () -> Unit>(
            { api.createWorld(UUID.randomUUID(), "survival") },
            { api.loadWorld(uuid, "survival") },
            { api.saveWorld(world) },
            { api.unloadWorld(world, saveChanges = true) },
            { api.deleteWorld(world) },
            { api.unloadAllWorlds(saveChanges = true) },
            { api.saveAllWorlds() },
            { api.findWorld(uuid, "survival") },
            { api.listWorlds("survival", showLoadedOnly = true) },
            { api.loadedWorlds }
        )
        for (call in calls) {
            val exception = assertThrows<IllegalStateException> { call() }
            assertTrue(exception.message!!.contains("closed"), exception.message)
        }
    }

    @Test
    fun `a save that is in flight finishes before an unload of the same world starts`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(7) }
        bridge.saveGate = CompletableDeferred()
        val gate = bridge.saveGate!!

        val saving = async { api.saveWorld(world) }
        runCurrent()
        val unloading = async { api.unloadWorld(world, saveChanges = false) }
        runCurrent()

        assertEquals(0, bridge.count("unload:"))

        gate.complete(Unit)
        saving.await()
        unloading.await()

        assertEquals(listOf("save:$uuid", "unload:$uuid"), bridge.calls.drop(1))
        assertArrayEquals(byteArrayOf(7), storedWorldBytes(uuid, "survival"))
    }

    @Test
    fun `a save does not write when the lock was lost while it ran`() = runTest {
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        val before = storage.stored.getValue(WorldCacheKey(uuid, "survival")).copyOf()
        bridge.saveGate = CompletableDeferred()
        val gate = bridge.saveGate!!

        val saving = async { runCatching { api.saveWorld(world) } }
        runCurrent()
        locks.held.clear()
        gate.complete(Unit)

        assertTrue(saving.await().exceptionOrNull() is IllegalStateException)
        assertEquals(0, storage.writes)
        assertArrayEquals(before, storage.stored.getValue(WorldCacheKey(uuid, "survival")))
    }

    @Test
    fun `deleteWorld keeps the lock and the stored world when bukkit refuses the unload`() = runTest {
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival") as SurfWorldImpl
        bridge.failingUnloads += world

        assertThrows<IllegalStateException> { api.deleteWorld(world) }

        assertEquals(setOf(WorldCacheKey(uuid, "survival")), locks.held)
        assertTrue(WorldCacheKey(uuid, "survival") in storage.stored)
        assertSame(world, api.findWorld(uuid, "survival"))
    }

    @Test
    fun `a failing load of a world whose lock this server already held keeps that lock`() = runTest {
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        // Held by a world that an earlier instance left loaded
        locks.held += WorldCacheKey(uuid, "survival")
        bridge.failOnLoad = IllegalStateException("already loaded")

        assertThrows<IllegalStateException> { api.createWorld(uuid, "survival") }

        assertEquals(setOf(WorldCacheKey(uuid, "survival")), locks.held)
    }

    @Test
    fun `the exception for an owned game mode names the real owner`() {
        val factory = SurfPolarPaperApiFactoryImpl()
        val other = mockk<Plugin> { every { name } returns "OtherPlugin" }
        factory.create(plugin, settings())

        val exception = assertThrows<GameModeAlreadyOwnedException> { factory.create(other, settings()) }

        assertEquals("TestPlugin", exception.owner)
    }

    @Test
    fun `claims are restored when creating the instance fails`() {
        val factory = SurfPolarPaperApiFactoryImpl()
        factory.create(plugin, polarPaperSettings { gameMode("lobby") { version = 1 } })

        assertThrows<IllegalStateException> {
            ApiInstances.claimAndRegister(plugin, settings()) { throw IllegalStateException("cannot create") }
        }

        assertNull(ApiInstances.ownership.ownerOf("survival"))
        // What the plugin's registered instance declares stays claimed
        assertEquals("TestPlugin", ApiInstances.ownership.ownerOf("lobby"))
        assertEquals(1, ApiInstances.all.size)
    }

    @Test
    fun `closeForShutdown does not wait for an autosave that can never run and still saves`() = runTest {
        // An autosave queued behind the blocked shutdown thread: its dispatcher never runs anything
        val deadScope = CoroutineScope(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
        })
        val closing = SurfPolarPaperApiImpl(plugin, settings(autosave = true), { storage }, { bridge }, autosaveScope = deadScope)
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        closing.loadWorld(uuid, "survival")
        bridge.savedBytes = { byteArrayOf(8) }

        closing.closeForShutdown()

        assertArrayEquals(byteArrayOf(8), storedWorldBytes(uuid, "survival"))
        assertEquals(1, bridge.count("save:"))
        assertTrue(ApiInstances.all.isEmpty())
    }

    @Test
    fun `creating an already loaded world is rejected and leaves the loaded world untouched`() = runTest {
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        val world = api.createWorld(uuid, "survival")
        val callsBefore = bridge.calls.toList()

        assertThrows<IllegalStateException> { api.createWorld(uuid, "survival") }

        assertSame(world, api.findWorld(uuid, "survival"))
        assertEquals(callsBefore, bridge.calls)
        assertEquals(setOf(WorldCacheKey(uuid, "survival")), locks.held)
        assertTrue(world in bridge.platformWorlds)
    }

    @Test
    fun `a failing create releases the lock, unloads the platform world and caches nothing`() = runTest {
        val locks = FakeLocks()
        val api = SurfPolarPaperApiImpl(plugin, settings(), { storage }, { bridge }, { locks })
        val uuid = UUID.randomUUID()
        bridge.failOnLoad = RuntimeException("boom")

        assertThrows<IllegalStateException> { api.createWorld(uuid, "survival") }

        assertTrue(locks.held.isEmpty())
        assertEquals(1, bridge.count("unload:"))
        assertNull(api.findWorld(uuid, "survival"))
        assertEquals(0, storage.writes)
    }

    @Test
    fun `concurrent loads of the same world load it once`() = runTest {
        val uuid = UUID.randomUUID()
        store(uuid, "survival", byteArrayOf(1))
        val gate = CompletableDeferred<Unit>()
        bridge.loadGate = gate

        val first = async { api.loadWorld(uuid, "survival") }
        val second = async { api.loadWorld(uuid, "survival") }
        runCurrent()
        gate.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, bridge.count("load:"))
    }

    @Test
    fun `a second instance of the same plugin cannot declare an owned game mode`() {
        val factory = SurfPolarPaperApiFactoryImpl()
        val first = factory.create(plugin, settings())

        assertThrows<GameModeAlreadyOwnedException> { factory.create(plugin, settings()) }

        assertEquals(listOf(first), ApiInstances.all)
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
