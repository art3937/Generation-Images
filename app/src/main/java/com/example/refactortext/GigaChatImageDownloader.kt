package com.example.refactortext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.refactortext.BuildConfig.SBER_SECRET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object GigaChatImageDownloader {
    private const val AUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private const val AUTH_KEY = SBER_SECRET


    private val client: OkHttpClient by lazy { createSberCompliantOkHttpClient() }

    // Метод создания OkHttp клиента с безопасной конфигурацией протокола
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    private fun createSberCompliantOkHttpClient(): OkHttpClient {
        // Настройка доверительных сертификатов для игнорирования ошибок сертификата
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate>? = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllCerts), SecureRandom())
        }

        // Создаем безопасный клиент с нужными параметрами и жестко фиксируем HTTP/1.1 (по требованию WAF Сбера)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    suspend fun generateImageFromPrompt(promptText: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val token = fetchOAuthToken()

            // 1. Получаем список моделей
            val modelsUrl = "https://api.giga.chat/v1/models"
            val modelsReq = Request.Builder()
                .url(modelsUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(modelsReq).execute().use { modelsResp ->
                if (!modelsResp.isSuccessful) {
                    Log.e("GigaChat", "Ошибка получения моделей: ${modelsResp.code}")
                    return@withContext null
                }
                val modelsJson = JSONObject(modelsResp.body?.string() ?: "{}")
                val data = modelsJson.getJSONArray("data")
                Log.e("GigaChat", "Модели $data")
                if (data.length() == 0) {
                    Log.e("GigaChat", "Нет доступных моделей")
                    return@withContext null
                }

                val modelId = data.getJSONObject(4).getString("id")
                Log.d("GigaChat", "Используем модель: $modelId")

                // 2. Формируем JSON-объект со всеми параметрами для запроса к нейросети
                val jsonParams = JSONObject().apply {
                    // Указываем ID модели, которую выбрали на предыдущем шаге (например, GigaChat-Pro)
                    put("model", modelId)

                    // Говорим модели автоматически решать, когда нужно вызывать функции (инструменты)
                    put("function_call", "auto")

                    // Создаем массив доступных для модели функций (инструментов)
                    val functions = JSONArray().apply {
                        // Добавляем встроенную функцию Сбера "text2image", которая отвечает за генерацию картинок
                        put(JSONObject().apply { put("name", "text2image") })
                    }
                    // Прикрепляем массив функций к общему JSON-запросу
                    put("functions", functions)

                    // Создаем массив сообщений (историю диалога и инструкции), чтобы передать контекст
                    val messages = JSONArray().apply {
                        // Нейтральный системный промпт: задаем жесткое правило поведения для ИИ
                        put(JSONObject().apply {
                            put("role", "system") // Роль "system" — это скрытая инструкция от разработчика
                            put("content", "Ты умеешь генерировать изображения с помощью функции text2image. Всегда используй её, если пользователь просит нарисовать что-либо.")
                        })

                        // Передаем то, что написал сам пользователь приложения
                        put(JSONObject().apply {
                            put("role", "user") // Роль "user" — это сообщение от реального пользователя
                            // Подставляем текст промпта (например, "Нарисуй рыжую лису")
                            put("content", promptText)
                        })
                    }
                    // Прикрепляем сформированную историю сообщений к общему JSON-запросу
                    put("messages", messages)
                }

                val genUrl = "https://api.giga.chat/v1/chat/completions"
                val genReq = Request.Builder()
                    .url(genUrl)
                    .post(jsonParams.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(genReq).execute().use { genResp ->
                    val responseStr = genResp.body?.string() ?: ""
                    if (!genResp.isSuccessful) {
                        Log.e("GigaChat", "Ошибка генерации: Код ${genResp.code}, тело: $responseStr")
                        return@withContext null
                    }

                    val choices = JSONObject(responseStr).getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    val content = message.getString("content")
                    Log.d("GigaChat", "Ответ модели (raw): $content")

                    // Ищем src="<uuid>"
                    val imgRegex = Regex("src=\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                    val fileId = imgRegex.find(content)?.groupValues?.getOrNull(1)

                    if (fileId.isNullOrBlank()) {
                        Log.w("GigaChat", "В ответе нет ID картинки. Функция text2image не была вызвана моделью.")
                        // Для отладки: выводим полный ответ, чтобы видеть, что именно сказала модель
                        Log.d("GigaChat", "Полный ответ модели: $content")
                        return@withContext null
                    }

                    Log.d("GigaChat", "Найден ID файла: $fileId")

                    // 3. Скачиваем картинку
                    val dlUrl = "https://api.giga.chat/v1/files/$fileId/content"
                    val dlReq = Request.Builder()
                        .url(dlUrl)
                        .get()
                        .addHeader("Authorization", "Bearer $token")
                        .build()

                    client.newCall(dlReq).execute().use { dlResp ->
                        if (!dlResp.isSuccessful) {
                            Log.e("GigaChat", "Ошибка скачивания: ${dlResp.code}")
                            return@withContext null
                        }
                        val bytes = dlResp.body?.bytes() ?: return@withContext null
                        return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("GigaChat", "Непредвиденная ошибка: ${ex.message}", ex)
        }
        return@withContext null
    }


    private fun fetchOAuthToken(): String? {
        val formBody = FormBody.Builder()
            .add("scope", "GIGACHAT_API_PERS")
            .build()

        val request = Request.Builder()
            .url(AUTH_URL)
            .post(formBody)
            .addHeader("Authorization", "Basic ${AUTH_KEY.trim()}")
            .addHeader("RqUID", UUID.randomUUID().toString())
            .addHeader("Accept", "application/json")
            .build()

        return try {
            // ИСПРАВЛЕНО: Вместо awaitResponse() используем стандартный execute()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GigaChat", "Ошибка получения токена: Код ${response.code}, причина: ${response.message}")
                    return@use null
                }

                val jsonBody = response.body?.string()?.let { JSONObject(it) }
                jsonBody?.getString("access_token")
            }
        } catch (e: Exception) {
            Log.e("GigaChat", "Ошибка аутентификации", e)
            null
        }
    }


    suspend fun generate3DPictureRobust(context: Context, promptText: String): File? = withContext(Dispatchers.IO) {
        val tag = "GigaChatImg"
        val reqId = UUID.randomUUID().toString()

        Log.d(tag, "[$reqId] START: generate3DPictureRobust, prompt=$promptText")

        try {
            val token = fetchOAuthToken()
            if (token.isNullOrBlank()) {
                Log.e(tag, "[$reqId] Token is empty")
                return@withContext null
            }

            // 1. Берём первую модель (без проверок capabilities)
            val modelsUrl = "https://api.giga.chat/v1/models"
            val modelsReq = Request.Builder()
                .url(modelsUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("RqUID", reqId)
                .build()

            client.newCall(modelsReq).execute().use { modelsResp ->
                if (!modelsResp.isSuccessful) {
                    Log.e(tag, "[$reqId] Models list error: ${modelsResp.code}")
                    return@withContext null
                }

                val modelsJson = JSONObject(modelsResp.body?.string() ?: "{}")
                val data = modelsJson.getJSONArray("data")
                if (data.length() == 0) {
                    Log.e(tag, "[$reqId] No models found")
                    return@withContext null
                }

                val modelId = data.getJSONObject(5).getString("id")
                Log.i(tag, "[$reqId] Using model: $modelId")

                // 2. Формируем запрос (с functions для правильного пути + auto)
                val fullPrompt = """
Деревянный стул в стиле ар‑деко. Вид в три четверти. 
Детали: резные ножки, бархатная обивка цвета пыльной розы. 
Освещение: тёплый студийный свет, мягкие тени, лёгкий эффект глубины резкости. 
Стиль: фотореалистичный концепт‑арт, 8k, высокая детализация, без текста, без водяных знаков.
Не используй стандартные модели и заглушки.
""".trimIndent()


                val jsonParams = JSONObject().apply {
                    put("model", modelId)
                    put("function_call", "auto")
                    put("functions", JSONArray().apply {
                        put(JSONObject().apply { put("name", "text2image") })
                    })
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "Ты — генератор изображений. Если можешь, вызови функцию text2image. Если нет — верни картинку тегом <img>.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", fullPrompt)
                        })
                    })
                }

                val genUrl = "https://api.giga.chat/v1/chat/completions"
                val genReq = Request.Builder()
                    .url(genUrl)
                    .post(jsonParams.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/json")
                    .addHeader("RqUID", reqId)
                    .build()

                client.newCall(genReq).execute().use { genResp ->
                    val raw = genResp.body?.string() ?: ""
                    if (!genResp.isSuccessful) {
                        Log.e(tag, "[$reqId] Generation error: ${genResp.code}, body=$raw")
                        return@withContext null
                    }

                    Log.d(tag, "[$reqId] Raw response (first 300 chars): ${raw.take(300)}")

                    val choices = JSONObject(raw).getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message")

                    var fileId: String? = null

                    // --- ВАРИАНТ 1: правильный путь через tool_calls ---
                    if (message.has("tool_calls") && message.getJSONArray("tool_calls").length() > 0) {
                        val toolCalls = message.getJSONArray("tool_calls")
                        val argsStr = toolCalls.getJSONObject(0)
                            .getJSONObject("function")
                            .getString("arguments")
                        val args = JSONObject(argsStr)
                        fileId = args.optString("file_id") ?: args.optString("image_id")
                        Log.i(tag, "[$reqId] Got fileId from tool_calls: $fileId")
                    }
                    // --- ВАРИАНТ 2: fallback — парсим content на <img src="..." ---
                    else {
                        val content = message.optString("content", "")
                        if (content.contains("<img src=\"")) {
                            val start = content.indexOf("<img src=\"") + "<img src=\"".length
                            val end = content.indexOf("\"", start)
                            if (end > start) {
                                fileId = content.substring(start, end)
                                Log.w(tag, "[$reqId] Fallback: fileId parsed from content (no tool_calls): $fileId")
                            } else {
                                Log.w(tag, "[$reqId] Found <img>, but could not parse src")
                            }
                        } else {
                            Log.e(tag, "[$reqId] No tool_calls and no <img> in content. Content preview: ${content.take(100)}")
                        }
                    }

                    if (fileId.isNullOrBlank()) {
                        Log.e(tag, "[$reqId] Could not extract fileId")
                        return@withContext null
                    }

                    // 3. Скачиваем картинку
                    val dlUrl = "https://api.giga.chat/v1/files/$fileId/content"
                    val dlReq = Request.Builder()
                        .url(dlUrl)
                        .get()
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("RqUID", reqId)
                        .build()

                    client.newCall(dlReq).execute().use { dlResp ->
                        if (!dlResp.isSuccessful) {
                            Log.e(tag, "[$reqId] Download error: ${dlResp.code}")
                            return@withContext null
                        }
                        val inputStream = dlResp.body?.byteStream() ?: return@withContext null

                        // Сохраняем в cacheDir (Android-стиль)
                        val fileName = "kandinsky_${fileId.replace("-", "")}.png"
                        val imageFile = File(context.cacheDir, fileName)

                        FileOutputStream(imageFile).use { out ->
                            inputStream.copyTo(out)
                        }

                        Log.i(tag, "[$reqId] Image saved: ${imageFile.absolutePath}, size=${imageFile.length()} bytes")
                        return@withContext imageFile
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(tag, "[$reqId] Unexpected exception", ex)
            return@withContext null
        }
    }

}
