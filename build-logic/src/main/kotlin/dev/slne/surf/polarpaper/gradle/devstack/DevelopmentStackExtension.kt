package dev.slne.surf.polarpaper.gradle.devstack

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configures the docker compose development stack of the development-stack plugin.
 */
abstract class DevelopmentStackExtension {
    /** Path of the Paper plugin project whose shadow jar is deployed to the dev server. */
    abstract val paperProject: Property<String>

    /** Path of the microservice project whose shadow jar the compose file runs. */
    abstract val microserviceProject: Property<String>

    /** Folder the Paper container runs from. */
    abstract val serverDirectory: DirectoryProperty

    /** Plugin config copied into the server's plugin data folder when none exists yet. */
    abstract val pluginConfigTemplate: RegularFileProperty

    /** File name prefixes of plugins the server needs besides this one. They have to be put into plugins/ manually. */
    abstract val requiredServerPlugins: ListProperty<String>
}
