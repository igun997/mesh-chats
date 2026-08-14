pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Signal's official Maven repo. libsignal 0.100.0 is published here, not to
        // Maven Central. Scoped to the org.signal group so every other dependency still
        // resolves from Central/Google and this repo is never consulted for them.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content {
                includeGroup("org.signal")
            }
        }
    }
}

rootProject.name = "mesh-chats"
include(":app")
include(":mesh-protocol")
