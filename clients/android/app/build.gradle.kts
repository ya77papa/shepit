import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Підпис релізу: ключ з keystore.properties (локально) або зі змінних оточення (CI).
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = signing.getProperty(name) ?: System.getenv(name)

android {
    namespace = "io.github.ya77papa.shepit"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "io.github.ya77papa.shepit"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("SHEPIT_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SHEPIT_VERSION_NAME") ?: "0.1.0-dev"
        buildConfigField("String", "SERVER", "\"https://shepit.razom.isroot.in\"")
        buildConfigField("String", "COMMIT", "\"${System.getenv("SHEPIT_COMMIT") ?: "dev"}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            val store = secret("SHEPIT_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = secret("SHEPIT_KEYSTORE_PASSWORD")
                keyAlias = secret("SHEPIT_KEY_ALIAS") ?: "shepit"
                keyPassword = secret("SHEPIT_KEY_PASSWORD") ?: secret("SHEPIT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (secret("SHEPIT_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
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

    // Згенеровані UniFFI-біндинги ядра (build-core.sh)
    sourceSets.getByName("main") {
        kotlin.directories.add("build/generated/uniffi")
        jniLibs.directories.add("build/generated/jniLibs")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("jna.library.path", rootProject.file("../../target/debug").absolutePath)
            System.getenv("SHEPIT_TEST_SERVER")?.let { v -> it.environment("SHEPIT_TEST_SERVER", v) }
        }
    }

    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.json:json:20250517")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("com.google.zxing:core:3.5.4")
}
