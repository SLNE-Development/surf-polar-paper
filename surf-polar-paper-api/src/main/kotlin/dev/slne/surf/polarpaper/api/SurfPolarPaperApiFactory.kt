package dev.slne.surf.polarpaper.api

import dev.slne.surf.polarpaper.api.settings.PolarPaperSettings
import org.bukkit.plugin.Plugin

/** Service-loaded implementation hook behind [SurfPolarPaperApi.create]. */
interface SurfPolarPaperApiFactory {
    fun create(plugin: Plugin, settings: PolarPaperSettings): SurfPolarPaperApi
}
