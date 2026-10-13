package dev.slne.surf.polarpaper.core.client

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import java.nio.file.Path

private val instance = requiredService<SurfPolarPaperInstance>()

abstract class SurfPolarPaperInstance {
    abstract val dataPath: Path

    // Lazy so that dataPath is only read once the subclass is fully initialized
    val configManager by lazy {
        PolarPaperConfig.createConfigManager(
            path = dataPath
        )
    }
    val config: PolarPaperConfig get() = configManager.config

    var storageProvider: StorageProvider? = null
        private set

    @OverridingMethodsMustInvokeSuper
    open suspend fun onLoad() {
        connect()
    }

    @OverridingMethodsMustInvokeSuper
    open suspend fun onEnable() {

    }

    @OverridingMethodsMustInvokeSuper
    open suspend fun onDisable() {
        try {
            SurfPolarPaperApi.unloadAllWorlds()
        } finally {
            disconnect()
        }
    }

    suspend fun connect() {
        disconnect() // Disconnect any existing storage provider before connecting a new one

        val storageProvider = config.storage.createStorageProvider(dataPath)
        storageProvider.connect()

        this.storageProvider = storageProvider
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
