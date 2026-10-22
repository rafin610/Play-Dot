import java.net.URI
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

// Explicit backend origin for this build. The Gradle property wins over the
// environment variable so CI and local builds can share one configuration.
val streamboxBaseUrl: String? = providers.gradleProperty("streambox.baseUrl").orNull
    ?: providers.environmentVariable("STREAM_BOX_BASE_URL").orNull

// Retrofit requires a trailing slash; normalize here so a build never fails at
// runtime just because the property was passed without one.
val resolvedBaseUrl: String = streamboxBaseUrl
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { if (it.endsWith("/")) it else "$it/" }
    ?: "http://10.0.2.2:8000/"

android {
    namespace = "com.streambox.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.streambox.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0-playdot"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Backend base URL, overridable per build without code changes:
        //   ./gradlew assembleDebug -Pstreambox.baseUrl=http://<LAN-IP>:8000/
        //   ./gradlew assembleRelease -Pstreambox.baseUrl=https://<service>.onrender.com/
        // Debug defaults to the emulator's host loopback (10.0.2.2). Release
        // builds must pass an explicit public HTTPS origin; checkReleaseBaseUrl
        // below rejects emulator/loopback targets so they cannot be shipped.
        val baseUrl = resolvedBaseUrl
        buildConfigField("String", "STREAM_BASE_URL", "\"$baseUrl\"")
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
            } else {
                signingConfig = signingConfigs.getByName("debug")
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

    // Device-level playback verification against a public H.264/AAC test stream.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

/**
 * Release APKs must target a publicly reachable HTTPS backend. Without this
 * guard the shared emulator default (`10.0.2.2`) silently ships and every
 * request fails on a physical phone because that host only exists inside an
 * emulator.
 */
val checkReleaseBaseUrl = tasks.register("checkReleaseBaseUrl") {
    group = "verification"
    description = "Rejects release builds that target an emulator or loopback backend."

    doLast {
        if (streamboxBaseUrl.isNullOrBlank()) {
            throw GradleException(
                "Release builds require -Pstreambox.baseUrl=https://<service>.onrender.com/ " +
                    "(or the STREAM_BOX_BASE_URL environment variable).",
            )
        }
        val uri = runCatching { URI(resolvedBaseUrl) }.getOrNull()
            ?: throw GradleException("streambox.baseUrl is not a valid URL: $resolvedBaseUrl")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw GradleException("Release builds require an HTTPS backend URL, got: $resolvedBaseUrl")
        }
        val host = uri.host?.lowercase()
            ?: throw GradleException("streambox.baseUrl has no host: $resolvedBaseUrl")
        val localHost = host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
            host == "10.0.2.2" || host.endsWith(".local")
        if (localHost) {
            throw GradleException("Refusing to build a release APK against a local backend: $resolvedBaseUrl")
        }
    }
}

tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }
    .configureEach { dependsOn(checkReleaseBaseUrl) }
