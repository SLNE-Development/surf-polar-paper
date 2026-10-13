package dev.slne.surf.polarpaper.core.client.config

import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageType
import dev.slne.surf.polarpaper.core.client.fakes.RecordingSurfConfigApi
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PolarPaperConfigTest {
    @TempDir
    lateinit var dataPath: Path

    private val s3Config = S3StorageConfig(endpoint = "http://localhost", bucket = "b", accessKey = "a", secretKey = "s")

    @Test
    fun `the config manager loads the full plugin config`() {
        // RecordingSurfConfigApi stands in for the server-only implementation and only records the request
        RecordingSurfConfigApi.ymlManagerRequests.clear()

        assertThrows<UnsupportedOperationException> { PolarPaperConfig.createConfigManager(dataPath) }

        assertEquals(
            listOf(
                RecordingSurfConfigApi.Request(
                    PolarPaperConfig::class.java,
                    dataPath,
                    PolarPaperConfig.DEFAULT_CONFIG_FILE_NAME
                )
            ),
            RecordingSurfConfigApi.ymlManagerRequests
        )
    }

    @Test
    fun `the default config stores worlds on disk`() {
        val storage = PolarPaperConfig().storage

        assertEquals(PolarPaperStorageType.DISK, storage.storageType)
        assertEquals(DiskStorageConfig("worlds"), storage.activeStorageConfig())
        assertInstanceOf(DiskStorageProvider::class.java, storage.createStorageProvider(dataPath))
    }

    @Test
    fun `the active storage config matches the storage type`() {
        val database = DatabaseStorageConfig()
        val storage = PolarPaperStorageConfig(
            storageType = PolarPaperStorageType.S3,
            s3 = s3Config,
            database = database
        )

        assertSame(s3Config, storage.activeStorageConfig())
        assertSame(database, storage.copy(storageType = PolarPaperStorageType.DATABASE).activeStorageConfig())
        assertInstanceOf(S3StorageProvider::class.java, storage.createStorageProvider(dataPath))
    }

    @Test
    fun `a missing section for the storage type is rejected`() {
        val storage = PolarPaperStorageConfig(storageType = PolarPaperStorageType.S3)

        assertThrows<IllegalArgumentException> { storage.activeStorageConfig() }
        assertThrows<IllegalArgumentException> { storage.createStorageProvider(dataPath) }
    }

    @Test
    fun `storage types reject configs of another type`() {
        assertThrows<IllegalArgumentException> {
            PolarPaperStorageType.DISK.createStorageProvider(s3Config, dataPath)
        }
        assertThrows<IllegalArgumentException> {
            PolarPaperStorageType.S3.createStorageProvider(DiskStorageConfig(), dataPath)
        }
        assertThrows<IllegalArgumentException> {
            PolarPaperStorageType.DATABASE.createStorageProvider(DiskStorageConfig(), dataPath)
        }
    }
}
