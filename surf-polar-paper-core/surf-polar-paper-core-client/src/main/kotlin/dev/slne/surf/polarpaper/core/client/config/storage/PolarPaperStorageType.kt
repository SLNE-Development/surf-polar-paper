package dev.slne.surf.polarpaper.core.client.config.storage

import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageProvider
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageProvider
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import java.nio.file.Path

@ConfigSerializable
enum class PolarPaperStorageType(
    private val storageProviderInit: (config: StorageConfigurable, dataPath: Path) -> StorageProvider
) {
    DISK({ config, dataPath ->
        val diskConfig = config as? DiskStorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for DISK storage type")

        DiskStorageProvider(diskConfig, dataPath)
    }),
    DATABASE({ config, dataPath ->
        if (config !is DatabaseStorageConfig) {
            throw IllegalArgumentException("Invalid storage config for DATABASE storage type")
        }

        check(DatabaseStorageProvider.isRabbitMqAvailable()) {
            "DATABASE storage needs the surf-rabbitmq plugin, which is not installed"
        }

        DatabaseStorageProvider(dataPath)
    }),
    S3({ config, _ ->
        val s3Config = config as? S3StorageConfig
            ?: throw IllegalArgumentException("Invalid storage config for S3 storage type")

        S3StorageProvider(s3Config)
    });

    fun createStorageProvider(config: StorageConfigurable, dataPath: Path): StorageProvider {
        return storageProviderInit(config, dataPath)
    }
}
