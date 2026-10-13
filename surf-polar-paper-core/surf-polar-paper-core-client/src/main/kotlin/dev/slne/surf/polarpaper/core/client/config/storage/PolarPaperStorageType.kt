package dev.slne.surf.polarpaper.core.client.config.storage

import dev.slne.surf.polarpaper.core.client.storage.StorageContext
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
    private val storageProviderInit: (config: StorageConfigurable, context: StorageContext) -> StorageProvider
) {
    DISK({ config, context ->
        val diskConfig = config as? DiskStorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for DISK storage type")

        DiskStorageProvider(diskConfig, context.dataPath)
    }),
    DATABASE({ config, context ->
        if (config !is DatabaseStorageConfig) {
            throw IllegalArgumentException("Invalid storage config for DATABASE storage type")
        }

        DatabaseStorageProvider(context.databaseStorageService)
    }),
    S3({ config, _ ->
        val s3Config = config as? S3StorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for S3 storage type")

        S3StorageProvider(s3Config)
    });

    fun createStorageProvider(config: StorageConfigurable, context: StorageContext): StorageProvider {
        return storageProviderInit(config, context)
    }
}
