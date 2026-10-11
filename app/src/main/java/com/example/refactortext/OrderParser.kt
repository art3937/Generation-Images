package com.example.refactortext

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    // ОСТАВЛЕНО ДЛЯ СОВМЕСТИМОСТИ С VIEWMODEL
    data class ParseResult(val text: String, val order: ParsedOrder)

    private data class AiNode(
        val type: String,
        val host: String,
        val model: String
    )

    // Оптимальные таймауты: не ждем мертвые сервера слишком долго
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun parseTextWithAI(inputText: String): ParseResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "[REQUEST] Оригинальный текст пользователя: $inputText")

        val finalPrompt = """
            Напиши небольшой пост на тему: «$inputText».
            Пиши живым, разговорным русским языком — так, будто пишешь для президента
            Обязательно добавь побольше подходящих эмодзи или картинок в тексте (не в конце отдельным блоком).
            Ответь ТОЛЬКО готовым текстом поста, тщательно проверяй текст на складность.
        """.trimIndent()

        // РАСШИРЕННЫЙ И ОБНОВЛЕННЫЙ ПУЛ СЕРВЕРОВ
        val aiPool = listOf(
            // Твой оригинальный проверенный вариант
            AiNode("GET_PATH", "text.pollinations.ai", ""),

            // Запросы через query к разным кластерам моделей
            AiNode("GET_QUERY", "text.pollinations.ai", "openai"),
            AiNode("GET_QUERY", "text.pollinations.ai", "qwen"),
            AiNode("GET_QUERY", "text.pollinations.ai", "llama"),

            // Новое стабильное зеркало Koala AI (Keyless)
            AiNode("GET_QUERY", "api.koala.sh", "gpt-4o-mini"),

            // Резервные ноды-зеркала
            AiNode("GET_QUERY", "text.lan2wan.ai", "openai"),
            AiNode("GET_QUERY", "text.lan2wan.ai", "llama"),

            // PUTERS с POST-JSON
            AiNode("POST_JSON", "://puter.com", "gpt-4o-mini")
        )

        var resultText = ""
        var attemptCounter = 0

        while (resultText.isBlank()) {
            val currentNode = aiPool[attemptCounter % aiPool.size]
            attemptCounter++

            val host = currentNode.host
            val model = currentNode.model
            Log.d(TAG, "[HTTP] Попытка №$attemptCounter. Сервер: $host | Модель: $model")

            try {
                val request: Request = when (currentNode.type) {
                    "GET_PATH" -> {
                        val url = HttpUrl.Builder()
                            .scheme("https")
                            .host(host)
                            .addPathSegment(finalPrompt)
                            .build()
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36")
                            .header("Accept", "text/plain, */*")
                            .get()
                            .build()
                    }
                    "GET_QUERY" -> {
                        val url = HttpUrl.Builder()
                            .scheme("https")
                            .host(host)
                            .addPathSegment("prompt")
                            .addPathSegment(finalPrompt) // Пробуем совмещенный безопасный путь
                            .apply {
                                if (model.isNotBlank()) addQueryParameter("model", model)
                            }
                            .build()
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .header("Accept", "*/*")
                            .get()
                            .build()
                    }
                    "POST_JSON" -> {
                        val url = HttpUrl.Builder().scheme("https").host(host).addPathSegment("v1").addPathSegment("ai").addPathSegment("chat").build()
                        val safePrompt = finalPrompt.replace("\"", "\\\"").replace("\n", "\\n")
                        val jsonBody = """{"messages": [{"role": "user", "content": "$safePrompt"}]}"""
                        val body = jsonBody.toRequestBody("application/json".toMediaType())
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .header("Accept", "application/json")
                            .post(body)
                            .build()
                    }
                    else -> continue
                }

                client.newCall(request).execute().use { resp ->
                    val code = resp.code
                    var respStr = resp.body?.string() ?: ""

                    if (resp.isSuccessful && respStr.isNotBlank()) {
                        // Убираем HTML-заглушки Cloudflare
                        if (respStr.trim().startsWith("<") && respStr.contains("html", ignoreCase = true)) {
                            Log.w(TAG, "[HTTP] Сервер $host вернул HTML-страницу ошибки вместо текста.")
                        } else {
                            // Извлекаем текст, если прилетел JSON
                            if (currentNode.type == "POST_JSON" && respStr.contains("\"content\"")) {
                                var cleanStr = respStr.substringAfter("\"content\"")
                                cleanStr = cleanStr.substringAfter("\"").substringBefore("\"")
                                    .replace("\\n", "\n").replace("\\\"", "\"")
                                if (cleanStr.isNotBlank()) resultText = cleanStr
                            } else {
                                // Для всех GET-запросов (чистый текст)
                                resultText = respStr
                            }
                        }
                    } else {
                        Log.w(TAG, "[HTTP] Сервер $host ответил кодом ошибки: $code")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[HTTP] Сетевое исключение на $host: ${e.localizedMessage}")
            }

            // Если на этом шаге текст пустой — ждем 2 секунды и идем на новый круг цикла
            if (resultText.isBlank()) {
                Log.w(TAG, "[HTTP] Точка не ответила. Смена сервера...")
                delay(2000)
            }
        }

        Log.d(TAG, "[HTTP] Успешно! Текст поста получен на попытке №$attemptCounter.")
        ParseResult(resultText, ParsedOrder(emptyList(), emptyList()))
    }
}
