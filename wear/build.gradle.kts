plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "fi.bibbi88.borawatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "fi.bibbi88.borawatch"
        minSdk = 30          // Wear OS 3 (Galaxy Watch4 and newer)
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }

    // Fixed key so new builds install over the old one without uninstalling
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
