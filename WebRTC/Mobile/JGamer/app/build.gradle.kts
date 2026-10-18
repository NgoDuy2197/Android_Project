plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.jgamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.jgamer"
        minSdk = 21
        targetSdk = 34
        // > 12 (last per-ABI split build) so it updates existing installs.
        versionCode = 13
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // Phones only: a single arm64 APK (32-bit / x86 cores left out to keep it small).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // Non-debuggable release signed with the debug key (updates existing installs).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // Compress cores in the APK and extract them on install (smaller download);
    // LibretroDroid loads them by filename from nativeLibraryDir.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Emulation engine (libretro frontend, GPLv3).
    implementation("com.github.swordfish90:libretrodroid:0.13.2")
}
