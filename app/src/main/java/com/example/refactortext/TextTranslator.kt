package com.example.refactortext

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object TextTranslator {

    private val conditions = DownloadConditions.Builder()
        .requireWifi()
        .build()

    /**
     * Переводит текст с русского на английский.
     */
    suspend fun translateRuToEn(text: String): String {
        return translate(text, TranslateLanguage.RUSSIAN, TranslateLanguage.ENGLISH)
    }

    /**
     * Переводит текст с английского на русский.
     */
    suspend fun translateEnToRu(text: String): String {
        return translate(text, TranslateLanguage.ENGLISH, TranslateLanguage.RUSSIAN)
    }

    /**
     * Универсальный метод перевода на стандартных колбэках Google Play Services
     */
    private suspend fun translate(text: String, fromLang: String, toLang: String): String =
        suspendCancellableCoroutine { continuation ->
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(fromLang)
                .setTargetLanguage(toLang)
                .build()

            val translator = Translation.getClient(options)

            // Если корутина отменяется извне, принудительно закрываем переводчик
            continuation.invokeOnCancellation {
                translator.close()
            }

            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { translatedText ->
                            if (continuation.isActive) {
                                continuation.resume(translatedText)
                                translator.close() // Освобождаем память после успеха
                            }
                        }
                        .addOnFailureListener { exception ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(exception)
                                translator.close() // Освобождаем память при ошибке перевода
                            }
                        }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                        translator.close() // Освобождаем память при ошибке скачивания
                    }
                }
        }
}
