pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/")
    }
}

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        // The surf plugins (including run-paper, which reacts to userdev) are loaded from the settings classpath,
        // so userdev has to live there too or run-paper cannot see its classes
        classpath("io.papermc.paperweight:paperweight-userdev:2.0.0-beta.24")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.slne.surf.api.gradle.settings") version "2.2.1"
}

rootProject.name = "surf-polar-paper"

include("surf-polar-paper-api")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-common")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-paper")
include("surf-polar-paper-runtime:surf-polar-paper-runtime-microservice")
include("surf-polar-paper-core:surf-polar-paper-core-common")
include("surf-polar-paper-core:surf-polar-paper-core-client")