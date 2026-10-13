package dev.slne.surf.polarpaper.core.client.storage

import dev.slne.surf.polarpaper.core.common.storage.database.DatabaseStorageService
import java.nio.file.Path

/**
 * What storage providers may need from the plugin.
 */
class StorageContext(
    val dataPath: Path,
    val databaseStorageService: () -> DatabaseStorageService
)
