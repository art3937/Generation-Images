package com.example.refactortext

import android.util.Log
import com.example.refactortext.BuildConfig.YANDEX_API_KEY
import com.example.refactortext.BuildConfig.YANDEX_FOLDER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GenerateTextYandex {

    private const val TAG = "BREAD_PARSER_LOG"

    // Ключи авторизации Яндекса
    // Теперь ключи берутся из защищенного сгенерированного файла
    private const val API_KEY = YANDEX_API_KEY
    private const val FOLDER_ID = YANDEX_FOLDER_ID

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
  создай красивый пост с картинками анимациями и эмодзи на эту тему:
    """.trimIndent()

    suspend fun parseTextWithAI(inputText: String): String = withContext(Dispatchers.IO) {
        try {
            // ✅ ИСПРАВЛЕНО: правильный endpoint
            val url = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

            val jsonBody = JSONObject().apply {
                put("modelUri", "gpt://$FOLDER_ID/yandexgpt-lite/latest")
                put("completionOptions", JSONObject().apply {
                    put("stream", false)
                    put("temperature", 0.1)
                    // ✅ ИСПРАВЛЕНО: maxTokens должен быть строкой
                    put("maxTokens", "1500")
                })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("text", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("text", inputText)
                    })
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Api-Key $API_KEY")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "[TEXT] Ошибка HTTP ${response.code}. Ответ сервера: $responseBody")
                    return@withContext "Ошибка Яндекса (Код: ${response.code})\nОтвет: $responseBody"
                }

                if (responseBody.isNullOrBlank()) {
                    return@withContext "Ошибка: Яндекс вернул пустой ответ"
                }

                val jsonResponse = JSONObject(responseBody)
                val resultObj = jsonResponse.getJSONObject("result")
                val alternatives = resultObj.getJSONArray("alternatives")
                val firstAlternative = alternatives.getJSONObject(0)
                val messageObj = firstAlternative.getJSONObject("message")
                val textResult = messageObj.getString("text")

                return@withContext textResult.trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Сетевая ошибка: ${e.localizedMessage}\nПроверьте подключение и убедитесь, что VPN выключен."
        }
    }
}