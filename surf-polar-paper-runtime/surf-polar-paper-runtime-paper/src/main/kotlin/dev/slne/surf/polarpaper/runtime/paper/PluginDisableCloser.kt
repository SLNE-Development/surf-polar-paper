package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.core.client.ApiInstances
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Closes the API instances of a plugin that gets disabled, so its worlds are saved and its game modes are freed.
 * Closing only saves, the worlds stay loaded.
 *
 * Closing runs in [scope] and not inline, because saving needs the region threads, which the disabling thread
 * would block. [awaitPending] lets the shutdown of this plugin wait for those closes.
 */
class PluginDisableCloser(
    private val scope: CoroutineScope,
    private val instancesOf: (Plugin) -> List<SurfPolarPaperApiImpl> = ApiInstances::forPlugin
) : Listener {
    private val log = logger()
    private val pending: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        for (api in instancesOf(event.plugin)) {
            val job = scope.launch {
                try {
                    api.close()
                } catch (exception: Exception) {
                    log.atSevere().withCause(exception).log("Failed to close the API of plugin %s", event.plugin.name)
                }
            }
            pending += job
            job.invokeOnCompletion { pending -= job }
        }
    }

    suspend fun awaitPending() = pending.toList().joinAll()
}
