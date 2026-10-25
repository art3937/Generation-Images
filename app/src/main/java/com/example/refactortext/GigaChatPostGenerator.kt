package com.example.refactortext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.refactortext.BuildConfig.SBER_SECRET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

object GigaChatPostGenerator {
    private const val AUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private const val AUTH_KEY = SBER_SECRET
    private const val TAG = "GigaChatText"

    private val client: OkHttpClient by lazy { createSberCompliantOkHttpClient() }

    // Храним ID модели. null = ещё не запросили.
    private var cachedModelId: String? = null

    /**
     * Блок init: запускается 1 раз при первом обращении к объекту.
     * Загружает ID модели в фоне, чтобы при генерации поста не делать лишний запрос.
     */
    init {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            cachedModelId = getModelIdOnce()
        }
    }


    private fun createSberCompliantOkHttpClient(): OkHttpClient {
        val trustAllCerts = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate>? = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllCerts), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Единственный метод, который делает сетевой запрос к списку моделей.
     * Вызывается 1 раз за сессию благодаря init.
     * ВАЖНО: тут оставляем выбор по индексу 4, как ты просил.
     */
    private suspend fun getModelIdOnce(): String? {
        val token = fetchOAuthToken() ?: return null
        val modelsUrl = "https://api.giga.chat/v1/models"
        val modelsReq = Request.Builder()
            .url(modelsUrl)
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(modelsReq).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Ошибка получения списка моделей: ${response.code}")
                    return@withContext null
                }
                val jsonString = response.body?.string() ?: return@withContext null
                val modelsJson = JSONObject(jsonString)
                val data = modelsJson.getJSONArray("data")

                if (data.length() < 5) {
                    Log.e(TAG, "Недостаточно моделей в списке. Всего: ${data.length()}")
                    return@withContext null
                }

                // Оставляем строго по индексу 4 (пятая модель в списке)
                return@withContext data.getJSONObject(4).getString("id")
            }
        }
    }

    /**
     * Генерирует текстовый пост. Возвращает текст или null при ошибке.
     */
    suspend fun generatePost(promptText: String): String? = withContext(Dispatchers.IO) {
        try {
            val token = fetchOAuthToken() ?: return@withContext null

            // Берём ID модели из кэша. Если null — значит, ещё не успели загрузиться.
            val modelId = cachedModelId
            if (modelId.isNullOrBlank()) {
                Log.w(TAG, "ID модели ещё не загружен. Подождите немного или перезапустите приложение.")
                return@withContext null
            }

            // Формируем JSON-запрос БЕЗ функции text2image
            val jsonParams = JSONObject().apply {
                put("model", modelId)
                put("function_call", "auto")

                val messages = JSONArray().apply {
                    // Системный промпт: просим писать пост в стиле соцсети
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            " Напиши подробный, цепляющий пост для соцсети (Telegram/VK) на тему: \"$promptText\". " +
                                    "Используй эмодзи побольше или анимацию, добавь 2–3 релевантных хэштега в конце,."
                        )
                    })
                    // Пользовательский промпт
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", promptText)
                    })
                }
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
                val responseStr = genResp.body?.string() ?: return@withContext null
                if (!genResp.isSuccessful) {
                    Log.e(TAG, "Ошибка генерации поста: Код ${genResp.code}, тело: $responseStr")
                    return@withContext null
                }

                val choices = JSONObject(responseStr).getJSONArray("choices")
                if (choices.length() == 0) return@withContext null

                val message = choices.getJSONObject(0).getJSONObject("message")
                val content = message.getString("content")

                Log.d(TAG, "Сгенерированный пост: $content")
                return@withContext content
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Непредвиденная ошибка при генерации поста", ex)
            return@withContext null
        }
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Ошибка токена: ${response.code}")
                    return@use null
                }
                response.body?.string()?.let { JSONObject(it) }?.getString("access_token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка аутентификации", e)
            null
        }
    }
}
