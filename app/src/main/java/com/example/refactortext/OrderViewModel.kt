package com.example.refactortext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OrderViewModel : ViewModel() {

    // === СОСТОЯНИЯ ЭКРАНА (ДАННЫЕ) ===
    // Текст, который пользователь вводит в поле ввода
    var inputText by mutableStateOf("")

    // Текст ответа от ИИ, который мы выводим в карточке результатов
    var resultText by mutableStateOf("")

    // Битмап сгенерированной картинки (null, если картинки ещё нет)
    var generatedBitmap by mutableStateOf<Bitmap?>(null)

    // Ссылка на сохранённый файл Excel для кнопки "Открыть Excel"
    var lastSavedExcelUri by mutableStateOf<Uri?>(null)

    // === СОСТОЯНИЯ ЗАГРУЗКИ (ИНДИКАТОРЫ) ===
    var isParsing by mutableStateOf(false)          // Идет ли парсинг текста
    var isGeneratingImage by mutableStateOf(false)  // Рисует ли ИИ картинку
    var isSavingExcel by mutableStateOf(false)      // Сохраняется ли в данный момент Excel
    var excelBtnText by mutableStateOf("СОХРАНИТЬ В EXCEL")

    // Скрытое поле для хранения распарсенного объекта заказа (нужно для экспорта в Excel)
    private var lastOrder: OrderParser.ParsedOrder? = null

    // === ВЫЧИСЛЯЕМЫЕ СВОЙСТВА ===
    // Показывать ли блок с результатами? Показываем, только если там есть текст
    val isResultVisible: Boolean get() = resultText.isNotBlank()

    // Показывать ли кнопку Excel? Только если в заказе есть новые позиции или позиции на обмен
    val isExcelVisible: Boolean get() = lastOrder?.let {
        it.newPositions.isNotEmpty() || it.exchangePositions.isNotEmpty()
    } ?: false

    // Блок init выполняется автоматически при создании ViewModel
    init {
        // Запускаем предзагрузку прокси в контексте viewModelScope на Dispatchers.IO (фоновый поток)
        // В отличие от lifecycleScope, viewModelScope не прервется при повороте экрана!
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("BREAD_PARSER_LOG", "[PROXY] Инициализация прокси-базы...")
                ProxyManager.fetchFreshProxies()
            } catch (e: Exception) {
                Log.e("BREAD_PARSER_LOG", "[PROXY] Сбой предзагрузки баз: ${e.message}")
            }
        }
    }

    // Метод парсинга текста через ИИ
    fun parseText(onToast: (String) -> Unit) {
        if (inputText.isBlank()) return onToast("Поле ввода пусто!")

        isParsing = true // Включаем режим загрузки (кнопка заблокируется и сменит текст)
        viewModelScope.launch {
            try {
                val res = OrderParser.parseTextWithAI(inputText)
                resultText = res.text
                lastOrder = res.order
            } catch (e: Exception) {
                onToast("Ошибка сети!")
            } finally {
                isParsing = false // Выключаем режим загрузки в любом случае
            }
        }
    }

    // Метод генерации изображения
    fun generateImage(onToast: (String) -> Unit) {
        if (inputText.isBlank()) return onToast("Введите описание!")

        isGeneratingImage = true
        viewModelScope.launch {
            try {
                val b = ImageGenerator.generateImage(inputText)
                if (b != null) {
                    generatedBitmap = b
                } else {
                    onToast("Ошибка картинки")
                }
            } catch (e: Exception) {
                onToast("Ошибка ИИ")
            } finally {
                isGeneratingImage = false
            }
        }
    }

    // Фоновое сохранение таблицы Excel
    fun saveExcel(context: Context, uri: Uri, onToast: (String) -> Unit) {
        val order = lastOrder ?: return
        excelBtnText = "Сохраняю..."
        isSavingExcel = true

        viewModelScope.launch {
            val ok = OrderExcelExporter.saveToExcel(context, uri, order)
            if (ok) {
                lastSavedExcelUri = uri
                onToast("Excel успешно сохранён!")
            } else {
                onToast("Ошибка Excel")
            }
            excelBtnText = "СОХРАНИТЬ В EXCEL"
            isSavingExcel = false
        }
    }

    // Копирование текстового результата в буфер обмена Android
    fun copyToClipboard(context: Context, onToast: (String) -> Unit) {
        if (resultText.isBlank()) return onToast("Нечего копировать!")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Order", resultText))
        onToast("Скопировано!")
    }

    // Сброс всех состояний экрана в исходное (Кнопка "Очистить")
    fun clearAll(onToast: (String) -> Unit) {
        inputText = ""
        resultText = ""
        generatedBitmap = null
        lastSavedExcelUri = null
        lastOrder = null
        onToast("Очищено!")
    }

//    fun getAppVersion(context: Context): String {
//        return try {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                context.packageManager.getPackageInfo(
//                    context.packageName,
//                    PackageManager.PackageInfoFlags.of(0)
//                ).versionName ?: ""
//            } else {
//                @Suppress("DEPRECATION")
//                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
//            }
//        } catch (e: Exception) {
//            "1.0"
//        }
//    }
}
