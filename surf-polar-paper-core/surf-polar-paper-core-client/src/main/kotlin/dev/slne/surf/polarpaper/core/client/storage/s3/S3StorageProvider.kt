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
import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class S3StorageProvider(
    private val config: S3StorageConfig
) : StorageProvider {
    private lateinit var client: S3Client

    override suspend fun connect() {
        if (::client.isInitialized) {
            return
        }

        client = S3Client {
            endpointUrl = Url.parse(config.endpoint)
            region = config.region
            forcePathStyle = true

            credentialsProvider = StaticCredentialsProvider(
                Credentials(
                    accessKeyId = config.accessKey,
                    secretAccessKey = config.secretKey
                )
            )
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
    }

    override suspend fun disconnect() {
        if (::client.isInitialized) {
            withContext(Dispatchers.IO) {
                client.close()
            }
        }
    }

    private fun requireClient(): S3Client {
        if (!::client.isInitialized) {
            throw IllegalStateException("S3 client is not initialized. Call connect() first.")
        }

        return client
    }

    private fun objectKey(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): String {
        val fileName = "$worldUuid.${WorldStorageCodec.FILE_EXTENSION}"

        return if (gameModeIdentifier.isEmpty()) {
            fileName
        } else {
            "$gameModeIdentifier/$fileName"
        }
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
    ): List<SurfWorld> {
        val client = requireClient()
        val prefix = if (gameModeIdentifier.isEmpty()) {
            ""
        } else {
            "$gameModeIdentifier/"
        }

        val worlds = mutableListOf<SurfWorld>()
        var continuationToken: String? = null

        do {
            val response = client.listObjectsV2 {
                bucket = config.bucket
                this.prefix = prefix
                this.continuationToken = continuationToken
            }

            response.contents?.forEach { s3Object ->
                val key = s3Object.key ?: return@forEach
                val fileName = key.substringAfterLast("/")
                if (!fileName.endsWith(".${WorldStorageCodec.FILE_EXTENSION}")) return@forEach

                val worldUuidString = fileName.substringBeforeLast(".")
                val worldUuid = try {
                    UUID.fromString(worldUuidString)
                } catch (e: IllegalArgumentException) {
                    return@forEach
                }

                worlds.add(
                    SurfWorld(
                        uuid = worldUuid,
                        gameModeIdentifier = gameModeIdentifier,
                        worldBytes = ByteArray(0) // Placeholder, actual bytes can be loaded later
                    )
                )
            }

            continuationToken = response.nextContinuationToken
        } while (continuationToken != null)

        return worlds
    }
}