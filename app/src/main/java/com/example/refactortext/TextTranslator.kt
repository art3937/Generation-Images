package com.example.audiobible.generatorAll

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object TextTranslator {

    // Настройка: с Русского на Английский
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.RUSSIAN)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    private val translator = Translation.getClient(options)

    /**
     * Переводит текст с русского на английский.
     * Автоматически дожидается скачивания языкового пакета, если его нет.
     */
    suspend fun translateRuToEn(text: String): String = suspendCancellableCoroutine { continuation ->
        val conditions = DownloadConditions.Builder()
            .requireWifi() // Можно убрать, если хотите разрешить скачивание по мобильной сети
            .build()

        // Проверяем/скачиваем модель, затем переводим
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        if (continuation.isActive) continuation.resume(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
            }
            .addOnFailureListener { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
    }
}
