package dev.slne.surf.polarpaper.core.client.config.storage

import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageProvider
import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
enum class PolarPaperStorageType(
    private val storageProviderInit: (StorageConfigurable) -> StorageProvider
) {
    DISK({ config ->
        val diskConfig = config as? DiskStorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for DISK storage type")

        DiskStorageProvider(diskConfig)
    }),
    DATABASE({ config ->
        val databaseConfig = config as? DatabaseStorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for DATABASE storage type")

        DatabaseStorageProvider(
            databaseConfig
        )
    }),
    S3({ config ->
        val s3Config = config as? S3StorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for S3 storage type")

        S3StorageProvider(s3Config)
    });

    fun createStorageProvider(config: StorageConfigurable): StorageProvider {
        return storageProviderInit(config)
    }
}