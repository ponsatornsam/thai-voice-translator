plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thaivoice.translator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thaivoice.translator"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // API Key — change this to your actual key
        buildConfigField("String", "BACKEND_SERVER_URL", "\"wss://comment-scrubber-ninja.ngrok-free.dev\"")
        buildConfigField("String", "BACKEND_API_KEY", "\"k3EOTM10GxevuDLUHj7g7TwZ16nfiq8bBGqEG0hAMoA\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // OkHttp WebSocket (Module 10)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines (Module 9, 10, 11)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
