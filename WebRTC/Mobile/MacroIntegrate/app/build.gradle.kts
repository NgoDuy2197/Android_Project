import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Default webhook from <project>/webhook.properties (git-ignored), baked into BuildConfig.
val secrets = Properties().apply {
    rootProject.file("webhook.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val defaultWebhook = secrets.getProperty("DISCORD_WEBHOOK", "").trim().replace("\"", "")

android {
    namespace = "com.dsoft.macrointegrate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsoft.macrointegrate"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_WEBHOOK", "\"$defaultWebhook\"")
    }

    buildFeatures { buildConfig = true }

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

// No dependencies on purpose: plain framework APIs keep the APK tiny.

dependencies { testImplementation("junit:junit:4.13.2") }
