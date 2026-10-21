import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is local-only: keystore.properties and the keystore itself are
// git-ignored and never baked into the APK. Debug builds keep using AGP's default
// debug keystore so `adb install -r` continues to update existing debug installs.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.streambox.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streambox.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0-playdot"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Backend base URL, overridable per build without code changes:
        //   ./gradlew assembleDebug -Pstreambox.baseUrl=http://<LAN-IP>:8000/
        // Default targets an emulator on the machine hosting the backend (10.0.2.2).
        val streamboxBaseUrl = providers.gradleProperty("streambox.baseUrl").orNull
            ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "STREAM_BASE_URL", "\"$streamboxBaseUrl\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Images
    implementation(libs.coil.compose)

    // Networking: Retrofit + OkHttp + kotlinx.serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Local watchlist persistence (Phase 2: local-only watchlist)
    implementation(libs.androidx.datastore.preferences)

    // Phase 3: playback via AndroidX Media3 (ExoPlayer + HLS/DASH + UI components)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // Unit tests (Phase 3: DTO parsing + resume policy)
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}
