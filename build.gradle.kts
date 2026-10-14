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

plugins {
    id("development-stack")
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

developmentStack {
    paperProject = ":surf-polar-paper-runtime:surf-polar-paper-runtime-paper"
    microserviceProject = ":surf-polar-paper-runtime:surf-polar-paper-runtime-microservice"
    serverDirectory = layout.projectDirectory.dir("surf-polar-paper-runtime/surf-polar-paper-runtime-paper/run")
    pluginConfigTemplates = mapOf(
        // Paper names the plugin data folder after the plugin, which is named after its project
        "surf-polar-paper-runtime-paper/polar-paper.yml" to layout.projectDirectory.file("docker/dev-server/polar-paper.yml"),
        "surf-core-paper/config.yml" to layout.projectDirectory.file("docker/dev-server/surf-core-paper.yml")
    )
    requiredServerPlugins = listOf(
        "polarpaper", "surf-api-paper-server", "surf-core-paper", "surf-rabbitmq-paper", "surf-redis-paper", "CommandAPI",
        "LuckPerms", "packetevents"
    )
}
