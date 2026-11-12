import java.util.Properties

plugins {
    id("com.android.application")
}

// One key signs every build when it is available, so CI builds and local
// builds install over each other. CI writes this file from repository secrets.
val signingFile = file(System.getenv("PARROGLOTT_SIGNING")
    ?: "${System.getProperty("user.home")}/.local/share/parroglott/signing.properties")
val signing = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use { load(it) } } else null

android {
    namespace = "com.mudb0y.parroglott"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.mudb0y.parroglott"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("PARROGLOTT_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("PARROGLOTT_VERSION_NAME") ?: "0.1.0"
        // Amazon's engine and voices are arm64 code; nothing else can run them.
        ndk { abiFilters += "arm64-v8a" }
    }

    // What scripts/stage-android.sh takes from Amazon's firmware.
    sourceSets["main"].jniLibs.srcDir("${rootDir}/../work/build/android/jniLibs")
    sourceSets["main"].assets.srcDir("${rootDir}/../work/build/android/assets")

    // Amazon's libraries ship exactly as Amazon built them.
    packaging {
        jniLibs { keepDebugSymbols += "**/*.so" }
    }

    signingConfigs {
        if (signing != null) create("parroglott") {
            storeFile = signingFile.parentFile.resolve(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("parroglott") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = key
        }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
