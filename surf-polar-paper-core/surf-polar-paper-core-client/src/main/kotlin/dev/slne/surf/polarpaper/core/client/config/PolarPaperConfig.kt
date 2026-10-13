package dev.slne.surf.polarpaper.core.client.config

import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageConfig
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import java.nio.file.Path

@ConfigSerializable
data class PolarPaperConfig(
    val gameModeIdentifier: String,
    val storage: PolarPaperStorageConfig
) {
    companion object {
        private const val DEFAULT_CONFIG_FILE_NAME = "polar-paper.yml"

        fun createConfigManager(
            path: Path,
            fileName: String = DEFAULT_CONFIG_FILE_NAME
        ) = surfConfigApi.createSpongeYmlConfigManager(
            configClass = PolarPaperStorageConfig::class.java,
            configFolder = path,
            configFileName = fileName
        )
    }
}
