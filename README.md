# Regionium

Multithreading in Fabric Minecraft!

The architecture's idea is based on [Folia](https://github.com/papermc/folia)

Work in progress, might be really unstable!
Use at your own risk!

This project uses [Stonecraft](https://stonecraft.meza.gg) to build for Fabric, Forge, and NeoForge. It includes:


## Build and verify

Download it from the [Actions](https://github.com/Pandorka132/Regionium/actions) page, or build it yourself:

Run the available commands from the project root:

```shell
./gradlew runGameTestServer buildAndCollect
```

`runGameTestServer` succeeds only after the no-op GameTest passes for every pair. Failures are reported by the corresponding version-loader Gradle task.
`buildAndCollect` builds every configured Minecraft-version and loader pair, then collects the resulting JARs under `build/libs`.

## GameTests

The loader-specific GameTest entrypoints are available to GameTest runs. Stonecraft removes them from normal production JARs.
