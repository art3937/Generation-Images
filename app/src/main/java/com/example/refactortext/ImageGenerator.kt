package com.example.refactortext

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.refactortext.ProxyManager
import com.example.refactortext.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds


object ImageGenerator {
    private const val TAG = "ImageGenerator"

    // Базовый клиент для работы через прокси
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Прямой клиент для аварийного обхода напрямую
    private val directClient = OkHttpClient.Builder()
        .connectTimeout(
            10,
            TimeUnit.SECONDS
        ) // ⚡️ ТАЙМАУТ 4 СЕКУНДЫ: Если прокси плохой, отваливаемся СРАЗУ
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false) // ⚡️ ГЛАВНЫЙ СЕКРЕТ ПРОФИ: Запрещаем OkHttp самовольно повторять запросы и тупить!
        .build()

    suspend fun generateImage(
        russianPrompt: String,
    ): Bitmap? = withContext(Dispatchers.IO) {

        Log.d(TAG, "[IMAGE] ---> СТАРТ ПЕРЕВОДА для '$russianPrompt'.")

        val englishPrompt = try {
            TextTranslator.translateRuToEn(russianPrompt)
        } catch (e: Exception) {
            Log.e(TAG, "[IMAGE] Ошибка перевода: ${e.localizedMessage}")
            russianPrompt
        }
        Log.e(TAG, "[IMAGE] переведено: $englishPrompt")

        val enhancedPrompt =
            "$englishPrompt, highly detailed, photorealistic, cinematic lighting, sharp focus, this context"

        var attempt = 1
        var isGenerated = false
        var finalBitmap: Bitmap? = null

        // НАСТОЯЩИЙ ЧЕСТНЫЙ БЕСКОНЕЧНЫЙ ЦИКЛ ЧЕРЕЗ УПРАВЛЯЕМЫЙ ФЛАГ
        while (!isGenerated) {
            try {
                val randomSeed = (1..100_000).random()
                val targetUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("image.pollinations.ai")
                    .addPathSegment("p")
                    .addPathSegment(enhancedPrompt)
                    .addQueryParameter("width", "1024")
                    .addQueryParameter("height", "1024")
                    .addQueryParameter("model", "flux")
                    .addQueryParameter("seed", randomSeed.toString())
                    .addQueryParameter("nologo", "true")
                    .build()

                // СБРАСЫВАЕМ КЭШ И ПУЛЫ СОЕДИНЕНИЙ, ЧТОБЫ ВТОРАЯ КАРТИНКА НЕ ЗАЛИПАЛА
                baseClient.connectionPool.evictAll()
                directClient.connectionPool.evictAll()

                Log.d(TAG, "[IMAGE] '$russianPrompt'. Попытка $attempt. URL: $targetUrl")

                var bytes: ByteArray? = null

// 1. Основная попытка НАПРЯМУЮ (без прокси)
                try {
                    val req = generateRequest(targetUrl, attempt)
                    bytes = directClient.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "[IMAGE] Прямой запрос HTTP ${response.code}. Переходим на прокси.")
                            return@use null
                        }
                        val body = response.body ?: return@use null
                        val rawBytes = body.bytes()
                        if (rawBytes.isEmpty()) return@use null

                        // Проверка Cloudflare (HTML-заглушки)
                        if (rawBytes.size < 500_000) {
                            val str = String(rawBytes, Charsets.UTF_8)
                            if (str.trim().startsWith("<!DOCTYPE") || str.contains("<html")) {
                                Log.w(TAG, "[IMAGE] Прямой запрос выдал HTML-заглушку от Cloudflare.")
                                return@use null
                            }
                        }

                        rawBytes
                    }
                } catch (directException: Exception) {
                    Log.w(TAG, "[IMAGE] Сбой прямого подключения на попытке $attempt: ${directException.message}")
                }

// 2. АВАРИЙНЫЙ ОБХОД ЧЕРЕЗ ПРОКСИ (срабатывает, если напрямую скачать не удалось)
                if (bytes == null) {
                    try {
                        val currentProxy = ProxyManager.getProxyForAttempt(attempt)
                        Log.w(TAG, "[IMAGE] Аварийный режим через прокси $currentProxy для попытки $attempt...")

                        val dynamicClient = baseClient.newBuilder()
                            .proxy(currentProxy)
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build()

                        val req = generateRequest(targetUrl, attempt)
                        bytes = dynamicClient.newCall(req).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.w(TAG, "[IMAGE] Прокси HTTP Код ошибки: ${response.code}. Меняем IP.")
                                ProxyManager.reportProxyStatus(currentProxy, isSuccess = false)
                                return@use null
                            }
                            val body = response.body ?: return@use null
                            val rawBytes = body.bytes()
                            if (rawBytes.isEmpty()) return@use null

                            if (rawBytes.size < 500_000) {
                                val str = String(rawBytes, Charsets.UTF_8)
                                if (str.trim().startsWith("<!DOCTYPE") || str.contains("<html")) {
                                    Log.w(TAG, "[IMAGE] Прокси выдал HTML-заглушку от Cloudflare.")
                                    ProxyManager.reportProxyStatus(currentProxy, isSuccess = false)
                                    return@use null
                                }
                            }

                            ProxyManager.reportProxyStatus(currentProxy, isSuccess = true)
                            rawBytes
                        }
                    } catch (proxyException: Exception) {
                        Log.e(TAG, "[IMAGE] Крах прокси-запроса на попытке $attempt: ${proxyException.message}")
                        // reportProxyStatus здесь нужен, если currentProxy доступен в этой области видимости
                    }
                }


                // 3. Сборка Bitmap в максимальном качестве
                if (bytes != null) {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (bitmap != null) {
                        Log.d(TAG, "[IMAGE] УСПЕХ! Картинка получена в RAM.")
                        finalBitmap = bitmap
                        isGenerated = true // Успешно выходим из цикла
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "[IMAGE] Критический сбой итерации $attempt: ${e.message}")
            }

            // === БЫСТРАЯ ЗАДЕРЖКА ПЕРЕД СЛЕДУЮЩЕЙ ИТЕРАЦИЕЙ ===
            if (!isGenerated) {
                attempt++
                val delayTime = when (attempt) {
                    2 -> 1500L
                    3 -> 3000L
                    else -> 5000L
                }
                Log.w(
                    TAG,
                    "[IMAGE] Переключаемся на следующий прокси через $delayTime мс на попытку $attempt"
                )
                delay(delayTime)
            }
        } // Конец while

        return@withContext finalBitmap
    }

    // ВАШ МЕТОД: Исправлен и корректно закрыт (с добавлением заголовка Connection: close для предотвращения залипаний)
    private fun generateRequest(targetUrl: HttpUrl, attempt: Int): Request {
        val userAgents = listOf(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            "Mozilla/5.0 (Linux; Android 13; SAMSUNG SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/23.0 Chrome/115.0.0.0 Mobile Safari/537.36"
        )
        val selectedAgent = userAgents[attempt % userAgents.size]

        return Request.Builder()
            .url(targetUrl)
            .addHeader("User-Agent", selectedAgent)
            .addHeader(
                "Connection",
                "close"
            ) // ⚡️ Убиваем старое соединение сразу после скачивания!
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
            .build()
    }
}
