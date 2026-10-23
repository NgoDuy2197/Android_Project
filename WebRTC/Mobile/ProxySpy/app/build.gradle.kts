plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsoft.proxyspy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.proxyspy"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Upstream HTTP client: handles TLS to origin, HTTP/2, gzip, chunked — so the MITM engine
    // only has to terminate the client TLS and forward the decoded request/response.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Root CA + dynamic per-host leaf certificate generation.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // QR code (link for the other phone to scan).
    implementation("com.google.zxing:core:3.5.3")
    // QR scanning (client mode: scan the proxy phone's address).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
