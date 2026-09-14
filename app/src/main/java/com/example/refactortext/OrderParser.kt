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

    data class ParsedOrder(
        val newPositions: List<Pair<String, Int>>,
        val exchangePositions: List<Pair<String, Int>>
    ) {
        val totalNew get() = newPositions.sumOf { it.second }
        val totalExchange get() = exchangePositions.sumOf { it.second }
    }

    data class ParseResult(val text: String, val order: ParsedOrder)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun parseJsonPositions(jsonText: String): ParsedOrder {
        Log.d(TAG, "[PARSE] Входной текст в парсер:\n$jsonText")

        val newPositions = mutableListOf<Pair<String, Int>>()
        val exchangePositions = mutableListOf<Pair<String, Int>>()

        try {
            var cleanText = jsonText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val jsonStart = cleanText.indexOf('[')
            if (jsonStart == -1) {
                Log.e(TAG, "[PARSE] Квадратная скобка [ не найдена")
                return ParsedOrder(emptyList(), emptyList())
            }

            cleanText = cleanText.substring(jsonStart).trim()

            if (!cleanText.endsWith("]")) {
                if (cleanText.endsWith(",")) {
                    cleanText = cleanText.dropLast(1).trim()
                }
                if (cleanText.lastIndexOf('{') > cleanText.lastIndexOf('}')) {
                    if (cleanText.endsWith("\"") || cleanText.endsWith("заказ") || cleanText.endsWith("обмен")) {
                        cleanText += "\"}"
                    } else {
                        cleanText += "}"
                    }
                }
                cleanText += "]"
            }

            Log.d(TAG, "[PARSE] Восстановленный чистый JSON:\n$cleanText")

            val items = JSONArray(cleanText)
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                val qty = obj.optInt("qty", 1)
                val type = obj.optString("type", "заказ").lowercase().trim()

                if (name.isBlank()) continue

                if (type.contains("обмен")) {
                    exchangePositions.add(Pair(name, qty))
                } else {
                    newPositions.add(Pair(name, qty))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PARSE] Критическая ошибка разбора JSON", e)
        }

        val group = { list: List<Pair<String, Int>> ->
            list.groupBy({ it.first }, { it.second }).map { Pair(it.key, it.value.sum()) }
        }

        return ParsedOrder(group(newPositions), group(exchangePositions))
    }

    private fun formatText(o: ParsedOrder): String {
        val sb = StringBuilder()
        sb.appendLine("ЗАКАЗ ПО НАИМЕНОВАНИЯМ")
        sb.appendLine("----------------------------------")

        if (o.newPositions.isNotEmpty()) {
            sb.appendLine("### Новые позиции:")
            o.newPositions.forEach { sb.appendLine("${it.first} | ${it.second} шт.") }
            sb.appendLine()
        }

        if (o.exchangePositions.isNotEmpty()) {
            sb.appendLine("### На обмен:")
            o.exchangePositions.forEach { sb.appendLine("${it.first} | ${it.second} шт.") }
        }

        sb.appendLine("----------------------------------")
        sb.appendLine("ИТОГОВЫЙ ЗАКАЗ: ${o.totalNew} шт.")
        sb.appendLine("ИТОГОВЫЙ ОБМЕН: ${o.totalExchange} шт.")
        sb.appendLine("----------------------------------")

        return sb.toString()
    }

    suspend fun parseTextWithAI(inputText: String): ParseResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[REQUEST] Отправляю в ИИ текст:\n$inputText")

        val apiUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"

        // ИСПРАВЛЕНО: gpt:// вместо yandexgpt://, без /latest
        val modelUri = "gpt://${BuildConfig.YANDEX_FOLDER_ID}/yandexgpt-lite"

        // ДИАГНОСТИКА: выводим итоговый URI в лог
        Log.d(TAG, "[REQUEST] modelUri = $modelUri")
        Log.d(TAG, "[REQUEST] YANDEX_FOLDER_ID = ${BuildConfig.YANDEX_FOLDER_ID}")
        Log.d(TAG, "[REQUEST] YANDEX_API_KEY = ${BuildConfig.YANDEX_API_KEY?.take(6)}...")

        val jsonBody = JSONObject().apply {
            put("modelUri", modelUri)
            put("completionOptions", JSONObject().apply {
                put("stream", false)
                put("temperature", 0.1)
                put("maxTokens", 1500)
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("text", OrderPrompt.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("text", inputText)
                })
            })
        }

        Log.d(TAG, "[REQUEST] Тело запроса:\n${jsonBody.toString(2)}")

        val body = jsonBody.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        try {
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Api-Key ${BuildConfig.YANDEX_API_KEY}")
                .build()

            client.newCall(request).execute().use { resp ->
                val respStr = resp.body?.string() ?: ""
                Log.d(TAG, "[HTTP] Код: ${resp.code}, Тело: $respStr")

                if (!resp.isSuccessful) {
                    Log.e(TAG, "[HTTP] Ошибка ${resp.code}: $respStr")
                    return@withContext ParseResult(
                        "Ошибка Яндекса (Код: ${resp.code})\n$respStr",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                val jsonResponse = JSONObject(respStr)

                if (!jsonResponse.has("result")) {
                    Log.e(TAG, "[HTTP] Нет поля result в ответе")
                    return@withContext ParseResult(
                        "Неожиданный формат ответа от Яндекса",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                val resultObj = jsonResponse.getJSONObject("result")

                if (!resultObj.has("alternatives") || resultObj.getJSONArray("alternatives").length() == 0) {
                    Log.e(TAG, "[HTTP] Нет alternatives в ответе")
                    return@withContext ParseResult(
                        "Пустой ответ от ИИ",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                val aiText = resultObj.getJSONArray("alternatives")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("text")

                Log.d(TAG, "[AI RESPONSE] Текст от ИИ:\n$aiText")

                val order = parseJsonPositions(aiText)
                return@withContext ParseResult(formatText(order), order)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Сетевая ошибка: ${e.localizedMessage}", e)
            return@withContext ParseResult(
                "Сетевая ошибка: ${e.localizedMessage}\nПроверьте подключение.",
                ParsedOrder(emptyList(), emptyList())
            )
        }
    }
}
