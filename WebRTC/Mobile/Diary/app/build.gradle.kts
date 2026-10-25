plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.odiary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.odiary"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Nicer, consistent emoji that work OFFLINE — the Noto Color Emoji font is embedded in the app.
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-bundled:1.4.0")
    // Google Sign-In + OAuth token (GoogleAuthUtil) for per-user Google Drive sync.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // Drive REST calls (upload/list/download) with the access token.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
