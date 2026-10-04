package com.example.refactortext

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

object DeepLTranslator {
    private const val TAG = "DeepLTranslator"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Прямой надежный клиент без прокси для быстрой работы внутри РФ напрямую
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    /**
     * Принимает корявый русский текст.
     * Открытый ИИ-сервер Cohere (модель Command-R) полностью переписывает его на красивый язык.
     * Работает в России напрямую без VPN. Код сам ничего вручную не правит!
     */
    suspend fun translateEnToRu(rawRussianText: String): String = withContext(Dispatchers.IO) {
        if (rawRussianText.isBlank()) return@withContext ""

        Log.d(TAG, "[START] Отправка корявого текста в открытый ИИ Cohere...")

        var polishedText = rawRussianText
        try {
            // Официальный открытый эндпоинт Cohere AI (полностью доступен в РФ)
            val url = "https://cohere.ai"

            // Заберите бесплатный Trial API ключ на ://cohere.com и вставьте его ниже
            val cohereApiKey = "ВСТАВЬ_СЮДА_БЕСПЛАТНЫЙ_КЛЮЧ_COHERE"

            val promptForAi = """
                Ты профессиональный русский редактор. Возьми этот корявый, дословно переведенный русский текст и полностью перепиши его красивым, живым, литературным языком. 
                Исправь все падежи, окончания, расставь правильно запятые и знаки препинания. 
                Обязательно сохрани структуру абзацев, все хэштеги и эмодзи на своих местах. 
                Ответь ТОЛЬКО готовым исправленным текстом, без лишних фраз, комментариев и приветствий.
                
                Текст для исправления:
                $rawRussianText
            """.trimIndent()

            // Собираем стандартный JSON-пакет для ИИ с помощью встроенного org.json.JSONObject
            val rootJson = JSONObject().apply {
                put("model", "command-r-plus") // Мощная нейросеть, превосходно знающая русский язык
                put("message", promptForAi)
            }

            val requestBody = rootJson.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Authorization", "Bearer $cohereApiKey") // Передаем токен авторизации
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                val respStr = response.body?.string() ?: ""

                // Защитный заслон от HTML страниц
                if (respStr.trim().startsWith("<") || respStr.contains("html")) {
                    Log.e(TAG, "[TEXT ERROR] ИИ-сервер вернул HTML. Возвращаем оригинал.")
                    return@withContext rawRussianText
                }

                if (response.isSuccessful && respStr.isNotBlank()) {
                    val jsonResponse = JSONObject(respStr)

                    // По спецификации Cohere API готовый текст ответа лежит в поле text
                    if (jsonResponse.has("text")) {
                        var resultText = jsonResponse.getString("text").trim()

                        // Очистка от возможных markdown-тегов кода ```
                        if (resultText.contains("```")) {
                            resultText = resultText
                                .replace("```json", "")
                                .replace("```text", "")
                                .replace("```", "")
                                .trim()
                        }

                        if (resultText.isNotBlank()) {
                            polishedText = resultText
                            Log.d(TAG, "[TEXT SUCCESS] Открытый ИИ сам полностью отполировал русский текст.")
                        }
                    }
                } else {
                    Log.w(TAG, "[TEXT WARN] Ошибка ИИ-сервера: ${response.code} | Ответ: $respStr")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TEXT CRASH] Исключение во время работы ИИ: ${e.localizedMessage}", e)
        }

        return@withContext polishedText
    }
}
