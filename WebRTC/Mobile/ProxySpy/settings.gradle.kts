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

rootProject.name = "ProxySpy"
include(":app")
// Local compile-only stubs of the Xposed API (de.robv.android.xposed.*). LSPosed provides the real
// classes at runtime, so nothing from this module is packaged into the APK.
include(":xposed-api")
