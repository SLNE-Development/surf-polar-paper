package dev.slne.surf.polarpaper.core.client

import com.google.auto.service.AutoService
import dev.slne.surf.polarpaper.api.SurfPolarPaperApi
import dev.slne.surf.polarpaper.api.SurfPolarPaperApiFactory
import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import org.bukkit.plugin.Plugin

@AutoService(SurfPolarPaperApiFactory::class)
class SurfPolarPaperApiFactoryImpl : SurfPolarPaperApiFactory {
    override fun create(plugin: Plugin, settings: PolarPaperSettings): SurfPolarPaperApi =
        // Claims before creating, so a conflict leaves nothing behind
        ApiInstances.claimAndRegister(plugin, settings) {
            SurfPolarPaperApiImpl(
                plugin = plugin,
                settings = settings,
                storageProviderSupplier = { SurfPolarPaperInstance.INSTANCE.storageProvider },
                platformBridgeSupplier = { PlatformWorldBridge.INSTANCE },
                worldLocksSupplier = { SurfPolarPaperInstance.INSTANCE.worldLocks }
            )
        }
}
