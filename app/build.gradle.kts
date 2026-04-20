import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

repositories {
    google()
    mavenCentral()
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.vistacore.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vistacore.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = 36
        versionName = "1.5.1"

        // TMDB proxy base — same Worker that serves the web app. The Worker's
        // `/api/tmdb` endpoint attaches TMDB_KEY server-side so the APK ships
        // with no API key. Override via local.properties if you self-host.
        val tmdbBase = providers.gradleProperty("tmdbProxyBase")
            .orElse("https://vistacore.app")
            .get()
        buildConfigField("String", "TMDB_PROXY_BASE", "\"$tmdbBase\"")
    }

    flavorDimensions += "device"
    productFlavors {
        create("modern") {
            dimension = "device"
            versionNameSuffix = "a"
            buildConfigField("boolean", "LEGACY_TLS", "false")
        }
        create("legacy") {
            dimension = "device"
            versionNameSuffix = "b"
            buildConfigField("boolean", "LEGACY_TLS", "true")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", ""))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Android TV Leanback
    implementation("androidx.leanback:leanback:1.0.0")

    // ExoPlayer / Media3 for IPTV
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-ui-leanback:1.2.1")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for IPTV API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Conscrypt — modern TLS provider for Fire OS / older Android TV
    // Handles TLS renegotiation that stock Fire OS SSL rejects
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // WorkManager for background channel updates
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
