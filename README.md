# Regionium

Regionium is an experimental region-based multithreading mod for Fabric dedicated servers, inspired by Folia's region-ticking architecture.

**This project is experimental and may corrupt worlds, crash, or behave incorrectly under load. Back up your world before testing it. Do not use it for production servers or valuable worlds.**

## Compatibility

- Minecraft: 26.3
- Loader: Fabric
- Runtime: Java 25
- Required mod: Fabric API
- Side: Dedicated server

Only the version and loader listed above are currently configured and tested by this repository. Compatibility with other Minecraft versions or loaders is not implied.

## Installation

1. Install a Fabric dedicated server for Minecraft 26.3 with Java 25.
2. Install Fabric API in the server's `mods` directory.
3. Download the Regionium JAR from the [GitHub Releases](https://github.com/Pandorka132/Regionium/releases) page and put it in the same `mods` directory.
4. Back up the world, then start the server and check `logs/latest.log` for errors.

Regionium is server-side; players do not need to install it on their clients.

## Build and test

Run these commands from the repository root:

```sh
./gradlew :26.3-fabric:runGameTestServer -Pregionium.carpetTests --stacktrace
./gradlew :26.3-fabric:buildAndCollect
```

The first command runs the Fabric GameTests, including the optional Carpet-based player tests. The second builds the configured artifact and collects it under `build/libs`.

## Publishing

The release workflow publishes the configured artifact to Modrinth and CurseForge. Configure these variables in the CI environment or a local, untracked `.env` file:

- `MODRINTH_ID` and `MODRINTH_TOKEN`
- `CURSEFORGE_ID` and `CURSEFORGE_TOKEN`
- `CURSEFORGE_SLUG` (optional; used for links in release notifications)
- `DO_PUBLISH=true` to allow real uploads

Gradle does not load `.env` automatically. For a local publish-task dry run, load it into the shell and explicitly keep uploads disabled:

```sh
set -a
. ./.env
set +a
DO_PUBLISH=false ./gradlew :26.3-fabric:publishMods
```

For a real upload, use `DO_PUBLISH=true` only when you are ready to publish. The `scripts/release.sh` release workflow loads `.env`, runs the GameTests, builds the JAR, and publishes to both configured platforms. Never commit `.env` or put API tokens in tracked files.

## License

Regionium is licensed under the MIT License. See [LICENSE](LICENSE).
