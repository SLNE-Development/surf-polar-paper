import dev.slne.surf.api.gradle.util.registerRequired

plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.polarpaper.runtime.paper.PaperMain")

    serverDependencies {
        registerRequired("polarpaper")
    }

    testing {
        // MockBukkit is not published for this Paper version, Bukkit is mocked with MockK instead
        mockBukkit = false
    }
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreClient)
    compileOnlyApi("live.minehub:polarpaper:2.3.1")

    testImplementation("live.minehub:polarpaper:2.3.1")
}

// surf-paper-api provides these at runtime. A second bundled copy (pulled in by the AWS SDK) breaks every class
// shared with it, e.g. kotlin.coroutines.Continuation in SuspendingJavaPlugin, with a LinkageError on load.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.slf4j")
}
