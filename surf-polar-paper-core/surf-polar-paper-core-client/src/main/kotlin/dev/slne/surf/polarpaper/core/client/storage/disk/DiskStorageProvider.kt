package dev.slne.surf.polarpaper.core.client.storage.disk

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class DiskStorageProvider(
    private val config: DiskStorageConfig
) : StorageProvider {
    private val storageDirectory
        get() = SurfPolarPaperInstance.INSTANCE.dataPath
            .resolve(config.path)

    override suspend fun connect() {
        storageDirectory.createDirectories()
    }

    override suspend fun disconnect() {
        // No disconnection needed for disk storage
    }

    private fun resolvePath(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) = storageDirectory.resolve(gameModeIdentifier).resolve("$worldUuid.${WorldStorageCodec.FILE_EXTENSION}")

    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val path = resolvePath(worldUuid, gameModeIdentifier)

        if (path.exists()) path.readBytes() else null
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) = withContext(Dispatchers.IO) {
        val path = resolvePath(worldUuid, gameModeIdentifier)
        val tempPath = path.resolveSibling("${path.fileName}.tmp")

        path.parent.createDirectories()

        // Write to a temporary file first so a crash mid-write never leaves a corrupted world behind
        tempPath.writeBytes(bytes)
        tempPath.moveTo(path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        Unit
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) = withContext(Dispatchers.IO) {
        resolvePath(worldUuid, gameModeIdentifier).deleteIfExists()

        Unit
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<SurfWorld> {
        val gameModeDirectory = storageDirectory.resolve(gameModeIdentifier)

        if (!gameModeDirectory.toFile().exists()) {
            return emptyList()
        }

        return gameModeDirectory.toFile().listFiles()
            ?.filter { it.isFile && it.extension == WorldStorageCodec.FILE_EXTENSION }
            ?.mapNotNull { file ->
                val fileName = file.nameWithoutExtension
                val worldUuid = fileName.toUUIDOrNull()

                if (worldUuid != null) {
                    SurfWorld(
                        uuid = worldUuid,
                        gameModeIdentifier = gameModeIdentifier,
                        worldBytes = ByteArray(0) // Placeholder, actual bytes can be loaded later
                    )
                } else {
                    null
                }
            } ?: emptyList()
    }

    private fun String.toUUIDOrNull(): UUID? {
        return try {
            UUID.fromString(this)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}