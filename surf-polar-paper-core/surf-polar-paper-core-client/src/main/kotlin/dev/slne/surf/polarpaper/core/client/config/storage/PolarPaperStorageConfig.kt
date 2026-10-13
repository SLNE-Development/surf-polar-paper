package dev.slne.surf.polarpaper.core.client.config.storage

import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.storage.database.DatabaseStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.disk.DiskStorageConfig
import dev.slne.surf.polarpaper.core.client.storage.s3.S3StorageConfig
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.file.Path

@ConfigSerializable
data class PolarPaperStorageConfig(
    @Comment("Where worlds are stored: DISK, DATABASE or S3. Only the matching section below is used.")
    val storageType: PolarPaperStorageType = PolarPaperStorageType.DISK,

    val disk: DiskStorageConfig? = DiskStorageConfig(),
    val s3: S3StorageConfig? = null,
    val database: DatabaseStorageConfig? = null
) {
    /**
     * Returns the config section that belongs to [storageType].
     */
    fun activeStorageConfig(): StorageConfigurable = when (storageType) {
        PolarPaperStorageType.DISK -> disk
        PolarPaperStorageType.DATABASE -> database
        PolarPaperStorageType.S3 -> s3
    } ?: throw IllegalArgumentException("Storage config for $storageType is not provided in the configuration.")

    fun createStorageProvider(dataPath: Path): StorageProvider =
        storageType.createStorageProvider(activeStorageConfig(), dataPath)
}
