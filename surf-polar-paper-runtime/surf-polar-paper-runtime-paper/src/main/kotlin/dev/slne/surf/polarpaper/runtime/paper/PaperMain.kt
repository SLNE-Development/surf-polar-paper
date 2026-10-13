package dev.slne.surf.polarpaper.runtime.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.runtime.paper.commands.worldCommand
import org.bukkit.plugin.java.JavaPlugin

class PaperMain : SuspendingJavaPlugin() {
    override suspend fun onLoadAsync() {
        SurfPolarPaperInstance.INSTANCE.onLoad()
    }

    override suspend fun onEnableAsync() {
        SurfPolarPaperInstance.INSTANCE.onEnable()

        worldCommand()
    }

    override suspend fun onDisableAsync() {
        SurfPolarPaperInstance.INSTANCE.onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)