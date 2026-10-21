package dev.slne.surf.polarpaper.core.client

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import dev.slne.surf.polarpaper.core.client.config.storage.PolarPaperStorageType
import dev.slne.surf.polarpaper.core.client.lock.DisabledWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.RemoteWorldLocks
import dev.slne.surf.polarpaper.core.client.lock.WorldLocks
import dev.slne.surf.polarpaper.core.client.rabbit.RabbitServices
import dev.slne.surf.polarpaper.core.client.rabbit.isRabbitMqAvailable
import dev.slne.surf.polarpaper.core.client.storage.StorageContext
import dev.slne.surf.polarpaper.core.client.storage.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path

private val instance = requiredService<SurfPolarPaperInstance>()

abstract class SurfPolarPaperInstance {
    abstract val dataPath: Path

    /**
     * Name of this server, unique in the network. World locks are held under it.
     */
    abstract val serverName: String

    // Lazy so that dataPath is only read once the subclass is fully initialized
    val configManager by lazy {
        PolarPaperConfig.createConfigManager(
            path = dataPath
        )
    }
    open val config: PolarPaperConfig get() = configManager.config

    protected open val apis: List<SurfPolarPaperApiImpl> get() = ApiInstances.all

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
            // close() only saves: the server discards its worlds when it stops, and worlds with players in them
            // cannot be unloaded at this point. The locks are released by disconnect().
            closeAllApis()
        } finally {
            disconnect()
        }
    }

    /**
     * Closes every API instance and keeps going when one fails, so a single broken plugin cannot keep the
     * others from saving. All failures are thrown together afterwards.
     */
    private suspend fun closeAllApis() {
        val instances = apis
        val failures = mutableListOf<Exception>()

        for (api in instances) {
            try {
                api.close()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failures += exception
            }
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to close ${failures.size} of ${instances.size} API instances").apply {
                failures.forEach(::addSuppressed)
            }
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
            val locks = RemoteWorldLocks(serverName, checkNotNull(rabbitServices).worldLocks)
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
