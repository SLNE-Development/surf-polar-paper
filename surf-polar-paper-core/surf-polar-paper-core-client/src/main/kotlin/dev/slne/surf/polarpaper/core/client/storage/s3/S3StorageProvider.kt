package dev.slne.surf.polarpaper.core.client.storage.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.listObjectsV2
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.client.utils.toUuidOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class S3StorageProvider(
    private val config: S3StorageConfig
) : StorageProvider {
    private var client: S3Client? = null

    override suspend fun connect() {
        if (client != null) {
            return
        }

        val client = S3Client {
            endpointUrl = Url.parse(config.endpoint)
            region = config.region
            forcePathStyle = true

            credentialsProvider = StaticCredentialsProvider(
                Credentials(
                    accessKeyId = config.accessKey,
                    secretAccessKey = config.secretKey
                )
            )

            // Requests open new connections when pooled ones were dropped, retries ride out longer connection losses
            retryStrategy {
                maxAttempts = config.maxAttempts
                delayProvider {
                    initialDelay = RETRY_INITIAL_DELAY
                    scaleFactor = RETRY_SCALE_FACTOR
                    maxBackoff = RETRY_MAX_BACKOFF
                }
            }

            httpClient {
                connectionIdleTimeout = config.connectionIdleTimeoutSeconds.seconds
            }
        }

        try {
            client.headBucket(
                HeadBucketRequest {
                    bucket = config.bucket
                }
            )
        } catch (exception: Exception) {
            withContext(Dispatchers.IO) {
                client.close()
            }

            throw IllegalStateException(
                "Failed to connect to S3 bucket: ${config.bucket} at endpoint: ${config.endpoint}",
                exception
            )
        }

        this.client = client
    }

    override suspend fun disconnect() {
        val client = client ?: return
        this.client = null

        withContext(Dispatchers.IO) {
            client.close()
        }
    }

    private fun requireClient(): S3Client {
        return client ?: throw IllegalStateException("S3 client is not initialized. Call connect() first.")
    }

    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? {
        val client = requireClient()
        val key = objectKey(worldUuid, gameModeIdentifier)

        return try {
            client.getObject(
                GetObjectRequest {
                    bucket = config.bucket
                    this.key = key
                }
            ) { response ->
                response.body?.toByteArray()
                    ?: throw IllegalStateException("Failed to read object body for key: $key")
            }
        } catch (_: NoSuchKey) {
            null
        }
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) {
        val client = requireClient()
        val key = objectKey(worldUuid, gameModeIdentifier)

        client.putObject {
            bucket = config.bucket
            this.key = key
            contentType = WorldStorageCodec.CONTENT_TYPE
            body = ByteStream.fromBytes(bytes)
        }
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) {
        val client = requireClient()
        val key = objectKey(worldUuid, gameModeIdentifier)

        client.deleteObject {
            bucket = config.bucket
            this.key = key
        }
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<WorldInfo> {
        val client = requireClient()
        val prefix = keyPrefix(gameModeIdentifier)

        val worlds = mutableListOf<WorldInfo>()
        var continuationToken: String? = null

        do {
            val response = client.listObjectsV2 {
                bucket = config.bucket
                this.prefix = prefix
                this.continuationToken = continuationToken
            }

            response.contents?.forEach { s3Object ->
                val worldUuid = s3Object.key?.let { worldUuidFromKey(it, gameModeIdentifier) } ?: return@forEach

                worlds.add(WorldInfo(worldUuid, gameModeIdentifier))
            }

            continuationToken = response.nextContinuationToken
        } while (continuationToken != null)

        return worlds
    }

    internal companion object {
        private val FILE_SUFFIX = ".${WorldStorageCodec.FILE_EXTENSION}"

        val RETRY_INITIAL_DELAY = 100.milliseconds
        const val RETRY_SCALE_FACTOR = 2.0
        val RETRY_MAX_BACKOFF = 10.seconds

        fun keyPrefix(gameModeIdentifier: String) = "${GameModeIdentifiers.requireValid(gameModeIdentifier)}/"

        fun objectKey(
            worldUuid: UUID,
            gameModeIdentifier: String
        ) = "${keyPrefix(gameModeIdentifier)}$worldUuid$FILE_SUFFIX"

        /**
         * Returns the world UUID of an object key directly inside the game mode prefix, or `null` for any other key.
         */
        fun worldUuidFromKey(key: String, gameModeIdentifier: String): UUID? {
            val fileName = key.removePrefix(keyPrefix(gameModeIdentifier))

            if (fileName == key || '/' in fileName || !fileName.endsWith(FILE_SUFFIX)) {
                return null
            }

            return fileName.removeSuffix(FILE_SUFFIX).toUuidOrNull()
        }
    }
}
