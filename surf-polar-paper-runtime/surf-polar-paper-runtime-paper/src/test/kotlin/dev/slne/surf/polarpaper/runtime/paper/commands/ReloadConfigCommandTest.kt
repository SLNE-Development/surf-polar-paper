package dev.slne.surf.polarpaper.runtime.paper.commands

import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException
import dev.slne.surf.polarpaper.core.client.SurfPolarPaperInstance
import dev.slne.surf.polarpaper.core.client.config.PolarPaperConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReloadConfigCommandTest {
    private val instance = mockk<SurfPolarPaperInstance>(relaxed = true) {
        every { config } returns PolarPaperConfig()
    }
    private val sender = mockk<CommandSender>(relaxed = true)

    @Test
    fun `reload reads the config again and reconnects the storage`() = runTest {
        reloadConfig(instance, sender)

        coVerify(exactly = 1) { instance.reloadConfig(true) }
        verify { sender.sendMessage(any<Component>()) }
    }

    @Test
    fun `a failing reload is reported as a command error`() = runTest {
        coEvery { instance.reloadConfig(any()) } throws IllegalArgumentException("Storage config for S3 is not provided")

        assertThrows<WrapperCommandSyntaxException> { reloadConfig(instance, sender) }
    }
}
