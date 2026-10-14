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
    # Name this server holds world locks under, unique per server. Empty uses the hostname.
    server-name: ""
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

## Development

Requires Java 25 and Docker.

```shell
./gradlew build                  # build and run all tests (the microservice tests start Testcontainers)
./gradlew startDevelopmentStack  # start PostgreSQL, RabbitMQ, Valkey, SeaweedFS, the microservice and a Paper server
./gradlew deployDevelopmentPlugin  # rebuild the plugin and restart the Paper server with it
./gradlew stopDevelopmentStack   # stop the stack
./gradlew cleanDevelopmentStack  # stop the stack and delete its volumes
```

The Paper server runs from `surf-polar-paper-runtime/surf-polar-paper-runtime-paper/run/`. Its `plugins/` folder needs
the plugins listed under [Installing the plugin](#installing-the-plugin) plus LuckPerms and packetevents.
`startDevelopmentStack` names any that are missing. Copy `.env.example` to `.env` to change ports and credentials.

| Service          | Address                    |
|------------------|----------------------------|
| Paper            | `localhost:25565`          |
| RabbitMQ UI      | http://localhost:15672     |
| SeaweedFS admin  | http://localhost:23646     |
| S3               | http://localhost:8333      |
| PostgreSQL       | `localhost:5432`           |
| Valkey           | `localhost:6379`           |

## Releasing

Pushing to a `version/**` branch runs `.github/workflows/publish.yml`, which publishes the API to Reposilite and creates
the GitHub release `v<version>` with the Paper plugin attached. The version comes from `gradle.properties`.
