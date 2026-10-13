pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.slne.surf.api.gradle.settings") version "+"
}

rootProject.name = "surf-polar-paper"

include("surf-polar-paper-api")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-common")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-paper")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-microservice")
include("surf-polar-paper-core:surf-polar-paper-core-common")
include("surf-polar-paper-core:surf-polar-paper-core-client")