# Общие правила ProGuard

# Сохраняем все классы приложения (чтобы не сломать функциональность)
-keep public class com.example.refactortext.** {
    public *;
    protected *;
    private *;
    <init>(...);
}

# Явно сохраняем MainActivity и все Activity
-keep class com.example.refactortext.MainActivity {
    public <init>(...);
    public void onCreate(...);
    public void onResume();
    public void onPause();
    public void onDestroy();
}

-keep class * extends android.app.Activity {
    public <init>(...);
}

-keep class * extends androidx.appcompat.app.AppCompatActivity {
    public <init>(...);
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
-keep class kotlin.** { *; }

# Google AI SDK (Gemini)
-keep class com.google.ai.** { *; }
-dontwarn com.google.ai.**

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
-keep class kotlinx.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Apache POI & зависимости (эти классы только для Desktop Java)
-keep class org.apache.poi.** { *; }
-keep class org.apache.logging.log4j.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.zaxxer.**
-dontwarn aQute.bnd.annotation.spi.**
-dontwarn java.awt.**
-dontwarn org.osgi.framework.**
-dontwarn org.graphbuilder.**

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Remove logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Viewbinding
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    *** *Binding(...);
}
