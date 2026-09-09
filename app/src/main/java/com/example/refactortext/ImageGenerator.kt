package com.example.refactortext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ImageGenerator {

    private const val TAG = "BREAD_PARSER_LOG"
    private const val MAX_RETRIES = 3

    private const val STABLE_PROXY_HOST = "45.43.60.220"
    private const val STABLE_PROXY_PORT = 8080
    val proxyAddress = InetSocketAddress(STABLE_PROXY_HOST, STABLE_PROXY_PORT)
    val appProxy = Proxy(Proxy.Type.HTTP, proxyAddress)

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)   // больше на медленных сетях
        .readTimeout(50, TimeUnit.SECONDS)      // генерация может быть долгой
        .proxy(appProxy)
        .build()

    // Отдельный клиент БЕЗ прокси для аварийного прямого обхода
    private val directClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    private val mutexMap = mutableMapOf<String, Mutex>()

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun generateImage(context: Context, russianPrompt: String): Bitmap? =
        withContext(Dispatchers.IO) {

            // ЛОГИРОВАНИЕ ПЕРЕВОДА
            Log.d(TAG, "[IMAGE] ---> СТАРТ ПЕРЕВОДА. Исходный текст: $russianPrompt")
            val englishPrompt = try {
                val translated = TextTranslator.translateRuToEn(russianPrompt)
                Log.i(TAG, "[IMAGE] ---> УСПЕХ ПЕРЕВОДА: '$russianPrompt' переведено в '$translated'")
                translated
            } catch (e: Exception) {
                Log.e(TAG, "[IMAGE] ---> ОШИБКА ПЕРЕВОДА: ${e.localizedMessage}. Используем исходный текст.")
                russianPrompt
            }

            val enhancedPrompt = "$englishPrompt, highly detailed, photorealistic, 8k resolution, cinematic lighting, masterpiece"

            // Уникальный идентификатор запроса для синхронизации потоков (вместо имени файла кэша)
            val requestKey = sha256(enhancedPrompt)

            val mutex = synchronized(mutexMap) {
                mutexMap.getOrPut(requestKey) { Mutex() }
            }

            try {
                mutex.withLock {
                    // Генерация с ретраями
                    var lastError: Exception? = null
                    for (attempt in 1..MAX_RETRIES) {
                        try {
                            val randomSeed = (1..100_000).random()
                            val targetUrl = HttpUrl.Builder()
                                .scheme("https")
                                .host("image.pollinations.ai")
                                .addPathSegment("p")
                                .addPathSegment(enhancedPrompt)
                                .addQueryParameter("width", "512")
                                .addQueryParameter("height", "512")
                                .addQueryParameter("model", "flux")
                                .addQueryParameter("seed", randomSeed.toString())
                                .addQueryParameter("nologo", "true")
                                .build()

                            Log.d(TAG, "[IMAGE] Попытка $attempt/$MAX_RETRIES через ПРОКСИ ($STABLE_PROXY_HOST). URL: $targetUrl")

                            var bytes: ByteArray? = null

                            // 1. Попытка запроса через основной клиент с ПРОКСИ
                            try {
                                bytes = client.newCall(generateRequest(targetUrl)).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        Log.e(TAG, "[IMAGE] ОШИБКА ПРОКСИ: HTTP ${response.code} (попытка $attempt)")
                                        return@use null
                                    }
                                    val body = response.body ?: run {
                                        Log.w(TAG, "[IMAGE] ПРОКСИ ОК, но тело ответа пустое.")
                                        return@use null
                                    }
                                    val rawBytes = body.bytes()
                                    if (rawBytes.isEmpty()) return@use null

                                    // Проверка на Cloudflare HTML заглушки вместо картинки
                                    if (rawBytes.size < 500_000) {
                                        val textCheck = String(rawBytes, Charsets.UTF_8)
                                        if (textCheck.trim().startsWith("<!DOCTYPE") || textCheck.contains("<html")) {
                                            Log.e(TAG, "[IMAGE] ОШИБКА ПРОКСИ: Сбой! Скачался HTML вместо картинки: ${textCheck.take(200)}")
                                            return@use null
                                        }
                                    }
                                    Log.i(TAG, "[IMAGE] УСПЕШНО СКАЧАНО ЧЕРЕЗ ПРОКСИ! Размер: ${rawBytes.size} байт.")
                                    rawBytes
                                }
                            } catch (proxyException: Exception) {
                                Log.w(TAG, "[IMAGE] СБОЙ СЕТИ ПРОКСИ на попытке $attempt: ${proxyException.localizedMessage}")
                            }

                            // 2. АВАРИЙНЫЙ ОБХОД НАПРЯМУЮ БЕЗ ПРОКСИ (если прокси выдал null или упал)
                            if (bytes == null) {
                                Log.w(TAG, "[АВАРИЙНЫЙ РЕЖИМ] Прокси подвёл. Пробуем скачать НАПРЯМУЮ без прокси...")
                                try {
                                    bytes = directClient.newCall(generateRequest(targetUrl)).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            Log.e(TAG, "[IMAGE] ОШИБКА НАПРЯМУЮ: HTTP ${response.code}")
                                            return@use null
                                        }
                                        val body = response.body ?: return@use null
                                        val rawBytes = body.bytes()
                                        if (rawBytes.isNotEmpty()) {
                                            Log.i(TAG, "[IMAGE] УСПЕШНО СКАЧАНО НАПРЯМУЮ БЕЗ ПРОКСИ! Размер: ${rawBytes.size} байт.")
                                            rawBytes
                                        } else null
                                    }
                                } catch (directException: Exception) {
                                    Log.e(TAG, "[IMAGE] Крах прямого подключения: ${directException.localizedMessage}")
                                }
                            }

                            // 3. Обработка успешного массива байт (из любого источника) и мгновенный возврат Bitmap
                            if (bytes != null) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    Log.d(TAG, "[IMAGE] Изображение успешно декодировано в Bitmap в RAM.")
                                    return@withContext bitmap
                                }
                            }

                            if (attempt < MAX_RETRIES) delay(2000L * attempt)
                        } catch (e: Exception) {
                            lastError = e
                            Log.e(TAG, "[IMAGE] Общий сбой итерации $attempt: ${e.localizedMessage}")
                            if (attempt < MAX_RETRIES) delay(2000L * attempt)
                        }
                    }

                    Log.e(TAG, "[IMAGE] Все попытки исчерпаны. Последняя ошибка: ${lastError?.localizedMessage}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[IMAGE] Крах внутри блокировки: ${e.localizedMessage}", e)
            } finally {
                synchronized(mutexMap) { mutexMap.remove(requestKey) }
            }

            return@withContext null
        }

    private fun generateRequest(targetUrl: HttpUrl): Request =
        Request.Builder()
            .url(targetUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .get()
            .build()
}
