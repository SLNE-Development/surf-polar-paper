package dev.slne.surf.polarpaper.core.client.storage.disk

import dev.slne.surf.polarpaper.api.world.SurfWorld
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.ZipUtils
import java.util.*
import kotlin.io.path.createDirectories

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
    ) = storageDirectory.resolve(gameModeIdentifier).resolve("$worldUuid.zip")

    override suspend fun loadWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ): ByteArray? {
        return ZipUtils.unzipFile(resolvePath(worldUuid, gameModeIdentifier))
    }

    override suspend fun writeWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String,
        bytes: ByteArray
    ) {
        ZipUtils.zipFile(
            name = worldUuid.toString(),
            fileContent = bytes,
            path = resolvePath(worldUuid, gameModeIdentifier)
        )
    }

    override suspend fun deleteWorldBytes(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) {
        val filePath = resolvePath(worldUuid, gameModeIdentifier)

        if (filePath.toFile().exists()) {
            filePath.toFile().delete()
        }
    }

    override suspend fun listWorlds(
        gameModeIdentifier: String
    ): List<SurfWorld> {
        val gameModeDirectory = storageDirectory.resolve(gameModeIdentifier)

        if (!gameModeDirectory.toFile().exists()) {
            return emptyList()
        }

        return gameModeDirectory.toFile().listFiles()
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