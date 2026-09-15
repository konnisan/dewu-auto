plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val licenseApiBaseUrl = providers.gradleProperty("LICENSE_API_BASE_URL")
    .orElse(providers.environmentVariable("LICENSE_API_BASE_URL"))
    .orElse("http://101.37.18.75/api/license")

android {
    namespace = "com.konnisan.dewuauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.konnisan.dewuauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.2-card-only"

        buildConfigField(
            "String",
            "LICENSE_API_BASE_URL",
            "\"${licenseApiBaseUrl.get()}\"",
        )
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}
