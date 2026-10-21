package dev.slne.surf.polarpaper.runtime.paper

import dev.slne.surf.polarpaper.core.client.SurfPolarPaperApiImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.every
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginDisableCloserTest {
    private val disabled = mockk<Plugin>(relaxed = true)
    private val other = mockk<Plugin>(relaxed = true)

    // The real event asks Bukkit for the current thread when it is built
    private fun event(plugin: Plugin) = mockk<PluginDisableEvent> { every { this@mockk.plugin } returns plugin }

    @Test
    fun `disabling a plugin closes only its instances`() = runTest {
        val own = mockk<SurfPolarPaperApiImpl>(relaxed = true)
        val foreign = mockk<SurfPolarPaperApiImpl>(relaxed = true)
        val closer = PluginDisableCloser(this) { plugin -> if (plugin === disabled) listOf(own) else listOf(foreign) }

        closer.onPluginDisable(event(disabled))
        closer.awaitPending()

        coVerify(exactly = 1) { own.close() }
        coVerify(exactly = 0) { foreign.close() }
    }

    @Test
    fun `awaitPending waits for a close that is still running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val api = mockk<SurfPolarPaperApiImpl>()
        coEvery { api.close() } coAnswers {
            gate.await()
            finished = true
        }
        val closer = PluginDisableCloser(this) { listOf(api) }

        closer.onPluginDisable(event(other))
        val waiting = launch { closer.awaitPending() }
        runCurrent()
        assertFalse(finished)

        gate.complete(Unit)
        waiting.join()

        assertTrue(finished)
    }

    @Test
    fun `a failing close does not break the event handler`() = runTest {
        val api = mockk<SurfPolarPaperApiImpl>()
        coEvery { api.close() } throws IllegalStateException("cannot save")
        val closer = PluginDisableCloser(this) { listOf(api) }

        closer.onPluginDisable(event(disabled))
        closer.awaitPending()

        coVerify(exactly = 1) { api.close() }
    }
}
