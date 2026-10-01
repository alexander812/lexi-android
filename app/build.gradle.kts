import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val webviewProperties = Properties().apply {
    val configFile = file("webview.properties")
    if (configFile.exists()) configFile.reader(Charsets.UTF_8).use { load(it) }
}

val keystoreProperties = Properties().apply {
    val configFile = rootProject.file("keystore.properties")
    if (configFile.exists()) configFile.reader(Charsets.UTF_8).use { load(it) }
}

fun webviewUrl(key: String): String = webviewProperties.getProperty(key).orEmpty().trim()

android {
    namespace = "dev.alexander812.lexi"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.alexander812.lexi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
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
            val url = webviewUrl("webview.devUrl").ifBlank { webviewUrl("webview.prodUrl") }
            buildConfigField("String", "WEB_START_URL", "\"$url\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "WEB_START_URL", "\"${webviewUrl("webview.prodUrl")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.exifinterface)
    implementation(libs.tesseract4android)
    implementation(libs.commons.compress)
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
