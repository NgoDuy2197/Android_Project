plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.autotap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.autotap"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only: ML Kit OCR ships native libs for every ABI otherwise (+size).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // OCR for the TAP_TEXT step: on-device Latin text recognition (Play-services models, not bundled).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // Pure-Kotlin JSON model is serialized with org.json; the real impl on the JVM test classpath
    // (android.jar only stubs it) so Scenario/Step round-trips can be unit-tested off-device.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
