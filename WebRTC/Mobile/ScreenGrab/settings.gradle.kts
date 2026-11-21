pluginManagement {
    repositories {
        google()
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

rootProject.name = "ScreenGrab"
include(":app")
// Compile-only stubs of the Xposed API (de.robv.android.xposed.*). LSPosed injects the real classes
// at runtime, so nothing from this module ends up in the APK.
include(":xposed-api")
