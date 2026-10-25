//package com.example.refactortext
//
//import android.util.Log
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import org.json.JSONArray
//import org.json.JSONObject
//import java.io.File
//import okhttp3.MediaType.Companion.toMediaType
//import okhttp3.Request
//import okhttp3.RequestBody.Companion.toRequestBody
//
//class Test {
//    suspend fun generate3DModelFromPrompt(promptText: String): File? = withContext(Dispatchers.IO) {
//        try {
//            val token = fetchOAuthToken()
//
//            // 1. Получаем список моделей
//            val modelsUrl = "https://api.giga.chat/v1/models"
//            val modelsReq = Request.Builder()
//                .url(modelsUrl)
//                .get()
//                .addHeader("Authorization", "Bearer $token")
//                .build()
//
//            client.newCall(modelsReq).execute().use { modelsResp ->
//                if (!modelsResp.isSuccessful) {
//                    Log.e("GigaChat", "Ошибка получения моделей: ${modelsResp.code}")
//                    return@withContext null
//                }
//                val modelsJson = JSONObject(modelsResp.body?.string() ?: "{}")
//                val data = modelsJson.getJSONArray("data")
//                if (data.length() == 0) {
//                    Log.e("GigaChat", "Нет доступных моделей")
//                    return@withContext null
//                }
//                val modelId = data.getJSONObject(4).getString("id")
//                Log.d("GigaChat", "Используем модель для 3D: $modelId")
//
//                // 2. Формируем запрос с функцией text2model3d
//                val jsonParams = JSONObject().apply {
//                    put("model", modelId)
//                    put("function_call", "auto")
//                    val functions = JSONArray().apply {
//                        put(JSONObject().apply { put("name", "text2model3d") })
//                    }
//                    put("functions", functions)
//                    val messages = JSONArray().apply {
//                        put(JSONObject().apply {
//                            put("role", "system")
//                            put("content", "Ты профессиональный 3D-дизайнер. Ты умеешь генерировать полноценные 3D-модели с помощью функции text2model3d. Всегда используй её, если пользователь просит создать 3D-объект.")
//                        })
//                        put(JSONObject().apply {
//                            put("role", "user")
//                            put("content", promptText)
//                        })
//                    }
//                    put("messages", messages)
//                }
//
//                val genUrl = "https://api.giga.chat/v1/chat/completions"
//                val genReq = Request.Builder()
//                    .url(genUrl)
//                    .post(jsonParams.toString().toRequestBody("application/json".toMediaType()))
//                    .addHeader("Authorization", "Bearer $token")
//                    .addHeader("Accept", "application/json")
//                    .build()
//
//                client.newCall(genReq).execute().use { genResp ->
//                    val responseStr = genResp.body?.string() ?: ""
//                    Log.d("GigaChat", "Ответ 3D модели (raw): $responseStr")
//                    if (!genResp.isSuccessful) {
//                        Log.e("GigaChat", "Ошибка генерации: Код ${genResp.code}")
//                        return@withContext null
//                    }
//                    val choices = JSONObject(responseStr).getJSONArray("choices")
//                    val message = choices.getJSONObject(0).getJSONObject("message")
//                    val content = message.getString("content")
//
//                    // Регулярка вытаскивает UUID из ответа
//                    val model3dRegex = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
//                    val fileId = model3dRegex.find(content)?.groupValues?.getOrNull(1)
//                    if (fileId.isNullOrBlank()) {
//                        Log.w("GigaChat", "В ответе нет ID 3D-модели. Полный ответ: $content")
//                        return@withContext null
//                    }
//                    Log.d("GigaChat", "Найден ID файла 3D: $fileId")
//
//                    // 3. Скачиваем бинарный файл 3D-модели
//                    val dlUrl = "https://api.giga.chat/v1/files/$fileId/content"
//                    val dlReq = Request.Builder()
//                        .url(dlUrl)
//                        .get()
//                        .addHeader("Authorization", "Bearer $token")
//                        .build()
//
//                    client.newCall(dlReq).execute().use { dlResp ->
//                        if (!dlResp.isSuccessful) {
//                            Log.e("GigaChat", "Ошибка скачивания файла: ${dlResp.code}")
//                            return@withContext null
//                        }
//                        val inputStream = dlResp.body?.byteStream() ?: return@withContext null
//
//                        // Прямой путь к папке кэша твоего приложения без использования Context
//                        val cacheFolder = File("/data/data/com.example.refactortext/cache")
//                        if (!cacheFolder.exists()) cacheFolder.mkdirs()
//
//                        val modelFile = File(cacheFolder, "generated_model_$fileId.glb")
//
//                        // Перекачиваем байты из сети в локальный файл
//                        java.io.FileOutputStream(modelFile).use { outputStream ->
//                            inputStream.copyTo(outputStream)
//                        }
//                        Log.d("GigaChat", "3D-модель успешно сохранена в: ${modelFile.absolutePath}")
//                        return@withContext modelFile
//                    }
//                }
//            }
//        } catch (ex: Exception) {
//            Log.e("GigaChat", "Ошибка в 3D генераторе: ${ex.message}", ex)
//        }
//        return@withContext null
//    }
//}
