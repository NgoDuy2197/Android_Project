plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.healthassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.healthassistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // The on-device LLM runtime (MediaPipe GenAI) ships arm64 native libs only.
        ndk { abiFilters += listOf("arm64-v8a") }
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
    // On-device LLM "Xem xét" (offline): MediaPipe LLM Inference loads a user-imported .task model.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
