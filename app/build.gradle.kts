plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.refactortext"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.refactortext"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Стандартный и надежный сетевой клиент
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // 1. Официальный SDK от Google для работы с Gemini API
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // 2. Официальная библиотека для парсинга JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // 3. Библиотека для удобной работы с фоновыми потоками (корутины)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Библиотека перевода Google ML Kit
    implementation("com.google.mlkit:translate:17.0.3")
}