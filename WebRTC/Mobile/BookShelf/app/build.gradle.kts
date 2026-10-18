plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.bookshelf"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.bookshelf"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // real phones/tablets only: drops the x86 copies of the ML Kit native libs (~35 MB)
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

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                // post-quantum crypto tables from bouncycastle (pulled in by pdfbox, never used)
                "org/bouncycastle/pqc/**")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PDF text layer
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device translation + language id (models downloaded on demand)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    // OCR (Play-services variants: models live in Google Play services, tiny APK)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    // Pure-Java LAME port (MP3 encoder). Only the low-level de.sciss.jump3r.mp3 API is used (no javax.sound).
    implementation("de.sciss:jump3r:1.0.5")

    testImplementation("junit:junit:4.13.2")
}
