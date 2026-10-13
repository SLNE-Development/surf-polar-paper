package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.api.world.WorldLockedException
import dev.slne.surf.polarpaper.core.client.fakes.FakePlatformWorldBridge
import dev.slne.surf.polarpaper.core.client.fakes.FakeWorldLockService
import dev.slne.surf.polarpaper.core.client.fakes.InMemoryStorageProvider
import dev.slne.surf.polarpaper.core.client.lock.RemoteWorldLocks
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class SurfPolarPaperApiImplLockTest {
    private val storage = InMemoryStorageProvider()
    private val bridge = FakePlatformWorldBridge()
    private val lockService = FakeWorldLockService()
    private val locks = RemoteWorldLocks("lobby-1", lockService)
    private val api = SurfPolarPaperApiImpl({ storage }, { bridge }, { locks })

    private val uuid = UUID.randomUUID()
    private val key = WorldCacheKey(uuid, "survival")

    private fun store(worldBytes: ByteArray = byteArrayOf(1)) {
        storage.stored[key] = WorldStorageCodec.encode(worldBytes)
    }

    @Test
    fun `loading a world locks it for this server`() = runTest {
        store()

        api.loadWorld(uuid, "survival")

        assertEquals("lobby-1", lockService.owners[key])
    }

    @Test
    fun `a world locked by another server is neither read nor loaded`() = runTest {
        store()
        lockService.owners[key] = "lobby-2"

        val exception = assertThrows<WorldLockedException> { api.loadWorld(uuid, "survival") }

        assertEquals("lobby-2", exception.owner)
        assertEquals(0, storage.loads)
        assertTrue(bridge.calls.isEmpty())
        assertNull(api.findWorld(uuid, "survival"))
    }

    @Test
    fun `a failed load releases the lock`() = runTest {
        // Nothing stored, so loading fails after locking

        assertThrows<IllegalStateException> { api.loadWorld(uuid, "survival") }

        assertFalse(locks.isHeld(key))
        assertTrue(lockService.owners.isEmpty())
    }

    @Test
    fun `unloading releases the lock`() = runTest {
        store()
        val world = api.loadWorld(uuid, "survival")

        api.unloadWorld(world, saveChanges = true)

        assertTrue(lockService.owners.isEmpty())
    }

    @Test
    fun `creating a world locks it`() = runTest {
        api.createWorld(uuid, "survival", byteArrayOf(1))

        assertEquals("lobby-1", lockService.owners[key])
    }

    @Test
    fun `a world whose lock was lost is not saved`() = runTest {
        store(byteArrayOf(1))
        val world = api.loadWorld(uuid, "survival")
        lockService.owners[key] = "lobby-2"
        locks.renew()
        bridge.savedBytes = { byteArrayOf(9) }

        assertThrows<IllegalStateException> { api.saveWorld(world) }

        assertEquals(1, WorldStorageCodec.decode(storage.stored.getValue(key)).single().toInt())
    }

    @Test
    fun `deleting a world that is not loaded locks it for the deletion`() = runTest {
        store()

        api.deleteWorld(SurfWorld(uuid, "survival", ByteArray(0)))

        assertTrue(storage.stored.isEmpty())
        assertTrue(lockService.owners.isEmpty())
    }

    @Test
    fun `a world loaded on another server cannot be deleted`() = runTest {
        store()
        lockService.owners[key] = "lobby-2"

        assertThrows<WorldLockedException> { api.deleteWorld(SurfWorld(uuid, "survival", ByteArray(0))) }

        assertTrue(key in storage.stored)
        assertEquals("lobby-2", lockService.owners[key])
    }

    @Test
    fun `deleting a loaded world releases its lock`() = runTest {
        store()
        val world = api.loadWorld(uuid, "survival")

        api.deleteWorld(world)

        assertTrue(storage.stored.isEmpty())
        assertTrue(lockService.owners.isEmpty())
    }
}
