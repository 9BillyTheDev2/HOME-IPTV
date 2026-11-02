plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.homeiptv.v2"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.homeiptv.v2"
        minSdk = 23
        targetSdk = 35
        versionCode = System.getenv("HOME_IPTV_VERSION_CODE")?.toIntOrNull() ?: 8
        versionName = System.getenv("HOME_IPTV_VERSION_NAME") ?: "0.1.7-alpha"
    }

    val releaseStorePath = System.getenv("HOME_IPTV_KEYSTORE_PATH")
    if (releaseStorePath != null) {
        val releaseSigning = signingConfigs.create("release") {
            storeFile = file(releaseStorePath)
            storePassword = System.getenv("HOME_IPTV_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HOME_IPTV_KEY_ALIAS")
            keyPassword = System.getenv("HOME_IPTV_KEY_PASSWORD")
        }
        buildTypes.getByName("release") {
            signingConfig = releaseSigning
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
