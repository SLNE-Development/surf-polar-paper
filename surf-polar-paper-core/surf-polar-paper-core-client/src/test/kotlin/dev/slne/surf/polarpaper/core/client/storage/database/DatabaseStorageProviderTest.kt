package dev.slne.surf.polarpaper.core.client.storage.database

import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.*

class DatabaseStorageProviderTest {
    private val service = mockk<DatabaseStorageService>(relaxed = true)
    private var proxyCreations = 0
    private val provider = DatabaseStorageProvider {
        proxyCreations++
        service
    }

    @Test
    fun `the rpc proxy is created lazily and only once`() = runTest {
        assertEquals(0, proxyCreations)

        provider.loadWorldBytes(UUID.randomUUID(), "survival")
        provider.listWorlds("survival")

        assertEquals(1, proxyCreations)
    }

    @Test
    fun `world operations are delegated to the rpc service`() = runTest {
        val uuid = UUID.randomUUID()
        coEvery { service.loadWorldBytes(uuid, "survival") } returns byteArrayOf(1, 2)
        coEvery { service.listWorlds("survival") } returns listOf(WorldInfo(uuid, "survival"))

        assertArrayEquals(byteArrayOf(1, 2), provider.loadWorldBytes(uuid, "survival"))
        assertEquals(listOf(WorldInfo(uuid, "survival")), provider.listWorlds("survival"))

        provider.writeWorldBytes(uuid, "survival", byteArrayOf(3))
        provider.deleteWorldBytes(uuid, "survival")

        coVerify { service.writeWorldBytes(uuid, "survival", byteArrayOf(3)) }
        coVerify { service.deleteWorldBytes(uuid, "survival") }
    }

    @Test
    fun `missing worlds come back as null`() = runTest {
        coEvery { service.loadWorldBytes(any(), any()) } returns null

        assertNull(provider.loadWorldBytes(UUID.randomUUID(), "survival"))
    }
}
