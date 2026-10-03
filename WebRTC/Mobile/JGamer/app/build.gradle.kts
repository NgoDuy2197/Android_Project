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
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // ABIs come from splits below (AGP rejects ndk.abiFilters together
        // with ABI splits): arm64-v8a for phones, armeabi-v7a for 32-bit TVs
        // (e.g. many Sony Bravia). LibretroDroid's x86 libs are left out.
    }

    buildTypes {
        release {
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

    // One APK per ABI so each carries only its own ~100-200 MB of cores.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Extract native libs (cores) to the install dir so LibretroDroid can load
    // them by filename from nativeLibraryDir.
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

// Per-ABI versionCode: arm64 > v7a, so a 64-bit device offered both picks arm64.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val code = abiCodes[abi] ?: return@forEach
            output.versionCode.set((output.versionCode.orNull ?: 1) * 10 + code)
        }
    }
}
