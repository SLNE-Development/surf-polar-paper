buildscript {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public")
    }
    dependencies {
        classpath("dev.slne.surf.api:surf-api-gradle-plugin:2.2.1")
        classpath("dev.slne.surf.microservice:surf-microservice-gradle-plugin:2.1.1")
    }
}

allprojects {
    group = "dev.slne.surf.polarpaper"
    version = findProperty("version") as String
}

subprojects {
    // The platform provides libraries such as adventure and flogger at runtime, which the published
    // runtime variants do not bring along, so unit tests run with the main compile classpath as well
    plugins.withType<JavaPlugin> {
        tasks.named<Test>("test") {
            classpath += configurations.getByName("compileClasspath")
        }

        dependencies {
            "testRuntimeOnly"("com.google.flogger:flogger-system-backend:0.9")
        }
    }
}

// Development stack (compose.yaml): SeaweedFS, PostgreSQL, RabbitMQ, the microservice and a Paper dev server

val developmentStackGroup = "developmentStack"
val paperRunDirectory = layout.projectDirectory.dir("surf-polar-paper-runtime/surf-polar-paper-runtime-paper/run")
val paperPluginsDirectory = paperRunDirectory.dir("plugins")

// Plugins the dev server needs besides this one. They are not downloaded automatically, put them into run/plugins.
val requiredDevelopmentPlugins = listOf("polarpaper", "surf-api-paper-server", "CommandAPI", "LuckPerms", "packetevents")

val deployDevelopmentPlugin by tasks.registering(Copy::class) {
    group = developmentStackGroup
    description = "Copies the Paper plugin into the dev server's run/plugins folder."
    dependsOn(":surf-polar-paper-runtime:surf-polar-paper-runtime-paper:shadowJar")

    from("surf-polar-paper-runtime/surf-polar-paper-runtime-paper/build/libs") {
        include("surf-polar-paper-runtime-paper-${project.version}-all.jar")
    }
    into(paperPluginsDirectory)
    rename { "surf-polar-paper.jar" }
}

val deployDevelopmentConfig by tasks.registering(Copy::class) {
    group = developmentStackGroup
    description = "Copies the dev server plugin config into run/plugins unless one exists already."

    val pluginDataDirectory = paperPluginsDirectory.dir("surf-polar-paper-runtime-paper")
    onlyIf { !pluginDataDirectory.file("polar-paper.yml").asFile.exists() }

    from("docker/dev-server/polar-paper.yml")
    into(pluginDataDirectory)
}

val checkDevelopmentPlugins by tasks.registering {
    group = developmentStackGroup
    description = "Fails when a plugin the dev server needs is missing from run/plugins."

    val pluginsDirectory = paperPluginsDirectory.asFile
    doLast {
        val jars = pluginsDirectory.listFiles { file -> file.extension == "jar" }.orEmpty().map { it.name }
        val missing = requiredDevelopmentPlugins.filter { plugin -> jars.none { it.startsWith(plugin) } }

        check(missing.isEmpty()) {
            "Missing dev server plugins in $pluginsDirectory: ${missing.joinToString()}"
        }
    }
}

tasks.register<Exec>("startDevelopmentStack") {
    group = developmentStackGroup
    description = "Builds the plugin and microservice and starts the development stack."
    dependsOn(
        ":surf-polar-paper-runtime:surf-polar-paper-runtime-microservice:shadowJar",
        deployDevelopmentPlugin,
        deployDevelopmentConfig,
        checkDevelopmentPlugins
    )

    commandLine("docker", "compose", "up", "--detach", "--remove-orphans")
    doLast {
        println(
            """
            |Development stack started:
            |  Paper server        localhost:25565 (console: docker attach surf-polar-paper-paper-1)
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
    description = "Stops the development stack and deletes the database, RabbitMQ and SeaweedFS data. run/ is kept."

    commandLine("docker", "compose", "down", "--volumes", "--remove-orphans")
}