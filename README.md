# surf-polar-paper

Stores and loads [Polar](https://github.com/MinehubMC/polarpaper) worlds for Paper servers. Worlds are kept per game mode
in one of three storages (disk, S3 or a database behind a microservice), and are locked so each world is only loaded on
one server at a time.

## Modules

| Module                                | Purpose                                                                                |
|---------------------------------------|----------------------------------------------------------------------------------------|
| `surf-polar-paper-api`                | Public API for other plugins, published to `reposilite.slne.dev/releases`              |
| `surf-polar-paper-core-common`        | RabbitMQ contracts shared by the plugin and the microservice                           |
| `surf-polar-paper-core-client`        | Storage providers, world locks and the API implementation                              |
| `surf-polar-paper-runtime-paper`      | The Paper plugin, attached to every GitHub release                                     |
| `surf-polar-paper-runtime-microservice` | Stores worlds in PostgreSQL and owns the world locks (PostgreSQL + Valkey)           |

## Using the API

```kotlin
repositories {
    maven("https://reposilite.slne.dev/releases")
}

dependencies {
    compileOnly("dev.slne.surf.polarpaper:surf-polar-paper-api:<version>")
}
```

```kotlin
val api = SurfPolarPaperApi.INSTANCE

val world = api.loadWorld(worldUuid, "skyblock")
val bukkitWorld = api.getPlatformWorld<World>(world)

api.saveWorld(world)
api.unloadWorld(world)
```

Loading or creating a world that is loaded on another server throws a `WorldLockedException` naming that server.

## Installing the plugin

Download `surf-polar-paper-runtime-paper-<version>-all.jar` from the
[releases](https://github.com/SLNE-Development/surf-polar-paper/releases) and put it into `plugins/` together with:

- [polarpaper](https://github.com/MinehubMC/polarpaper)
- surf-api-paper-server
- [surf-core-paper](https://github.com/SLNE-Development/surf-core/releases), world locks are held under its server name
- [CommandAPI](https://commandapi.jorel.dev)
- [surf-rabbitmq-paper](https://github.com/SLNE-Development/surf-rabbitmq/releases), needed for database storage and
  world locking

### Configuration

`plugins/surf-polar-paper-runtime-paper/polar-paper.yml`:

```yaml
# The game mode this server hosts worlds for.
game-mode-identifier: default
storage:
    # DISK, DATABASE or S3. Only the matching section below is used.
    storage-type: S3
    disk:
        path: worlds
    s3:
        endpoint: https://s3.example.com
        region: us-east-1
        bucket: worlds
        access-key: ...
        secret-key: ...
    # Goes through RabbitMQ to the microservice, configured via the SURF_RABBITMQ_* variables
    database: {}
locking:
    # Locks worlds so each is only loaded on one server at a time. Needs RabbitMQ and the microservice.
    enabled: true
```

### Commands

All commands need the permission `surf.polarpaper.runtime.command.world`.

| Command                                              | Description                                 |
|------------------------------------------------------|---------------------------------------------|
| `/sworld create <gameMode>`                          | Creates an empty world                      |
| `/sworld load <gameMode> <uuid>`                     | Loads a stored world                        |
| `/sworld join <gameMode> <uuid>`                     | Teleports you into a loaded world           |
| `/sworld unload <gameMode> <uuid>`                   | Saves and unloads a world                   |
| `/sworld delete <gameMode> <uuid>`                   | Deletes a world from storage                |
| `/sworld list <gameMode> [showLoadedOnly]`           | Lists stored worlds                         |

## World locking

The microservice keeps the authoritative lock in the PostgreSQL table `world_lock` and caches the current owner in
Valkey for 10 seconds, so requests for a world held by another server rarely reach the database. Several microservice
instances can run side by side, since all lock state lives in the shared database and Valkey.

A lock is a 60 second lease that the plugin renews every 20 seconds, so the worlds of a crashed server become free
within a minute. A server that lost a lock refuses to save that world instead of overwriting the new owner's changes.

## The microservice

`surf-polar-paper-runtime-microservice-<version>-all.jar` is configured through environment variables:

- `SURF_DATABASE_*` for PostgreSQL
- `SURF_RABBITMQ_*` for RabbitMQ
- `SURF_REDIS_HOST`, `SURF_REDIS_PORT`, `SURF_REDIS_PASSWORD` for Valkey

It is also published as a Docker image, built by CI from the Dockerfile that the microservice Gradle plugin generates
(`./gradlew generateMicroserviceDockerfile`):

```shell
docker pull ghcr.io/slne-development/surf-polar-paper-microservice:<version>
```

Docker's default seccomp profile blocks io_uring, so pass the Netty switches through `JAVA_OPTS`:
`-Dio.netty.transport.noNative=true -Ddev.slne.surf.rabbitmq.shaded.io.netty.transport.noNative=true -Ddev.slne.surf.redis.shaded.io.netty.transport.noNative=true`.

## Development

Requires Java 25. The microservice tests start PostgreSQL and Valkey with Testcontainers, so they need Docker.

```shell
./gradlew build
```

## Releasing

Pushing to a `version/**` branch runs `.github/workflows/publish.yml`, which

- publishes the API to Reposilite,
- creates the GitHub release `v<version>` with the Paper plugin attached,
- pushes the microservice image to `ghcr.io/slne-development/surf-polar-paper-microservice` as `<version>` and `latest`.

The version comes from `gradle.properties`.
