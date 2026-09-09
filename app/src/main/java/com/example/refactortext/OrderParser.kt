package com.example.refactortext

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OrderParser {

    private const val TAG = "BREAD_PARSER_LOG"

    // Ключи авторизации Яндекса
    // Теперь ключи берутся из защищенного сгенерированного файла
    private val apiKey = BuildConfig.YANDEX_API_KEY
    private val folderId = BuildConfig.YANDEX_FOLDER_ID

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        Ты — профессиональный парсер текстовых заявок для хлебозавода. 
        Твоя задача — прочитать хаотичный текст голосового ввода, понять контекст и составить красивую structured текстовую таблицу.
        
        Обязательно разделяй позиции на два блока: новые позиции (заказ/привезти/добавить) и обмен (поменять/возврат/отдать).
        Складывай и группируй одинаковые позиции. Если количество не указано, считай что это 1 шт.
        
        Используй ТОЛЬКО следующие официальные наименования из каталога:
        - хлеб белый тостовый
        - хлеб бородинский
        - Хлеб чайный тостовый
        - хлеб формовой в/с
        - хлеб формовой 1 сорт/Отрубной
        - батон ржано-пшеничный
        - батон нарезной
        - батон столичный
        - Ролл пшеничный
        - Лаваш Афипский
        - Лаваш квадратный
        - багет французский
        - багет чесночный
        - mini багет французкий и булки
        - рулет с маком
        - плюшка московская
        - улитка маковая
        - булка с маком
        - хлеб бездрожжевой
        - пирожок
        - pletenka со сгущенкой
        - колобок
        - ракушка
        - хлеб с изюмом

        Выведи ответ СТРОГО в таком формате (без лишних приветствий, только эта таблица):
        
        ЗАКАЗ ПО НАИМЕНОВАНИЯМ (ИИ МАКС)
        ----------------------------------
        Наименование             | Кол-во
        ----------------------------------
        ### Новые позиции:
        хлеб белый тостовый      | 1 шт.
        булка с маком            | 3 шт.
        
        ### На обмен:
        булка с маком            | 3 шт.
        пирожок                  | 2 шт.
        ----------------------------------
        ИТОГОВЫЙ ЗАКАЗ: 4 шт.
        ИТОГОВЫЙ ОБМЕН: 5 шт.
        ----------------------------------
    """.trimIndent()

    suspend fun parseTextWithAI(inputText: String): String = withContext(Dispatchers.IO) {
        try {
            // ✅ ИСПРАВЛЕНО: правильный endpoint
            val url = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

            val jsonBody = JSONObject().apply {
                put("modelUri", "gpt://$folderId/yandexgpt-lite/latest")
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
                .addHeader("Authorization", "Api-Key $apiKey")
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