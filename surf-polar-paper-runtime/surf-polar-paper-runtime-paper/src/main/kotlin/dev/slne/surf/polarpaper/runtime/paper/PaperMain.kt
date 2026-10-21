package dev.slne.surf.polarpaper.runtime.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import com.github.shynixn.mccoroutine.folia.scope
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.world.PlatformWorldBridge
import dev.slne.surf.polarpaper.runtime.paper.commands.worldCommand
import org.bukkit.plugin.java.JavaPlugin

class PaperMain : SuspendingJavaPlugin() {
    private val disableCloser by lazy { PluginDisableCloser(scope) }

    override suspend fun onLoadAsync() {
        SurfPolarPaperInstance.INSTANCE.onLoad()
    }

    override suspend fun onEnableAsync() {
        SurfPolarPaperInstance.INSTANCE.onEnable()

        server.pluginManager.registerEvents(disableCloser, this)
        (PlatformWorldBridge.INSTANCE as? PlatformWorldBridgePaper)?.let {
            server.pluginManager.registerEvents(ChunkUnloadListener(it), this)
        }
        worldCommand()
    }

    override suspend fun onDisableAsync() {
        // Closes started by plugins that were disabled before this one must finish before the storage disconnects
        disableCloser.awaitPending()
        SurfPolarPaperInstance.INSTANCE.onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)
