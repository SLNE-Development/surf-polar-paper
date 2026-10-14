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
