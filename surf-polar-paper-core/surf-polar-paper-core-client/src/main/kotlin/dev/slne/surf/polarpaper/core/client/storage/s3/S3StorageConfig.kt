package dev.slne.surf.polarpaper.core.client.storage.s3

import dev.slne.surf.polarpaper.core.client.config.storage.StorageConfigurable
import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class S3StorageConfig(
    val endpoint: String,
    val region: String = "us-east-1",
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
) : StorageConfigurable
