package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.core.api.common.SurfCoreApi
import org.bukkit.Bukkit

/**
 * The only class that touches SurfCore. It is kept apart so that no SurfCore class is loaded while the plugin is
 * missing: the JVM links [SurfCoreApi] lazily, when [read] first runs, which [lookup] only does for an installed plugin.
 */
internal object SurfCoreServerName {
    private const val PLUGIN_NAME = "surf-core-paper"

    /** The server name from surf-core-paper, or null when that plugin is not installed. */
    fun lookup(): String? {
        // Loaded is enough: the instance connects in onLoad, when plugins are loaded but not yet enabled
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return null
        return read()
    }

    private fun read(): String = SurfCoreApi.getCurrentServerName()
}
