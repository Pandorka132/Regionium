import gg.meza.stonecraft.mod

plugins {
    id("gg.meza.stonecraft")
}

if (providers.gradleProperty("regionium.carpetTests").isPresent && mod.isFabric) {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "Modrinth"
                    url = uri("https://api.modrinth.com/maven")
                }
            }
            filter {
                includeGroup("maven.modrinth")
            }
        }
    }

    dependencies {
        runtimeOnly("maven.modrinth:TQTTVgYE:yt9oDFOj")
    }
}

modSettings {
    clientOptions {
        fov = 90
        guiScale = 3
        narrator = false
        darkBackground = true
        musicVolume = 0.0
    }

    variableReplacements =
        mapOf(
            "minecraftVersionVirtual" to stonecutter.current.version,
            "forgeLoaderVersion" to
                if (project.mod.isForge) {
                    project.mod
                        .prop("forge_version")
                        .substringAfter("-")
                        .substringBefore(".")
                } else {
                    ""
                },
        )
}

publishMods {
    type = ALPHA
    changelog = providers.provider {
        rootProject.file("CHANGELOG.md").readText()
    }

    modrinth {
        accessToken.set(providers.gradleProperty("MODRINTH_TOKEN"))
        if (mod.isFabric) requires("fabric-api")
        environment.set(DEDICATED_SERVER_ONLY)
    }

    curseforge {
        accessToken.set(providers.gradleProperty("CURSEFORGE_TOKEN"))
        client = false
        server = true
        if (mod.isFabric) requires("fabric-api")
    }
}
