// Docker compose development stack (compose.yaml next to the applying build): SeaweedFS, PostgreSQL, RabbitMQ,
// the microservice and a Paper dev server. Configured through the developmentStack extension.

import dev.slne.surf.polarpaper.gradle.devstack.DeployDevelopmentPlugin
import dev.slne.surf.polarpaper.gradle.devstack.DevelopmentStackExtension

val developmentStack = extensions.create<DevelopmentStackExtension>("developmentStack")

val developmentStackGroup = "developmentStack"
val serverPluginsDirectory = developmentStack.serverDirectory.dir("plugins")

val deployDevelopmentPlugin = tasks.register<DeployDevelopmentPlugin>("deployDevelopmentPlugin") {
    group = developmentStackGroup
    description = "Copies the Paper plugin into the dev server's plugins folder and restarts the server if it is running."

    val shadowJar = project(developmentStack.paperProject.get()).tasks.named<Jar>("shadowJar")
    dependsOn(shadowJar)

    pluginJar = shadowJar.flatMap { it.archiveFile }
    deployedJar = serverPluginsDirectory.map { it.file("${rootProject.name}.jar") }
    composeDirectory = layout.projectDirectory
}

val deployDevelopmentConfig = tasks.register<Copy>("deployDevelopmentConfig") {
    group = developmentStackGroup
    description = "Copies the plugin config template into the dev server unless a config exists already."

    // Paper names the plugin data folder after the plugin, which is named after its project
    val pluginDataDirectory = serverPluginsDirectory.map { it.dir(project(developmentStack.paperProject.get()).name) }
    val template = developmentStack.pluginConfigTemplate
    onlyIf { !pluginDataDirectory.get().file(template.get().asFile.name).asFile.exists() }

    from(template)
    into(pluginDataDirectory)
}

val checkDevelopmentPlugins = tasks.register("checkDevelopmentPlugins") {
    group = developmentStackGroup
    description = "Fails when a plugin the dev server needs is missing from its plugins folder."

    val pluginsDirectory = serverPluginsDirectory
    val requiredPlugins = developmentStack.requiredServerPlugins
    doLast {
        val directory = pluginsDirectory.get().asFile
        val jars = directory.listFiles { file -> file.extension == "jar" }.orEmpty().map { it.name }
        val missing = requiredPlugins.get().filter { plugin -> jars.none { it.startsWith(plugin) } }

        check(missing.isEmpty()) {
            "Missing dev server plugins in $directory: ${missing.joinToString()}"
        }
    }
}

tasks.register<Exec>("startDevelopmentStack") {
    group = developmentStackGroup
    description = "Builds the plugin and microservice and starts the development stack."
    dependsOn(
        developmentStack.microserviceProject.map { "$it:shadowJar" },
        deployDevelopmentPlugin,
        deployDevelopmentConfig,
        checkDevelopmentPlugins
    )

    commandLine("docker", "compose", "up", "--detach", "--remove-orphans")
    doLast {
        println(
            """
            |Development stack started:
            |  Paper server        localhost:25565 (console: docker compose attach paper)
            |  SeaweedFS admin UI  http://localhost:23646
            |  SeaweedFS S3        http://localhost:8333
            |  RabbitMQ management http://localhost:15672
            |  PostgreSQL          localhost:5432
            |Ports and credentials can be changed in .env (see .env.example).
            """.trimMargin()
        )
    }
}

tasks.register<Exec>("stopDevelopmentStack") {
    group = developmentStackGroup
    description = "Stops the development stack and keeps its data."

    commandLine("docker", "compose", "down", "--remove-orphans")
}

tasks.register<Exec>("cleanDevelopmentStack") {
    group = developmentStackGroup
    description = "Stops the development stack and deletes the database, RabbitMQ and SeaweedFS data. The server folder is kept."

    commandLine("docker", "compose", "down", "--volumes", "--remove-orphans")
}
