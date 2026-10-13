package dev.slne.surf.polarpaper.core.client

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageType
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import java.nio.file.Path

private val instance = requiredService<SurfPolarPaperInstance>()

abstract class SurfPolarPaperInstance {
    abstract val dataPath: Path

    val configManager = PolarPaperConfig.createConfigManager(
        path = dataPath
    )
    val config get() = configManager.config

    var storageProvider: StorageProvider? = null

    @OverridingMethodsMustInvokeSuper
    suspend fun onLoad() {
        connect()
    }

    @OverridingMethodsMustInvokeSuper
    suspend fun onEnable() {

    }

    @OverridingMethodsMustInvokeSuper
    suspend fun onDisable() {
        SurfPolarPaperApi.unloadAllWorlds()

        disconnect()
    }

    suspend fun connect() {
        disconnect() // Disconnect any existing storage provider before connecting a new one

        val storageType = config.storageType
        val storageConfig = when (storageType) {
            PolarPaperStorageType.DISK -> config.disk
            PolarPaperStorageType.DATABASE -> config.database
            PolarPaperStorageType.S3 -> config.s3
        }

        if (storageConfig == null) {
            throw IllegalArgumentException("Storage config for $storageType is not provided in the configuration.")
        }

        storageProvider = storageType.createStorageProvider(storageConfig)
        storageProvider?.connect()
    }

    suspend fun disconnect() {
        storageProvider?.disconnect()
        storageProvider = null
    }

    suspend fun reconnect() {
        disconnect()
        connect()
    }

    suspend fun saveConfig(
        reconnect: Boolean = true
    ) {
        configManager.save()

        if (reconnect) {
            reconnect()
        }
    }

    suspend fun reloadConfig(
        reconnect: Boolean = true
    ) {
        configManager.reloadFromFile()

        if (reconnect) {
            reconnect()
        }
    }

    companion object {
        val INSTANCE get() = instance
    }
}