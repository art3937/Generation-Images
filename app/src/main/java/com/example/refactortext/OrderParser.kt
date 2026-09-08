package com.example.refactortext

import android.util.Log
import com.example.audiobible.generatorAll.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object OrderParser {

    private const val TAG = "BREAD_PARSER_LOG"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Официальный каталог хлебозавода для точной привязки на самом устройстве
    private val catalog = listOf(
        "хлеб белый тостовый", "хлеб бородинский", "Хлеб чайный тостовый",
        "хлеб формовой в/с", "хлеб формовой 1 сорт/Отрубной", "батон ржано-пшеничный",
        "батон нарезной", "батон столичный", "Ролл пшеничный", "Лаваш Афипский",
        "Лаваш квадратный", "багет французский", "багет чесночный",
        "mini багет французкий и булки", "рулет с маком", "плюшка московская",
        "улитка маковая", "булка с маком", "хлеб бездрожжевой", "пирожок",
        "плетенка со сгущенкой", "колобок", "ракушка", "хлеб с изюмом"
    )

    suspend fun parseTextWithAI(inputText: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "==================== START POLLINATIONS GET ====================")
        Log.d(TAG, "[TEXT] Входящий сырой текст:\n$inputText")

        try {
            // 1. ПЕРЕВОДИМ ХАОС НА АНГЛИЙСКИЙ (чтобы ИИ идеально связал цифры "три", "две" с товарами)
            val englishInput = try {
                TextTranslator.translateRuToEn(inputText)
            } catch (e: Exception) {
                inputText
            }

            // Ультра-короткая инструкция для ИИ. Сервер пропустит её бесплатно и без ошибок!
            val shortPrompt = "List items from text under categories Order and Exchange with numbers. If item has negation like don't need or left, ignore it. Text: $englishInput"

            // СБОРКА ЧЕРЕЗ HTTPURL.BUILDER (Точно так же, как у тебя в рабочем генераторе картинок!)
            val targetUrl = HttpUrl.Builder()
                .scheme("https")
                .host("text.pollinations.ai") // Тот самый бесплатный сервер
                .addPathSegment(shortPrompt)  // Кидаем короткий текст прямо в путь ссылки
                .addQueryParameter("model", "openai")
                .build()

            Log.d(TAG, "[TEXT] Отправка GET-запроса на URL: $targetUrl")

            val request = Request.Builder()
                .url(targetUrl)
                .get() // Строго GET! Ошибки 405 больше физически не будет
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody.isNullOrBlank() || responseBody.contains("<!DOCTYPE")) {
                    Log.e(TAG, "[TEXT] Сервер выдал ошибку или HTML-страницу. Код: ${response.code}")
                    return@withContext "Ошибка ИИ: Сбой потока данных. Попробуйте еще раз."
                }

                Log.d(TAG, "[TEXT] Чистый английский ответ от ИИ получен:\n$responseBody")

                // 2. УМНАЯ СБОРКА ТАБЛИЦЫ НА KOTLIN С ПРИВЯЗКОЙ К КАТАЛОГУ (Мгновенно и бесплатно)
                val lines = responseBody.lines()
                val orderItems = mutableListOf<String>()
                val exchangeItems = mutableListOf<String>()

                var isExchangeSection = false
                var totalOrder = 0
                var totalExchange = 0

                for (line in lines) {
                    val lowerLine = line.lowercase()
                    if (lowerLine.contains("exchange") || lowerLine.contains("return")) {
                        isExchangeSection = true
                        continue
                    }
                    if (lowerLine.contains("order") || lowerLine.contains("list")) {
                        isExchangeSection = false
                        continue
                    }

                    // Достаем цифру количества штук
                    val count = "\\d+".toRegex().find(line)?.value?.toIntOrNull() ?: 1

                    // Очищаем английскую строчку товара от мусора
                    val cleanLine = line.replace("\\d+".toRegex(), "").replace("-", "").trim().lowercase()

                    if (cleanLine.isNotBlank()) {
                        // Магия: сопоставляем корень слова с официальным русским каталогом
                        val matchedName = catalog.firstOrNull { officialName ->
                            val coreWord = officialName.split(" ").firstOrNull { it.length > 3 } ?: officialName
                            cleanLine.contains(coreWord.lowercase()) || inputText.lowercase().contains(coreWord.lowercase())
                        } ?: cleanLine

                        val formattedLine = "${matchedName.padEnd(25)} | $count шт."
                        if (isExchangeSection) {
                            exchangeItems.add(formattedLine)
                            totalExchange += count
                        } else {
                            orderItems.add(formattedLine)
                            totalOrder += count
                        }
                    }
                }

                // Собираем красивую финальную таблицу для TextView
                val resultTable = StringBuilder().apply {
                    append("ЗАКАЗ ПО НАИМЕНОВАНИЯМ (ИИ МАКС)\n")
                    append("----------------------------------\n")
                    append("Наименование             | Кол-во\n")
                    append("----------------------------------\n")
                    append("### Новые позиции:\n")
                    if (orderItems.isEmpty()) append("Нет позиций\n") else orderItems.forEach { append("$it\n") }
                    append("\n### На обмен:\n")
                    if (exchangeItems.isEmpty()) append("Нет позиций\n") else exchangeItems.forEach { append("$it\n") }
                    append("----------------------------------\n")
                    append("ИТОГОВЫЙ ЗАКАЗ: $totalOrder шт.\n")
                    append("ИТОГОВЫЙ ОБМЕН: $totalExchange шт.\n")
                    append("----------------------------------\n")
                }.toString()

                return@withContext resultTable
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TEXT] Крах операции: ${e.localizedMessage}")
            return@withContext "Сетевая ошибка: ${e.localizedMessage}"
        }
    }
}
