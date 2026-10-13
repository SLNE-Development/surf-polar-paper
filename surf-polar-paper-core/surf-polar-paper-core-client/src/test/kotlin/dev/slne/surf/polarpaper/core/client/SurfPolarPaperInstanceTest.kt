package dev.slne.surf.polarpaper.core.client

import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageType
import dev.slne.surf.polarpaper.core.client.fakes.FakeS3Server
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class SurfPolarPaperInstanceTest {
    @TempDir
    lateinit var dataPath: Path

    private val api = mockk<SurfPolarPaperApi>(relaxed = true)

    private inner class TestInstance(
        override var config: PolarPaperConfig = PolarPaperConfig(
            storage = PolarPaperStorageConfig(disk = DiskStorageConfig("worlds"))
        )
    ) : SurfPolarPaperInstance() {
        override val dataPath: Path get() = this@SurfPolarPaperInstanceTest.dataPath
        override val api: SurfPolarPaperApi get() = this@SurfPolarPaperInstanceTest.api
    }

    @Test
    fun `constructing an instance does not read dataPath`() {
        class NotReadyInstance : SurfPolarPaperInstance() {
            override val dataPath: Path get() = error("dataPath is not available yet")
        }

        assertDoesNotThrow { NotReadyInstance() }
    }

    @Test
    fun `onLoad connects the configured storage provider`() = runTest {
        val instance = TestInstance()

        instance.onLoad()

        assertInstanceOf(DiskStorageProvider::class.java, instance.storageProvider)
        assertTrue(dataPath.resolve("worlds").exists())
    }

    @Test
    fun `connecting again replaces the storage provider`() = runTest {
        val instance = TestInstance()
        instance.connect()
        val first = instance.storageProvider

        instance.connect()

        assertNotSame(first, instance.storageProvider)
    }

    @Test
    fun `a storage provider that fails to connect is not kept`() = runTest {
        FakeS3Server(bucket = "worlds").use { server ->
            val instance = TestInstance(
                PolarPaperConfig(
                    storage = PolarPaperStorageConfig(
                        storageType = PolarPaperStorageType.S3,
                        s3 = S3StorageConfig(
                            endpoint = server.endpoint,
                            bucket = "missing",
                            accessKey = "a",
                            secretKey = "s"
                        )
                    )
                )
            )

            assertThrows<IllegalStateException> { instance.connect() }

            assertNull(instance.storageProvider)
        }
    }

    @Test
    fun `onDisable saves all worlds without unloading them and disconnects`() = runTest {
        val instance = TestInstance()
        instance.connect()

        instance.onDisable()

        coVerify(exactly = 1) { api.saveAllWorlds() }
        coVerify(exactly = 0) { api.unloadAllWorlds(any(), any(), any()) }
        assertNull(instance.storageProvider)
    }

    @Test
    fun `onDisable disconnects even when saving fails`() = runTest {
        val instance = TestInstance()
        instance.connect()
        coEvery { api.saveAllWorlds() } throws IllegalStateException("Failed to save 1 of 2 worlds")

        assertThrows<IllegalStateException> { instance.onDisable() }

        assertNull(instance.storageProvider)
    }
}
