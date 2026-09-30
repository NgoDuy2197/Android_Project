plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.voicenote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.voicenote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Only real-phone ABIs -> smaller APK.
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
}

dependencies {
    // Offline speech recognition (Kaldi). No AndroidX on purpose: keeps the APK tiny.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
