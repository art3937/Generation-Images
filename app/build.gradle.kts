import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// === ЧТЕНИЕ КЛЮЧЕЙ ИЗ LOCAL.PROPERTIES (Вынесено на самый верх) ===
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.refactortext"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.refactortext"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Получаем значения напрямую из local.properties
        // Если в файле кавычки уже стоят, Gradle запишет их правильно
        val apiKeyVal = localProperties.getProperty("YANDEX_API_KEY") ?: "\"\""
        val folderIdVal = localProperties.getProperty("YANDEX_FOLDER_ID") ?: "\"\""

        buildConfigField("String", "YANDEX_API_KEY", apiKeyVal)
        buildConfigField("String", "YANDEX_FOLDER_ID", folderIdVal)

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true // Включает автогенерацию класса BuildConfig
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
