package com.example.refactortext

import OrderViewModel
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.refactortext.MainActivity

@Composable
fun OrderScreen(viewModel: OrderViewModel, modifier: Modifier = Modifier) {
    // Получаем текущий Context Android внутри Compose-функции
    val context = LocalContext.current

    // FocusManager отвечает за фокус элементов ввода. С его помощью мы будем скрывать клавиатуру.
    val focusManager = LocalFocusManager.current

    // Быстрая лямбда-функция для отображения стандартных Toast-сообщений
    val showToast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    // === ЛАУНЧЕРЫ ДЛЯ ДИАЛОГОВ СОХРАНЕНИЯ ФАЙЛОВ ===
    // 1. Для сохранения TXT-файла отчетности
    val txtLauncher = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        uri?.let { (context as MainActivity).saveBytes(it, viewModel.resultText.toByteArray()) }
    }

    // 2. Для сохранения JPEG-картинки, созданной ИИ
    val imgLauncher = rememberLauncherForActivityResult(CreateDocument("image/jpeg")) { uri ->
        uri?.let { u ->
            viewModel.generatedBitmap?.let { b ->
                context.contentResolver.openOutputStream(u)?.use {
                    b.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                Toast.makeText(context, "Сохранено!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. Для сохранения сгенерированной таблицы Excel
    val xlsLauncher = rememberLauncherForActivityResult(CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        uri?.let { viewModel.saveExcel(context, it, showToast) }
    }

    // Главный контейнер экрана с поддержкой вертикального скролла (если элементов много, они будут прокручиваться)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp) // Автоматический отступ между элементами в 12dp
    ) {

        // Поле ввода текста (Заменяет EditText)
        OutlinedTextField(
            value = viewModel.inputText,
            onValueChange = { viewModel.inputText = it }, // При вводе символа обновляем текст во ViewModel
            label = { Text("Введите текст заказа или описание для ИИ") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6
        )

        // Горизонтальный блок с 3 главными кнопками управления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Кнопка Парсинга
            Button(
                onClick = {
                    focusManager.clearFocus() // Убираем фокус — клавиатура прячется АВТОМАТИЧЕСКИ
                    viewModel.parseText(showToast)
                },
                modifier = Modifier.weight(1f), // Занимает равную долю пространства в строке
                enabled = !viewModel.isParsing  // Заморозить кнопку во время отправки запроса
            ) {
                Text(if (viewModel.isParsing) "Считаю..." else "РАСПРЕДЕЛИТЬ")
            }

            // Кнопка генерации изображения нейросетью
            Button(
                onClick = {
                    focusManager.clearFocus() // Скрываем клавиатуру
                    viewModel.generateImage(showToast)
                },
                modifier = Modifier.weight(1f),
                enabled = !viewModel.isGeneratingImage
            ) {
                Text(if (viewModel.isGeneratingImage) "Рисую..." else "КАРТИНКА ИИ")
            }

            // Кнопка сброса
            Button(
                onClick = { viewModel.clearAll(showToast) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) // Красный цвет ошибки
            ) {
                Text("ОЧИСТИТЬ")
            }
        }

        // Блок вывода результатов от ИИ (Условный рендеринг: если текста нет — элемент вообще не создается в памяти)
        if (viewModel.isResultVisible) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Текст ответа
                    Text(text = viewModel.resultText, style = MaterialTheme.typography.bodyMedium)

                    // Кнопки взаимодействия с текстом результата
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.copyToClipboard(context, showToast) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Копировать")
                        }
                        Button(
                            onClick = { txtLauncher.launch("zakaz.txt") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Сохранить TXT")
                        }
                    }
                }
            }
        }

        // Кнопка экспорта в Excel таблицу (появляется только при наличии нужных позиций)
        if (viewModel.isExcelVisible) {
            Button(
                onClick = { xlsLauncher.launch("zakaz_${System.currentTimeMillis()}.xlsx") },
                enabled = !viewModel.isSavingExcel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(viewModel.excelBtnText)
            }
        }

        // Кнопка открытия уже успешно сохраненного Excel файла внешним приложением
        viewModel.lastSavedExcelUri?.let { uri ->
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Даем права системе читать этот файл
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        showToast("Установите Excel для просмотра!")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("ОТКРЫТЬ СОХРАНЕННЫЙ EXCEL")
            }
        }

        // Карточка отображения и сохранения сгенерированного изображения
        viewModel.generatedBitmap?.let { bitmap ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Конвертируем стандартный Android Bitmap в пригодный для Compose ImageBitmap
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Сгенерированная картинка от ИИ",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )

                    Button(
                        onClick = { imgLauncher.launch("ii_img.jpg") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("СОХРАНИТЬ В JPEG")
                    }
                }
            }
        }
    }
}




