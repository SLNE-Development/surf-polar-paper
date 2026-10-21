import dev.slne.surf.api.gradle.util.registerRequired
import dev.slne.surf.api.gradle.util.registerSoft

plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}


// run-paper registers a runDevBundleServer task as soon as userdev is applied, with a version that cannot be changed.
// The surf plugin sets the version of every RunServer task, so userdev is applied after its configuration has run;
// the task then stays unrealized and is not needed here.
afterEvaluate {
    apply(plugin = "io.papermc.paperweight.userdev")
    dependencies.add("paperweightDevelopmentBundle", "io.papermc.paper:dev-bundle:26.3.build.211-stable")
}

repositories {
    // The surf reposilite proxy serves the poms of the dev bundle and its tooling (mache, codebook) but not their
    // zips, so everything under io.papermc is taken from papermc directly
    exclusiveContent {
        forRepository { maven("https://repo.papermc.io/repository/maven-public/") }
        filter { includeGroupAndSubgroups("io.papermc") }
    }
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.momirealms.net/releases")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.polarpaper.runtime.paper.PaperMain")

    serverDependencies {
        registerRequired("polarpaper")

        // Optional: the server name for world locks comes from it, or from SURF_POLAR_PAPER_SERVER_NAME or the
        // surf.polarpaper.server-name system property
        registerSoft("surf-core-paper")

        // Only needed for DATABASE storage, which talks to the microservice through RabbitMQ
        registerSoft("surf-rabbitmq-paper")

        // Furniture plugins whose furniture entities are migrated together with the chunk data
        registerSoft("Nexo")
        registerSoft("CraftEngine")
    }

    testing {
        // MockBukkit is not published for this Paper version, Bukkit is mocked with MockK instead
        mockBukkit = false
    }
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreClient)
    // PolarWorldAccess and PolarChunk.BlockEntity expose NMS types. Paper 26.x is unobfuscated, so the
    // server classes are only needed to compile against and nothing is remapped or bundled into our jar.

    compileOnlyApi("live.minehub:polarpaper:2.3.1")

    // Only touched through SurfCoreServerName, so the plugin works without surf-core-paper installed
    compileOnly("dev.slne.surf.core:surf-core-api-paper:+")

    compileOnly("com.nexomc:nexo:1.29.1")
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")

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
