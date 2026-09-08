package com.example.audiobible.generatorAll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ImageGenerator {

    private const val TAG = "BREAD_PARSER_LOG"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val mutexMap = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Принимает русский текст, переводит внутри и генерирует
    suspend fun generateImage(context: android.content.Context, russianPrompt: String): Bitmap? = withContext(Dispatchers.IO) {

        // 1. АВТОПЕРЕВОД ПРОМПТА НА АНГЛИЙСКИЙ ЯЗЫК
        val englishPrompt = try {
            Log.d(TAG, "[IMAGE] Перевод промпта: $russianPrompt")
            TextTranslator.translateRuToEn(russianPrompt)
        } catch (e: Exception) {
            Log.e(TAG, "[IMAGE] Ошибка перевода, используем исходный текст: ${e.localizedMessage}")
            russianPrompt
        }

        Log.d(TAG, "[IMAGE] Итоговый английский промпт для сервера: $englishPrompt")

        val cacheDir = File(context.cacheDir, "image_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        // Универсальные теги качества для ЛЮБОГО слова (будь то хлеб, машина или человек)
        val enhancedPrompt = "$englishPrompt, highly detailed, photorealistic, 8k resolution, cinematic lighting, masterpiece"


        val randomSeed = (1..100_000).random()
        val filename = sha256(enhancedPrompt + randomSeed) + ".png"
        val cacheFile = File(cacheDir, filename)

        val key = filename
        val mutex = synchronized(mutexMap) { mutexMap.getOrPut(key) { kotlinx.coroutines.sync.Mutex() } }

        try {
            mutex.withLock {
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    try {
                        return@withContext BitmapFactory.decodeFile(cacheFile.absolutePath)
                    } catch (e: Exception) { /* continue */ }
                }

                val targetUrl = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("image.pollinations.ai")
                    .addPathSegment("p")
                    .addPathSegment(enhancedPrompt)
                    .addQueryParameter("width", "512")  // Оптимизировано до 512 для скорости
                    .addQueryParameter("height", "512") // Оптимизировано до 512 для скорости
                    .addQueryParameter("model", "flux")
                    .addQueryParameter("seed", randomSeed.toString())
                    .addQueryParameter("nologo", "true")
                    .build()

                Log.d(TAG, "[IMAGE] Запуск генерации. URL: $targetUrl")

                val generateRequest = Request.Builder()
                    .url(targetUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .get()
                    .build()

                client.newCall(generateRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "[IMAGE] Ошибка сервера: ${response.code}")
                        return@withContext null
                    }

                    val responseBody = response.body
                    if (responseBody == null) {
                        Log.e(TAG, "[IMAGE] Тело ответа отсутствует.")
                        return@withContext null
                    }

                    val imageBytes = responseBody.bytes()
                    if (imageBytes.isEmpty()) {
                        Log.e(TAG, "[IMAGE] Скачанный бинарник пуст.")
                        return@withContext null
                    }

                    if (imageBytes.size < 15000) {
                        val textCheck = String(imageBytes, Charsets.UTF_8)
                        if (textCheck.trim().startsWith("<!DOCTYPE") || textCheck.contains("<html")) {
                            Log.e(TAG, "[IMAGE] Сбой! Скачался HTML: ${textCheck.take(200)}")
                            return@withContext null
                        }
                    }

                    try {
                        FileOutputStream(cacheFile).use { fos ->
                            fos.write(imageBytes)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[IMAGE] Ошибка записи в кэш: ${e.localizedMessage}")
                    }

                    return@withContext BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[IMAGE] Крах во время генерации: ${e.localizedMessage}", e)
        } finally {
            synchronized(mutexMap) { mutexMap.remove(key) }
        }
        return@withContext null
    }
}
