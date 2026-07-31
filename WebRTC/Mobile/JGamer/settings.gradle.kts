pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // LibretroDroid is published on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "JGamer"
include(":app")
