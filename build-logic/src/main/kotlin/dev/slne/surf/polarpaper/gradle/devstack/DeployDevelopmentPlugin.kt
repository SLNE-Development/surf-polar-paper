package dev.slne.surf.polarpaper.gradle.devstack

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Copies the plugin jar into the dev server's plugins folder. A running dev server is stopped first, because replacing
 * the jar under a loaded plugin breaks its class loading, and started again afterwards.
 */
abstract class DeployDevelopmentPlugin : DefaultTask() {
    @get:InputFile
    abstract val pluginJar: RegularFileProperty

    @get:OutputFile
    abstract val deployedJar: RegularFileProperty

    /** Folder containing the compose file. */
    @get:Internal
    abstract val composeDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun deploy() {
        val serverWasRunning = isServerRunning()

        if (serverWasRunning) compose("stop", SERVER_SERVICE)
        pluginJar.get().asFile.copyTo(deployedJar.get().asFile, overwrite = true)
        if (serverWasRunning) compose("start", SERVER_SERVICE)
    }

    private fun isServerRunning(): Boolean {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir = composeDirectory.get().asFile
            commandLine("docker", "compose", "ps", "--status", "running", "--services")
            standardOutput = output
            errorOutput = OutputStream.nullOutputStream()
            isIgnoreExitValue = true
        }

        return result.exitValue == 0 && output.toString().lines().any { it.trim() == SERVER_SERVICE }
    }

    private fun compose(vararg arguments: String) {
        execOperations.exec {
            workingDir = composeDirectory.get().asFile
            commandLine("docker", "compose", *arguments)
        }
    }

    companion object {
        /** Name of the Paper server service in the compose file. */
        const val SERVER_SERVICE = "paper"
    }
}
