import dev.slne.surf.microservice.gradle.plugin.rabbit.RabbitModule

plugins {
    id("dev.slne.surf.api.gradle.core")
    id("dev.slne.surf.microservice")
}

surfCoreApi {
    withSurfRedis()
    withSurfDatabaseR2dbc("2.3.4", "dev.slne.surf.polarpaper.runtime.libs.database")
}

surfMicroservice {
    withMicroserviceApi()
    withRabbitModule(RabbitModule.SERVER_API, true)
}

dependencies {
    api(projects.surfPolarPaperCore.surfPolarPaperCoreCommon)
    // The microservice is not a plugin, so it bundles the standalone Redis runtime instead of using a platform plugin
    implementation("dev.slne.surf.redis:surf-redis-standalone:1.12.0")

    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
}

tasks.named<Jar>("shadowJar") {
    // The bundled database and Redis runtimes exceed the 65535 entries of a plain zip
    isZip64 = true
}