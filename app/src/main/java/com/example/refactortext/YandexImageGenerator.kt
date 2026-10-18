package com.example.refactortext

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.refactortext.BuildConfig.YANDEX_API_KEY
import com.example.refactortext.BuildConfig.YANDEX_FOLDER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object YandexImageGenerator {

    private const val TAG = "YANDEX_IMAGE_LOG"

    private const val API_KEY = YANDEX_API_KEY
    private const val FOLDER_ID = YANDEX_FOLDER_ID

    // НОВЫЙ ЕДИНЫЙ СИНХРОННЫЙ URL ДЛЯ ГЕНЕРАЦИИ КАРТИНОК
    private const val GENERATE_URL = "https://ai.api.cloud.yandex.net/v1/images/generations"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // увеличили таймаут, так как сервер генерирует сразу
        .build()

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }

    suspend fun generateImage(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[IMAGE] Старт синхронной генерации. Промпт: \"$prompt\"")

            // Новый формат тела запроса по стандарту OpenAI
            // Вместо старого "art://$FOLDER_ID/yandex-art/latest" пишем:
            val jsonBody = JSONObject().apply {
                put("model", "art://$FOLDER_ID/aliceai-image-art-3.0") // Имя новой модели
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
                put("response_format", "b64_json")
            }


            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(GENERATE_URL)
                .post(requestBody)
                .addHeader("Authorization", "Api-Key $API_KEY")
                .build()

            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "[IMAGE] Ошибка сервера: ${response.code} | ${response.body?.string()}")
                    return@withContext null
                }

                val bodyString = response.body?.string() ?: return@withContext null
                val jsonResponse = JSONObject(bodyString)

                // Извлекаем картинку напрямую из массива data
                val dataArray = jsonResponse.optJSONArray("data")
                if (dataArray == null || dataArray.length() == 0) {
                    Log.e(TAG, "[IMAGE] Отсутствуют данные в ответе (data)")
                    return@withContext null
                }

                val base64Image = dataArray.getJSONObject(0).optString("b64_json", "")
                if (base64Image.isBlank()) {
                    Log.e(TAG, "[IMAGE] Поле b64_json пустое")
                    return@withContext null
                }

                Log.d(TAG, "[IMAGE] Картинка успешно получена напрямую! Декодируем...")
                val bytes = Base64.decode(base64Image, Base64.DEFAULT)
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[IMAGE] Исключение: ${e.message}", e)
            return@withContext null
        }
    }
}
