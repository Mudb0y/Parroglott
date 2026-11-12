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
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mudb0y.parroglott"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("PARROGLOTT_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("PARROGLOTT_VERSION_NAME") ?: "0.1.0"
        // Amazon's engine and voices are arm64 code; nothing else can run them.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // NDK r27 still links with 4 KB segment alignment unless asked.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // What scripts/stage-android.sh takes from Amazon's firmware. It travels
    // as assets, loaded from app storage, because Android warns about any
    // library in an APK that is not aligned for 16 KB pages, and Amazon's
    // cannot be rebuilt.
    sourceSets["main"].assets.srcDir("${rootDir}/../work/build/android/assets")

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
