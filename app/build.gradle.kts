plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.aluetschen.karoofuel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.aluetschen.karoofuel"
        minSdk = 23
        targetSdk = 34
        // Bump both for every release (versionCode must always increase).
        versionCode = 3
        versionName = "0.2.1"
    }

    // Signing key from GitHub Secrets (see README). Without secrets, the debug key is used.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
