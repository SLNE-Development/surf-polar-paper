import java.io.ByteArrayInputStream
import java.time.Duration
import dev.slne.surf.api.gradle.util.registerRequired
import dev.slne.surf.api.gradle.util.registerSoft
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

repositories {
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.momirealms.net/releases")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.polarpaper.integration.IntegrationTestPlugin")

    serverDependencies {
        registerRequired("surf-polar-paper-runtime-paper")
        // Needed for the Polar checks of the pipeline scenario
        registerRequired("polarpaper")

        // Soft dependencies with load BEFORE make this plugin disable first, so its worlds are saved while the
        // furniture plugins still run
        registerSoft("Nexo")
        registerSoft("CraftEngine")
    }

    testing {
        enabled = false
    }
}

dependencies {
    compileOnly(projects.surfPolarPaperApi)
    compileOnly("live.minehub:polarpaper:2.3.1")
    compileOnly("com.nexomc:nexo:1.29.1")
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")
}

// ---- Real server run -------------------------------------------------------------------------------------------

val pinnedPaperBuild = 211

// Jars from the surf reposilite. Not transitive: they are whole plugins, not libraries.
val serverPlugins by configurations.creating {
    isTransitive = false
}

dependencies {
    serverPlugins("dev.slne.surf.api:surf-api-paper-server:3.50.0:all")
}

val serverDirectory = layout.buildDirectory.dir("integration-server")
val resultsFile = serverDirectory.map { it.file("plugins/surf-polar-paper-integration-test/results.json") }

// Nexo is premium and cannot be downloaded: the jar is passed by the NEXO_JAR variable or -PnexoJar
val nexoJar: File? = (System.getenv("NEXO_JAR") ?: findProperty("nexoJar") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }

// The task of the runtime project is only registered once that project is evaluated
evaluationDependsOn(":surf-polar-paper-runtime:surf-polar-paper-runtime-paper")
val runtimeShadowJar = project(":surf-polar-paper-runtime:surf-polar-paper-runtime-paper").tasks.named("shadowJar")

val prepareIntegrationServer by tasks.registering(Sync::class) {
    group = "verification"
    description = "Lays out the server directory with its configuration"
    from("src/server")
    into(serverDirectory)
    outputs.upToDateWhen { false }
    doFirst { delete(resultsFile) }
    // The server and its caches are written during a run and not part of the layout
    preserve { include("cache/**", "libraries/**", "versions/**") }
}

fun RunServer.configureIntegrationServer() {
    group = "verification"
    minecraftVersion("26.3")
    build(pinnedPaperBuild)
    runDirectory.set(serverDirectory)

    pluginJars.from(tasks.named("jar"), runtimeShadowJar, serverPlugins)
    nexoJar?.let { pluginJars.from(it) }

    downloadPlugins {
        github("MinehubMC", "PolarPaper", "2.3.1", "polarpaper-2.3.1.jar")
        modrinth("commandapi", "12.1.0")
        // Required by surf-api-paper-server
        modrinth("luckperms", "v5.5.71-bukkit")
        modrinth("packetevents", "2.14.0+spigot")
        modrinth("craftengine", "26.9.2")
    }

    // The scenarios need no console, and the server stops itself through Bukkit.shutdown()
    args("--nogui")
    environment("SURF_POLAR_PAPER_SERVER_NAME", "integration-test")
    standardInput = ByteArrayInputStream(ByteArray(0))
    // A failing scenario is reported by integrationTest from results.json, a crashed server by the missing file
    isIgnoreExitValue = true
    // Every invocation is a new run, whatever the jars and the layout look like
    outputs.upToDateWhen { false }
    timeout.set(Duration.ofMinutes((findProperty("serverTimeoutMinutes") as String?)?.toLong() ?: 10))
}

val runIntegrationServer by tasks.registering(RunServer::class) {
    description = "Starts a real Paper server with the test plugin and waits until it stops"
    dependsOn(prepareIntegrationServer, tasks.named("jar"), runtimeShadowJar)
    configureIntegrationServer()
}

// Starts the same server again on the same directory. The first run stopped with a modified world loaded, and this
// run checks that the shutdown saved it.
val runShutdownVerification by tasks.registering(RunServer::class) {
    description = "Starts the server a second time to check what the shutdown of the first run saved"
    dependsOn(runIntegrationServer)
    configureIntegrationServer()
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs the scenarios on a real Paper server. Not part of check or build."

    dependsOn(runShutdownVerification)
    outputs.upToDateWhen { false }
    val results = resultsFile

    doLast {
        val file = results.get().asFile
        if (!file.exists()) {
            throw GradleException("The server did not write ${file}. Check the server log in ${file.parentFile.parentFile.parentFile}/logs")
        }

        val text = file.readText()
        logger.lifecycle(text)

        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val scenarios = parsed["scenarios"] as List<Map<String, Any?>>
        val failed = scenarios.filter { it["result"] == "FAIL" }
        if (failed.isNotEmpty()) {
            throw GradleException("Failed scenarios: " + failed.joinToString { it["name"].toString() })
        }
        if (scenarios.none { it["name"] == "shutdown-saves-loaded-world" }) {
            throw GradleException("The second server run did not verify the shutdown save")
        }
        if (parsed["finished"] != true) {
            throw GradleException("The scenarios did not all run to the end")
        }
    }
}
