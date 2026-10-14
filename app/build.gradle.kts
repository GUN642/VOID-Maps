import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build number from CI so every GitHub build installs as an update over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Optional TomTom key for local builds. Never committed; CI builds leave it empty
// because their APKs are published – there the key is entered in the app settings.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tomtomKey = (localProps.getProperty("tomtom.apiKey") ?: "").trim()

android {
    namespace = "app.voidmaps"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.voidmaps"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "TOMTOM_KEY", "\"$tomtomKey\"")
        ndk {
            // Galaxy S25 Ultra and every current phone are arm64 – keeps the APK small.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Secrets from GitHub Actions take priority; the bundled key keeps updates installable otherwise.
            val ksPath = System.getenv("VOID_KEYSTORE_PATH")
            storeFile = if (ksPath.isNullOrBlank()) rootProject.file("signing/voidmaps.jks") else file(ksPath)
            storePassword = System.getenv("VOID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "voidmaps"
            keyAlias = System.getenv("VOID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "voidmaps"
            keyPassword = System.getenv("VOID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "voidmaps"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("release")
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Android Auto
    implementation("androidx.car.app:app:1.4.0")

    // Map rendering (OpenStreetMap vector tiles)
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
