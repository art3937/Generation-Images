import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.refactortext"
        minSdk = 26
        targetSdk = 37
        versionCode = 2       // Увеличьте на 1 для каждого нового релиза в Google Play
        versionName = "1.1"   // Укажите понятную пользователям версию (например, "1.0.1" или "1.1")
    


    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Получаем значения напрямую из local.properties
        // Если в файле кавычки уже стоят, Gradle запишет их правильно
        val apiKeyVal = localProperties.getProperty("YANDEX_API_KEY") ?: "\"\""
        val folderIdVal = localProperties.getProperty("YANDEX_FOLDER_ID") ?: "\"\""

        buildConfigField("String", "YANDEX_API_KEY", apiKeyVal)
        buildConfigField("String", "YANDEX_FOLDER_ID", folderIdVal)



    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null &&
                System.getenv("KEYSTORE_PASSWORD") != null &&
                System.getenv("KEY_ALIAS") != null &&
                System.getenv("KEY_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // Включаем поддержку Jetpack Compose в проекте
        compose = true
        viewBinding = true
        buildConfig = true // Включает автогенерацию класса BuildConfig
    }

    composeOptions {
        // Указываем версию компилятора (для современных версий Kotlin используется актуальный Compiler Extension)
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    // Стандартный и надежный сетевой клиент
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.runtime)
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

    // POI для Excel (современная версия)
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Базовая интеграция Compose в Activity (исправляет Unresolved reference 'compose')
    implementation("androidx.activity:activity-compose:1.9.3")

    // Основной инструментарий Compose (блоки, тексты, разметка)
    implementation(platform("androidx.compose:compose-bom:2026.09.00")) // Управляет версиями Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Материальный дизайн (кнопки, карточки) и ViewModel (из прошлого шага)
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

        // Актуальная библиотека Lottie для Jetpack Compose
        implementation("com.airbnb.android:lottie-compose:6.6.0") // или более свежая версия
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1") // используйте актуальную версию для вашего проекта





}
