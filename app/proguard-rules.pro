# Общие правила ProGuard

# Сохраняем все классы приложения (чтобы не сломать функциональность)
-keep public class com.example.refactortext.** {
    public *;
    protected *;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keep class kotlin.Metadata { *; }

# Google AI SDK (Gemini)
-keep class com.google.ai.** { *; }
-dontwarn com.google.ai.**

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Apache POI
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.zaxxer.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Remove logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
