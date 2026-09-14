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
    }
}

rootProject.name = "Echo"

include(":app")
include(":whisper_base_assets") // install-time asset pack: ships ggml-base.bin with the app (no INTERNET)
include(":core:capture")
include(":core:consent")
include(":core:billing")
include(":core:transcribe")
include(":core:sync")
include(":core:data")
include(":core:ui")
