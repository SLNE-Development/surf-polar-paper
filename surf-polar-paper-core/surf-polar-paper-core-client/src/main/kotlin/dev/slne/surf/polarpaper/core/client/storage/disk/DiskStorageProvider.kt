package dev.slne.surf.polarpaper.core.client.storage.disk

import dev.slne.surf.polarpaper.api.world.GameModeIdentifiers
import dev.slne.surf.polarpaper.api.world.WorldInfo
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import dev.slne.surf.polarpaper.core.client.utils.WorldStorageCodec
import dev.slne.surf.polarpaper.core.client.utils.toUuidOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class DiskStorageProvider(
    private val config: DiskStorageConfig,
    dataPath: Path
) : StorageProvider {
    private val storageDirectory = dataPath.resolve(config.path).toAbsolutePath().normalize()

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            storageDirectory.createDirectories()
        }
    }

    override suspend fun disconnect() {
        // No disconnection needed for disk storage
    }

    private fun resolveGameModeDirectory(gameModeIdentifier: String): Path {
        GameModeIdentifiers.requireValid(gameModeIdentifier)

        val directory = storageDirectory.resolve(gameModeIdentifier).normalize()

        // Defense in depth, the identifier validation already rules out path separators and '..'
        check(directory.parent == storageDirectory) {
            "Game mode directory $directory escapes the storage directory $storageDirectory"
        }

        return directory
    }

    private fun resolvePath(
        worldUuid: UUID,
        gameModeIdentifier: String
    ) = resolveGameModeDirectory(gameModeIdentifier).resolve("$worldUuid.${WorldStorageCodec.FILE_EXTENSION}")

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
    ): List<WorldInfo> = withContext(Dispatchers.IO) {
        val gameModeDirectory = resolveGameModeDirectory(gameModeIdentifier)

        if (!gameModeDirectory.isDirectory()) {
            return@withContext emptyList()
        }

        gameModeDirectory.listDirectoryEntries("*.${WorldStorageCodec.FILE_EXTENSION}")
            .filter { it.isRegularFile() && it.extension == WorldStorageCodec.FILE_EXTENSION }
            .mapNotNull { file ->
                file.nameWithoutExtension.toUuidOrNull()?.let { worldUuid ->
                    WorldInfo(worldUuid, gameModeIdentifier)
                }
            }
    }
}
