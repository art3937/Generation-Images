package com.example.refactortext

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
}
