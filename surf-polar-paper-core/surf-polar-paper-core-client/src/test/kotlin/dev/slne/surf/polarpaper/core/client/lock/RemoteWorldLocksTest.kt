package dev.slne.surf.polarpaper.core.client.lock

import dev.slne.surf.polarpaper.api.world.WorldLockedException
import dev.slne.surf.polarpaper.core.client.fakes.FakeWorldLockService
import dev.slne.surf.polarpaper.core.common.world.WorldCacheKey
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import kotlin.time.Duration.Companion.seconds

class RemoteWorldLocksTest {
    private val service = FakeWorldLockService()
    private val locks = RemoteWorldLocks("lobby-1", service)
    private val world = WorldCacheKey(UUID.randomUUID(), "survival")

    @Test
    fun `locking a free world holds it`() = runTest {
        locks.lock(world)

        assertTrue(locks.isHeld(world))
        assertEquals("lobby-1", service.owners[world])
    }

    @Test
    fun `a world locked by another server fails with its owner`() = runTest {
        service.owners[world] = "lobby-2"

        val exception = assertThrows<WorldLockedException> { locks.lock(world) }

        assertEquals("lobby-2", exception.owner)
        assertFalse(locks.isHeld(world))
    }

    @Test
    fun `unlocking releases the world`() = runTest {
        locks.lock(world)

        locks.unlock(world)

        assertFalse(locks.isHeld(world))
        assertTrue(service.owners.isEmpty())
    }

    @Test
    fun `locks lost in the meantime are no longer held after renewing`() = runTest {
        val other = WorldCacheKey(UUID.randomUUID(), "survival")
        locks.lock(world)
        locks.lock(other)
        service.owners[world] = "lobby-2" // expired and taken over while this server could not renew

        locks.renew()

        assertFalse(locks.isHeld(world))
        assertTrue(locks.isHeld(other))
    }

    @Test
    fun `a failed renewal keeps the locks`() = runTest {
        locks.lock(world)
        service.failRenewals = true

        locks.renew()

        assertTrue(locks.isHeld(world))
    }

    @Test
    fun `leases are renewed periodically`() = runTest {
        locks.lock(world)
        locks.startRenewing(backgroundScope)

        advanceTimeBy(61.seconds)

        assertEquals(3, service.renewals)
    }

    @Test
    fun `nothing is renewed while no world is locked`() = runTest {
        locks.startRenewing(backgroundScope)

        advanceTimeBy(61.seconds)

        assertEquals(0, service.renewals)
    }

    @Test
    fun `closing releases every lock and stops renewing`() = runTest {
        locks.lock(world)
        locks.startRenewing(backgroundScope)

        locks.close()
        advanceTimeBy(61.seconds)

        assertFalse(locks.isHeld(world))
        assertTrue(service.owners.isEmpty())
        assertEquals(0, service.renewals)
    }
}
