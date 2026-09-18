package com.example.refactortext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

object ImageGenerator {

    private const val TAG = "IMAGE_LOG"

    // Базовый чистый клиент (основа для динамического подкидывания прокси)
    private val baseClient = OkHttpClient.Builder()
        .build()

    // Прямой клиент без прокси для аварийного режима
    private val directClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        //.proxy(Proxy.NO_PROXY)
        .proxy(Proxy.NO_PROXY)
        .build()

    suspend fun generateImage(
        context: Context,
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

        val enhancedPrompt = "$englishPrompt, highly detailed, photorealistic, cinematic lighting, sharp focus"


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

                // Получаем абсолютно новый прокси на круг
                val currentProxy = ProxyManager.getProxyForAttempt(attempt)
                Log.d(TAG, "[PROXY] Попытка $attempt. Запуск через прокси: $currentProxy")

                // Агрессивные таймауты: 4 секунды на коннект, 6 на чтение.
                val dynamicClient = baseClient.newBuilder()
                    .proxy(currentProxy)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                Log.d(TAG, "[IMAGE] '$russianPrompt'. Попытка $attempt. URL: $targetUrl")

                var bytes: ByteArray? = null

                // 1. Попытка запроса через динамический ПРОКСИ
                try {
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

                        // Проверка Cloudflare (HTML-заглушки)
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
                    Log.w(TAG, "[IMAGE] Сбой сети текущего прокси на попытке $attempt: ${proxyException.message}")
                    ProxyManager.reportProxyStatus(currentProxy, isSuccess = false)
                }

                // 2. АВАРИЙНЫЙ ОБХОД НАПРЯМУЮ (С жестким перехватом корутины)
                if (bytes == null) {
                    try {
                        Log.w(TAG, "[IMAGE] Аварийный режим напрямую без прокси для попытки $attempt...")

                        // ЖЕСТКИЙ ЛИМИТ: Если Cloudflare начнет тянуть время, корутина убьет его ровно через 3 секунды
                        bytes = kotlinx.coroutines.withTimeout(3000L.milliseconds) {
                            val req = generateRequest(targetUrl, attempt)
                            directClient.newCall(req).execute().use { response ->
                                if (!response.isSuccessful) return@use null
                                val body = response.body ?: return@use null
                                val rawBytes = body.bytes()

                                if (rawBytes.size < 500_000) {
                                    val str = String(rawBytes, Charsets.UTF_8)
                                    if (str.trim().startsWith("<!DOCTYPE") || str.contains("<html")) {
                                        return@use null
                                    }
                                }
                                if (rawBytes.isNotEmpty()) rawBytes else null
                            }
                        }
                    } catch (timeoutEx: kotlinx.coroutines.TimeoutCancellationException) {
                        // Ловим зависание Cloudflare и мгновенно идем дальше
                        Log.e(TAG, "[IMAGE] Аварийный режим напрямую ЗАВИС намертво. Корутина принудительно сбросила его.")
                    } catch (directException: Exception) {
                        Log.e(TAG, "[IMAGE] Крах прямого подключения: ${directException.message}")
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

            // === БЫСТРАЯ ЗАДЕРЖКА ПЕРЕД СЛЕДУЮЩЕЙ ИТЕРАЦИЕЙ (если картинка еще не сгенерирована) ===
            if (!isGenerated) {
                attempt++
                val delayTime = when (attempt) {
                    2 -> 1500L
                    3 -> 3000L
                    else -> 5000L
                }
                Log.w(TAG, "[IMAGE] Переключаемся на следующий прокси через $delayTime мс на попытку $attempt")
                delay(delayTime)
            }
        } // Конец while

        // ИСПРАВЛЕНО: Четкий и гарантированный возврат объекта для компилятора
        return@withContext finalBitmap
    }

    // ВАШ МЕТОД: Генерация запроса с ротацией User-Agent
    private fun generateRequest(targetUrl: okhttp3.HttpUrl, attempt: Int): Request {
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
            .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
    }
}
