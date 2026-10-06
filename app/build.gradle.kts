plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.blackcloudgroup.binaural"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blackcloudgroup.binaural"
        minSdk = 26
        targetSdk = 36
        // CI passes the run number so every uploaded bundle has a higher versionCode (Play requires it).
        versionCode = System.getenv("VERSION_CODE")?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Committed debug key so every CI build is signed identically and installs over the last one.
        // Debug-only (public repo, well-known passwords): never use it for a Play Store release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Upload key for Play, supplied only by the release CI job (see docs/RELEASING.md).
        // Absent locally and in normal CI, in which case release builds are left unsigned.
        // CI passes missing secrets as empty strings, so treat empty the same as unset.
        val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotEmpty() }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: error("RELEASE_KEYSTORE_PASSWORD must be set when RELEASE_KEYSTORE_PATH is")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotEmpty() }
                    ?: error("RELEASE_KEY_ALIAS must be set when RELEASE_KEYSTORE_PATH is")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: error("RELEASE_KEY_PASSWORD must be set when RELEASE_KEYSTORE_PATH is")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Release code (R8 shrinking and obfuscation) signed with the debug key, so it installs from the
        // latest-debug link and over the debug build. Catches release-only crashes before Play does.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// jvmTarget follows compileOptions.targetCompatibility (17) under built-in Kotlin.

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Only Icons.Default.Delete is used; the core icon set has it (extended adds ~10 MB of icons).
    implementation("androidx.compose.material:material-icons-core")

    // Room Database (KSP)
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Health Connect Client
    implementation("androidx.health.connect:connect-client:1.1.0")

    // MediaSession
    implementation("androidx.media:media:1.8.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
