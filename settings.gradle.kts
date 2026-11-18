pluginManagement {
    repositories {
        // Local curl-populated mirror FIRST (same reason as
        // dependencyResolutionManagement below): the sandbox
        // resets the Gradle daemon's own HTTPS fetches, plugin
        // markers included (v1.0.35-test build fix).
        maven("file:///home/hatch/workspace/forte/local-repo")
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
        // Local curl-populated mirror FIRST: this sandbox resets
        // the Gradle daemon's own HTTPS fetches to the public
        // repos (curl through the same proxy works), so artifacts
        // the public repos cannot deliver are served from here.
        maven("file:///home/hatch/workspace/forte/local-repo")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Forte"
include(":app")
