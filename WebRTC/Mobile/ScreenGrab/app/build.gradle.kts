plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.screengrab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.screengrab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // Keep LSPosed entry class + reflection names intact; no shrinking.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            // BouncyCastle ships duplicate/meta files that trip the packager.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    // Pure Android framework: MediaProjection, MediaCodec, MediaMuxer, AudioRecord, OpenGL ES.
    // No third-party libs → tiny APK that builds anywhere.

    // LSPosed module stubs (de.robv.android.xposed.*). Compile-time only; the real classes are
    // provided by LSPosed inside the hooked process, so nothing here is packaged.
    compileOnly(project(":xposed-api"))

    // Video sniffer/downloader: MITM proxy (TLS to origins) + its CA (BouncyCastle), HLS/file fetch.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
