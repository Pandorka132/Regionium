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

1. Install a Fabric server with the [Fabric installer](https://fabricmc.net/use/installer/)
2. Install Fabric API in the server's `mods` directory.
3. Download the Regionium JAR from [GitHub Releases](https://github.com/Pandorka132/Regionium/releases) or [Modrinth](https://modrinth.com/project/xzlqihg0) and put it in the same `mods` directory.
4. Back up the world, then start the server and check `logs/latest.log` for errors.

Regionium is server-side; players do not need to install it on their clients.

## Build and test

Run these commands from the repository root:

```sh
./gradlew runGameTestServer buildAndCollect -Pregionium.carpetTests --stacktrace
```

The first command runs the Fabric GameTests, including the optional Carpet-based player tests. The second builds the configured artifact and collects it under `build/libs`.

## License

Regionium is licensed under the MIT License. See [LICENSE](LICENSE).
