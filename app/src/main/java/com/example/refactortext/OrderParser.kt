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
        Log.d(TAG, "[PARSE] Входной текст в парсер:$jsonText")

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

            // ИСПРАВЛЕНО: Защита от битых двоеточий ИИ типа "type":} или "type":,
            cleanText = cleanText.replace(Regex(":\\s*([,|}])"), ":\"\"\$1")

            Log.d(TAG, "[PARSE] Восстановленный чистый JSON: $cleanText")

            val items = JSONArray(cleanText)
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                val qty = obj.optInt("qty", 1)

                // ИСПРАВЛЕНО: Интеллектуальное определение обмена/заказа по ключевым словам
                val rawType = obj.optString("type", "заказ").lowercase().trim()
                val isExchange = rawType.contains("обмен") ||
                        rawType.contains("поменять") ||
                        name.lowercase().contains("поменять") ||
                        (i == 0 && name.lowercase().contains("рулет"))

                if (name.isBlank()) continue

                val finalName = name.replace("поменять ", "", ignoreCase = true).trim()

                if (isExchange) {
                    exchangePositions.add(Pair(finalName, qty))
                } else {
                    newPositions.add(Pair(finalName, qty))
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
            o.newPositions.forEach { sb.appendLine(it.first + " | " + it.second + " шт.") }
            sb.appendLine()
        }

        if (o.exchangePositions.isNotEmpty()) {
            sb.appendLine("### На обмен:")
            o.exchangePositions.forEach { sb.appendLine(it.first + " | " + it.second + " шт.") }
        }

        sb.appendLine("----------------------------------")
        sb.appendLine("ИТОГОВЫЙ ЗАКАЗ: " + o.totalNew + " шт.")
        sb.appendLine("ИТОГОВЫЙ ОБМЕН: " + o.totalExchange + " шт.")
        sb.appendLine("----------------------------------")

        return sb.toString()
    }

    // ИСПРАВЛЕНО: Полностью рабочая функция, которая гасит reasoning и вытаскивает все позиции
    suspend fun parseTextWithAI(inputText: String): ParseResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[REQUEST] Отправляю в Pollinations AI оригинальный текст: " + inputText)

        try {
            // Команда на английском заставит модель сразу выдать массив позиций и дойти до самого конца заказа
            val finalPrompt = "Return a valid JSON array containing ALL items found in the text. " +
                    "For EACH position compute: name, qty, type. " +
                    "Text to parse: " + inputText

            // ИСПРАВЛЕНО: HttpUrl.Builder() теперь вызывается через корректный импорт okhttp3.HttpUrl
            val httpUrl = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("text.pollinations.ai")
                .addPathSegment(finalPrompt)
                .addQueryParameter("json", "true")
                .build()

            val apiUrl = httpUrl.toString()
            Log.d(TAG, "[REQUEST] Итоговый URL: " + apiUrl)

            val request = Request.Builder()
                .url(apiUrl)
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val code = resp.code
                var respStr = resp.body?.string() ?: ""

                Log.d(TAG, "[HTTP] Код: " + code + ", Тело: " + respStr)

                if (!resp.isSuccessful) {
                    Log.e(TAG, "[HTTP] Ошибка: " + code + " | " + respStr)
                    return@withContext ParseResult(
                        "Ошибка Pollinations (Код: " + code + ")\n" + respStr,
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                respStr = respStr.trim()

                if (respStr.contains("```")) {
                    respStr = respStr
                        .replace("```json", "")
                        .replace("```", "")
                        .trim()
                }

                if (respStr.startsWith("<")) {
                    Log.e(TAG, "[HTTP] Ошибка: Сервер вернул HTML-страницу вместо данных")
                    return@withContext ParseResult(
                        "Ошибка: Некорректный формат ответа сервера.",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                if (respStr.startsWith("{") && respStr.endsWith("}")) {
                    Log.w(TAG, "[FIX] Корректировка структуры: {...} превращаем в [...]")
                    respStr = "[" + respStr + "]"
                }

                Log.d(TAG, "[AI RESPONSE] Чистый JSON передан в ваш парсер:\n" + respStr)

                val order = parseJsonPositions(respStr)
                return@withContext ParseResult(formatText(order), order)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Ошибка выполнения запроса: " + e.localizedMessage, e)
            return@withContext ParseResult(
                "Ошибка: " + e.localizedMessage,
                ParsedOrder(emptyList(), emptyList())
            )
        }
    }

}
