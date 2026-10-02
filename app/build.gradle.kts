plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.estilodocampo.gameanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.estilodocampo.gameanalyzer"
        minSdk = 26
        targetSdk = 35
        val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciRunNumber ?: 2
        versionName = if (ciRunNumber != null) "2.$ciRunNumber" else "2.0"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
