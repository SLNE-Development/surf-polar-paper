package dev.slne.surf.polarpaper.integration

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import com.github.shynixn.mccoroutine.folia.launch
import dev.slne.surf.polarpaper.integration.scenarios.ShutdownSavesLoadedWorld
import dev.slne.surf.polarpaper.integration.scenarios.allScenarios
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Runs the scenarios once the server is up, writes `results.json` and stops the server. The Gradle task
 * `integrationTest` starts the server and reads the file.
 */
class IntegrationTestPlugin : SuspendingJavaPlugin(), Listener {
    private val results = mutableListOf<ScenarioResult>()
    private var verifying = false
    private val writer by lazy { ResultWriter(dataPath.resolve(RESULTS_FILE)) }

    override suspend fun onEnableAsync() {
        server.pluginManager.registerEvents(this, this)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type != ServerLoadEvent.LoadType.STARTUP) return

        launch {
            try {
                val shutdown = ShutdownSavesLoadedWorld(this@IntegrationTestPlugin)
                verifying = shutdown.hasPendingVerification()
                if (verifying) verifyShutdown(shutdown) else runAll(shutdown)
            } catch (throwable: Throwable) {
                logger.severe("The run broke: $throwable")
                // The first run did not finish, so the Gradle task fails on the flag. The verifying run must not
                // overwrite the results of the first one.
                if (results.isNotEmpty() || !verifying) writer.write(results, finished = false)
            } finally {
                Bukkit.getGlobalRegionScheduler().run(this@IntegrationTestPlugin) { Bukkit.shutdown() }
            }
        }
    }

    /** Second server run: checks what the shutdown of the first run saved, and adds it to the first run's results. */
    private suspend fun verifyShutdown(shutdown: ShutdownSavesLoadedWorld) {
        val context = ScenarioContext(this)
        val start = TimeSource.Monotonic.markNow()
        val result = try {
            shutdown.verify(context)
            ScenarioResult(shutdown.name, Outcome.PASS, durationMillis = start.elapsedNow().inWholeMilliseconds)
        } catch (throwable: Throwable) {
            failure(shutdown.name, throwable, start.elapsedNow().inWholeMilliseconds)
        } finally {
            context.cleanUp()
        }
        writer.append(result)
        logger.info("Scenario ${result.name}: ${result.outcome}${result.message?.let { " ($it)" }.orEmpty()}")
    }

    private suspend fun runAll(shutdown: ShutdownSavesLoadedWorld) {
        val context = ScenarioContext(this)
        val scenarios = allScenarios(this)
        logger.info("Running ${scenarios.size} scenarios")

        for (scenario in scenarios) {
            val result = run(scenario, context)
            results += result
            writer.write(results, finished = false)
            logger.info("Scenario ${result.name}: ${result.outcome}${result.message?.let { " ($it)" }.orEmpty()}")
        }

        // Leaves a modified world loaded; the server stop right after the results saves it, and verifyShutdown checks
        try {
            shutdown.prepare(context)
        } catch (throwable: Throwable) {
            results += failure(shutdown.name, throwable, 0)
        }
        writer.write(results, finished = true)
    }

    private suspend fun run(scenario: Scenario, context: ScenarioContext): ScenarioResult {
        scenario.skipReason()?.let { return ScenarioResult(scenario.name, Outcome.SKIPPED, it) }

        val start = TimeSource.Monotonic.markNow()
        return try {
            withTimeout(SCENARIO_TIMEOUT) { scenario.run(context) }
            ScenarioResult(scenario.name, Outcome.PASS, durationMillis = start.elapsedNow().inWholeMilliseconds)
        } catch (exception: CancellationException) {
            // A timeout cancels the scenario with this
            failure(scenario.name, exception, start.elapsedNow().inWholeMilliseconds)
        } catch (throwable: Throwable) {
            failure(scenario.name, throwable, start.elapsedNow().inWholeMilliseconds)
        } finally {
            context.cleanUp()
        }
    }

    private fun failure(name: String, throwable: Throwable, millis: Long): ScenarioResult {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return ScenarioResult(name, Outcome.FAIL, throwable.message ?: throwable.toString(), trace, millis)
    }

    companion object {
        const val RESULTS_FILE = "results.json"
        private val SCENARIO_TIMEOUT = 2.minutes
    }
}
