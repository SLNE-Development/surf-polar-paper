package dev.slne.surf.polarpaper.core.client.storage.disk

import dev.slne.surf.polarpaper.core.client.config.storage.StorageConfigurable
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.objectmapping.meta.Comment

@ConfigSerializable
data class DiskStorageConfig(
    @Comment("The path to the directory where files will be stored relative to the plugin root.")
    val path: String
) : StorageConfigurable
