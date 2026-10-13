package dev.slne.surf.polarpaper.core.client.config

import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageConfig
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment
import java.nio.file.Path

@ConfigSerializable
data class PolarPaperConfig(
    @Comment("The game mode this server hosts worlds for.")
    val gameModeIdentifier: String = "default",
    val storage: PolarPaperStorageConfig = PolarPaperStorageConfig(),
    val locking: WorldLockingConfig = WorldLockingConfig()
) {
    companion object {
        const val DEFAULT_CONFIG_FILE_NAME = "polar-paper.yml"

        fun createConfigManager(
            path: Path,
            fileName: String = DEFAULT_CONFIG_FILE_NAME
        ) = surfConfigApi.createSpongeYmlConfigManager(
            configClass = PolarPaperConfig::class.java,
            configFolder = path,
            configFileName = fileName
        )
    }
}
