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
        // Correct DJI SDK V5 Maven Repository
        maven { url = uri("https://developer.dji.com/maven") }
        // DJI SDK often uses jitpack or other private repos for some components
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "drones"
include(":app")
