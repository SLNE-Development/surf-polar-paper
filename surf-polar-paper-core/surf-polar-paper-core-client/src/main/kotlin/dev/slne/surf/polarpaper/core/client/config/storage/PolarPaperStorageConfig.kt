package dev.slne.surf.polarpaper.core.client.config.storage

import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class PolarPaperStorageConfig(
    val storageType: PolarPaperStorageType,

    val disk: DiskStorageConfig? = null,
    val s3: S3StorageConfig? = null,
    val database: DatabaseStorageConfig? = null
)
