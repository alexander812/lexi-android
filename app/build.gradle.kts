import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val webviewProperties = Properties().apply {
    val configFile = file("webview.properties")
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

    buildTypes {
        debug {
            buildConfigField("String", "WEB_START_URL", "\"${webviewUrl("webview.devUrl")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}
