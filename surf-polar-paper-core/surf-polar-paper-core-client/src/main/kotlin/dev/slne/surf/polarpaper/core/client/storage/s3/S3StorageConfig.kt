package dev.slne.surf.polarpaper.core.client.storage.s3

import dev.slne.surf.polarpaper.core.client.config.storage.StorageConfigurable
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
data class S3StorageConfig(
    val endpoint: String,
    val region: String = "us-east-1",
    val bucket: String,
    val accessKey: String,
    val secretKey: String,

    @Comment(
        """
        How often a request is tried before it fails. Failed attempts caused by dropped connections or timeouts
        are retried with a backoff of 100ms doubling up to 10s, so the default keeps retrying for about half a minute.
        """
    )
    val maxAttempts: Int = 10,

    @Comment(
        """
        Seconds after which unused connections are closed. Keep this below the idle timeout of the S3 endpoint,
        otherwise requests can be sent on connections the endpoint has already dropped.
        """
    )
    val connectionIdleTimeoutSeconds: Long = 20,
) : StorageConfigurable
