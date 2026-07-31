allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    // Some plugins (e.g. file_picker) still compile against an older Android
    // SDK while their transitive deps now require compileSdk 36. Force every
    // Android library subproject up to 36 so the AAR-metadata check passes.
    // Registered before evaluationDependsOn so it runs before the subproject
    // is evaluated.
    afterEvaluate {
        val androidExt = extensions.findByName("android")
        if (androidExt is com.android.build.gradle.BaseExtension) {
            val current =
                androidExt.compileSdkVersion?.substringAfter("android-")?.toIntOrNull() ?: 0
            if (current < 36) {
                androidExt.compileSdkVersion(36)
            }
        }
    }
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
