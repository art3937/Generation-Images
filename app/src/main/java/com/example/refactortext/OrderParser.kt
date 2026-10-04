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

    private fun parseJsonPositions(jsonText: String): ParsedOrder {
        return ParsedOrder(emptyList(), emptyList())
    }

    fun formatText(o: ParsedOrder): String {
        return ""
    }

    /**
     * Генерирует пост через базовый, 100% бесплатный анонимный текстовый шлюз Pollinations.
     */
    suspend fun parseTextWithAI(inputText: String): ParseResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[REQUEST] Оригинальный текст пользователя: $inputText")

        try {
            // 1. Перевод темы ввода на английский язык с помощью вашего TextTranslator
          //  val englishTopic = TextTranslator.translateRuToEn(inputText)
           // Log.d(TAG, "[TRANSLATOR] Текст успешно переведен на английский: $englishTopic")

            // 2. Формируем промпт для ИИ
           // val finalPrompt = "make a post on this topic: $englishTopic"
            // ГЛАВНОЕ: явно просим про смайлики и стиль
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

//                Log.d(TAG, "[HTTP] Код: $code, Длина ответа: ${respStr.length}")
//
//                if (!resp.isSuccessful) {
//                    Log.e(TAG, "[HTTP] Ошибка: $code | $respStr")
//                    return@withContext ParseResult(
//                        "Ошибка Pollinations (Код: $code)\n$respStr",
//                        ParsedOrder(emptyList(), emptyList())
//                    )
//                }
//
//                respStr = respStr.trim()
//
//                // Очистка от возможных markdown-тегов кода ```
//                if (respStr.contains("```")) {
//                    respStr = respStr
//                        .replace("```json", "")
//                        .replace("```", "")
//                        .trim()
//                }

                // Защитная проверка от HTML-страниц
                if (respStr.startsWith("<") && respStr.contains("html")) {
                    Log.e(TAG, "[HTTP] Ошибка: Сервер вернул веб-страницу вместо текста.")
                    return@withContext ParseResult(
                        "Ошибка: Некорректный формат ответа сервера.",
                        ParsedOrder(emptyList(), emptyList())
                    )
                }

                Log.d(TAG, "[AI RESPONSE] Пост на английском языке успешно сгенерирован шлюзом.")

                // 4. Переводим полученный текст обратно на русский
              //  val russianTranslation = TextTranslator.translateEnToRu(respStr)
                // 3. Переводим через наш новый DeepLTranslator (качество ИИ-уровня)
           //   val russianTranslation = DeepLTranslator.translateEnToRu(russianTranslation1)

                // ИСПРАВЛЕНО: Склеиваем английский оригинал и русский перевод через двойные переносы и черточки
               // val combinedText = respStr

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
