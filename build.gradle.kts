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
    // Adventure is provided by the platform at runtime, unit tests need it on their own classpath
    plugins.withType<JavaPlugin> {
        dependencies {
            "testImplementation"("net.kyori:adventure-api:5.2.0")
            "testImplementation"("net.kyori:adventure-text-serializer-plain:5.2.0")
        }
    }
}