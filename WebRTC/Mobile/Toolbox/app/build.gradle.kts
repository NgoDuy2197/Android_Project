plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.toolbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.toolbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Phones only: ML Kit translate ships native libs for every ABI otherwise (+40 MB).
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
    // Google code scanner: camera UI + all barcode / QR formats run inside Play services (no camera permission, tiny APK).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Screen translate: OCR (Play-services models, not bundled) + Google on-device translation.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
