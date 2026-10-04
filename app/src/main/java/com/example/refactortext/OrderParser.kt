package com.example.refactortext

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object OrderParser {
    private const val TAG = "BREAD_PARSER_LOG"

    // ОСТАВЛЕНО ДЛЯ СОВМЕСТИМОСТИ С VIEWMODEL
    data class ParsedOrder(
        val newPositions: List<Pair<String, Int>>,
        val exchangePositions: List<Pair<String, Int>>
    ) {
        val totalNew get() = newPositions.sumOf { it.second }
        val totalExchange get() = exchangePositions.sumOf { it.second }
    }

    // ОСТАВЛЕНО ДЛЯ СОВМЕСТИМОСТИ С VIEWMODEL (структура без изменений)
    data class ParseResult(val text: String, val order: ParsedOrder)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()



    suspend fun parseTextWithAI(inputText: String): ParseResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[REQUEST] Оригинальный текст пользователя: $inputText")

        try {
            val finalPrompt = """
        Напиши небольшой пост на тему: «$inputText».
        Пиши живым, разговорным русским языком — так, будто пишешь для призедента
        Обязательно добавь побольше подходящих эмодзи или картинок в тексте (не в конце отдельным блоком).
        Ответь ТОЛЬКО готовым текстом поста, тщательно проверяй текст на складность.
    """.trimIndent()
            // 3. GET-запрос к text.pollinations.ai без query-параметров.
            val httpUrl = HttpUrl.Builder()
                .scheme("https")
                .host("text.pollinations.ai")
                .addPathSegment(finalPrompt) // Автоматически экранирует пробелы и спецсимволы
                .build()

            val apiUrl = httpUrl.toString()
            Log.d(TAG, "[REQUEST] Итоговый URL: $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val code = resp.code
                var respStr = resp.body?.string() ?: ""



                // Защитная проверка от HTML-страниц
                if (respStr.startsWith("<") && respStr.contains("html")) {
                    Log.e(TAG, "[HTTP] Ошибка: Сервер вернул веб-страницу вместо текста.")
                    return@withContext ParseResult(
                        "Ошибка: Некорректный формат ответа сервера.",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }
                return@withContext ParseResult(respStr, ParsedOrder(emptyList(), emptyList()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Ошибка выполнения запроса: ${e.localizedMessage}", e)
            return@withContext ParseResult(
                "Ошибка: ${e.localizedMessage}",
                ParsedOrder(emptyList(), emptyList())
            )
        }
    }
}