package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.polarpaper.core.client.ApiInstances
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Closes the API instances of a plugin that gets disabled, so its worlds are saved and its game modes are freed.
 *
 * While the server stops, the instances are closed right inside the event, on the disabling thread and without
 * dispatching anywhere: the main thread no longer ticks then, so nothing that needs a dispatcher could ever run. On
 * Paper that thread owns every region, so chunks are prepared inline. On Folia this is best effort. The worlds are only
 * saved and stay loaded, because Paper's world manager is already closed then.
 *
 * When a plugin is disabled at runtime, closing runs in [scope] instead, because saving needs the region threads
 * that a blocked disabling thread would starve. It saves and unloads the worlds and releases their locks, so a plugin
 * that is enabled again starts clean; a world that Bukkit refuses to unload stays loaded, saved and locked.
 * [awaitPending] lets the shutdown of this plugin wait for those closes.
 */
class PluginDisableCloser(
    private val scope: CoroutineScope,
    private val isStopping: () -> Boolean = Bukkit::isStopping,
    private val instancesOf: (Plugin) -> List<SurfPolarPaperApiImpl> = ApiInstances::forPlugin
) : Listener {
    private val log = logger()
    private val pending: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        val stopping = isStopping()

        for (api in instancesOf(event.plugin)) {
            if (stopping) {
                closeNow(api, event.plugin)
                continue
            }

            val job = scope.launch { close(api, event.plugin) }
            pending += job
            job.invokeOnCompletion { pending -= job }
        }
    }

    private fun closeNow(api: SurfPolarPaperApiImpl, plugin: Plugin) {
        // runBlocking only runs what the saving resumes into this thread, nothing is handed to a plugin dispatcher
        runBlocking {
            try {
                api.closeForShutdown()
            } catch (exception: Exception) {
                log.atSevere().withCause(exception).log("Failed to close the API of plugin %s", plugin.name)
            }
        }
    }

    private suspend fun close(api: SurfPolarPaperApiImpl, plugin: Plugin) {
        try {
            api.close()
        } catch (exception: Exception) {
            log.atSevere().withCause(exception).log("Failed to close the API of plugin %s", plugin.name)
        }
    }

    /** Waits for the closes of plugins disabled at runtime, at most [timeout], and logs what it gives up on. */
    suspend fun awaitPending(timeout: Duration = 30.seconds) {
        val jobs = pending.toList()
        if (jobs.isEmpty()) return

        if (withTimeoutOrNull(timeout) { jobs.joinAll() } == null) {
            val abandoned = jobs.count { it.isActive }
            log.atSevere().log("Gave up waiting for %s API closes after %s, their worlds may not be saved", abandoned, timeout)
        }
    }
}
