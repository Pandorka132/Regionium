# Regionium

Multithreading in Fabric Minecraft!
Basically a folia port to fabric with mixins. Work in progress, might not work as intended

This project uses [Stonecraft](https://stonecraft.meza.gg) to build for Fabric, Forge, and NeoForge. It includes:


## Build and verify

Run the available commands from the project root:

```shell
./gradlew runGameTestServer buildAndCollect
```

`runGameTestServer` succeeds only after the no-op GameTest passes for every pair. Failures are reported by the corresponding version-loader Gradle task.
`buildAndCollect` builds every configured Minecraft-version and loader pair, then collects the resulting JARs under `build/libs`.

## GameTests

The loader-specific GameTest entrypoints are available to GameTest runs. Stonecraft removes them from normal production JARs.
