package dev.slne.surf.polarpaper.core.client

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageType
import dev.slne.surf.polarpaper.core.client.lock.DisabledWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.RemoteWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.WorldLocks
import dev.slne.surf.polarpaper.core.client.rabbit.RabbitServices
import dev.slne.surf.polarpaper.core.client.rabbit.isRabbitMqAvailable
import dev.slne.surf.polarpaper.core.client.storage.StorageContext
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    open val config: PolarPaperConfig get() = configManager.config

    protected open val api: SurfPolarPaperApi get() = SurfPolarPaperApi.INSTANCE

    var storageProvider: StorageProvider? = null
        private set

    var worldLocks: WorldLocks = DisabledWorldLocks
        private set

    // Only set when database storage or world locking need it, the RabbitMQ client comes from an optional plugin
    private var rabbitServices: RabbitServices? = null
    private val lockRenewalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            // Only save: the server discards its worlds when it stops, and worlds with players in them cannot be
            // unloaded at this point because players are disconnected after plugins are disabled
            api.saveAllWorlds()
        } finally {
            disconnect()
        }
    }

    suspend fun connect() {
        disconnect() // Disconnect any existing storage provider before connecting a new one

        val config = config
        val rabbitServices = if (config.storage.storageType == PolarPaperStorageType.DATABASE || config.locking.enabled) {
            check(isRabbitMqAvailable()) {
                "DATABASE storage and world locking need the surf-rabbitmq plugin, which is not installed"
            }
            RabbitServices(dataPath)
        } else {
            null
        }

        val storageProvider = config.storage.createStorageProvider(
            StorageContext(dataPath) { checkNotNull(rabbitServices).databaseStorage }
        )

        rabbitServices?.connect()
        this.rabbitServices = rabbitServices

        storageProvider.connect()
        this.storageProvider = storageProvider

        if (config.locking.enabled) {
            val locks = RemoteWorldLocks(config.locking.resolveServerName(), checkNotNull(rabbitServices).worldLocks)
            locks.releaseLeftoverLocks()
            locks.startRenewing(lockRenewalScope)
            worldLocks = locks
        }
    }

    suspend fun disconnect() {
        try {
            worldLocks.close()
        } finally {
            worldLocks = DisabledWorldLocks

            storageProvider?.disconnect()
            storageProvider = null

            rabbitServices?.disconnect()
            rabbitServices = null
        }
    }

    companion object {
        val INSTANCE get() = instance
    }
}
