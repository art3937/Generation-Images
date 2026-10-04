package com.example.refactortext

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import android.widget.Toast
import com.airbnb.lottie.compose.rememberLottieAnimatable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.launch

@Composable
fun OrderScreen(viewModel: OrderViewModel, modifier: Modifier = Modifier) {
    // Получаем текущий Context Android внутри Compose-функции
    val context = LocalContext.current

    // Запоминаем версию, чтобы не пересчитывать её при каждом рекомпозите
   // val appVersion = remember { viewModel.getAppVersion(context)}

        val coroutineScope = rememberCoroutineScope() // 👈 Добавляем эту строчку


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
        Text( text = "Введите несколько или одно любое слово чтоб сгенерировать текст, а если нужна картинка, нажмите сгенерировать картинку это займет немногим больше времени",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        // Поле ввода текста (Заменяет EditText)
        Surface( // 👈 Оборачиваем в Surface, чтобы придать форму и контрастный белый цвет
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, // 👈 Чисто белый фон плашки
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), // 👈 Едва заметная тонкая серая граница
            modifier = Modifier.fillMaxWidth()
        ) {

            TextField(
                value = viewModel.inputText,
                onValueChange = { viewModel.inputText = it },
                placeholder = {
                    Text(
                        text = " описание для ИИ...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    // Делаем контейнер самого поля прозрачным, так как белый цвет уже задан слоем Surface выше
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }




        // Горизонтальный блок с 3 главными кнопками управления
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Кнопка 1: РАСПРЕДЕЛИТЬ
            AnimatedButton(
                text = "Сгенерировать пост",
                loadingText = "Генерирую...",
                isLoading = viewModel.isParsing,
                onClick = { focusManager.clearFocus(); viewModel.parseText(showToast) },
                modifier = Modifier.weight(1.3f)
            )

            // Кнопка 2: КАРТИНКА ИИ
            AnimatedButton(
                text = "КАРТИНКА ИИ",
                loadingText = "Рисую...",
                isLoading = viewModel.isGeneratingImage,
                onClick = { focusManager.clearFocus(); viewModel.generateImage(showToast) },
                modifier = Modifier.weight(1.1f),
                isSecondary = true
            )

// 1. Создаем профессиональный и стабильный аниматор Lottie
            val lottieAnimatable = rememberLottieAnimatable()

// 2. Загружаем саму анимацию из папки raw
            val deleteComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.delete_anim))

// 3. Используем LaunchedEffect. Он следит за состоянием: как только запускается анимация,
// мы дожидаемся её полного завершения и только потом надежно очищаем экран.
            LaunchedEffect(lottieAnimatable.progress) {
                if (lottieAnimatable.progress == 1f) {
                    viewModel.clearAll(showToast) // Очищаем текстовые поля строго в конце анимации
                    lottieAnimatable.snapTo(composition = deleteComposition, progress = 0f) // Сбрасываем мусорку в начальный кадр
                }
            }

// 4. Крупная и отзывчивая мусорка-кнопка
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        lottieAnimatable.animate(
                            composition = deleteComposition,
                            iterations = 1,
                            continueFromPreviousAnimate = false
                        )
                    }
                },
                modifier = Modifier.size(100.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                LottieAnimation(
                    composition = deleteComposition,
                    progress = { lottieAnimatable.progress },
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(180.dp)
                )
            }

        }

//        Text(
//            text = "Версия : $appVersion",
//            fontSize = 14.sp,
//            color = Color.Gray
//        )

        // Блок вывода результатов от ИИ (Условный рендеринг: если текста нет — элемент вообще не создается в памяти)
        if (viewModel.isResultVisible) {
            ElevatedCard( // 👈 Вместо Card используем ElevatedCard
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), // 👈 Современное крупное скругление углов
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow // 👈 Профессиональный мягкий фон
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 5.dp) // 👈 Идеально просчитанная тень
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = viewModel.resultText, style = MaterialTheme.typography.bodyLarge)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Вместо обычных кнопок ставим ElevatedButton (они с легким объемом)
                        ElevatedButton(onClick = { viewModel.copyToClipboard(context, showToast) }, modifier = Modifier.weight(1f)) {
                            Text("Копировать")
                        }
                        ElevatedButton(onClick = { txtLauncher.launch("zakaz.txt") }, modifier = Modifier.weight(1f)) {
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

@Composable
fun AnimatedButton(
    text: String,                 // Текст на кнопке (например, "РАСПРЕДЕЛИТЬ")
    loadingText: String,          // Текст, который покажется при загрузке (например, "Считаю...")
    isLoading: Boolean,           // Флаг: идет ли сейчас загрузка? (true/false)
    onClick: () -> Unit,          // Блок кода, который сработает при клике по кнопке
    modifier: Modifier = Modifier, // Базовый модификатор (сюда из Row прилетает вес .weight)
    isSecondary: Boolean = false  // Если true — кнопка будет фиолетовой, если false — синей
) {
    // -------------------------------------------------------------------------
    // 1. БЛОК РАСЧЕТА АНИМАЦИЙ СЖАТИЯ И ЦВЕТА
    // -------------------------------------------------------------------------

    // Анимация масштаба: если загрузка идет, плавно уменьшаем кнопку до 0.95 (на 5% меньше нормы)
    val scale by animateFloatAsState(if (isLoading) 0.95f else 1f, animationSpec = tween(200), label = "scale")

    // Выбираем базовый цвет кнопки в зависимости от флага isSecondary
    val baseColor = if (isSecondary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    // Выбираем блеклый цвет кнопки для режима загрузки
    val loadingColor = if (isSecondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    // Анимируем плавный перелив цвета кнопки за 200 миллисекунд
    val buttonColor by animateColorAsState(if (isLoading) loadingColor else baseColor, animationSpec = tween(200), label = "color")

    // -------------------------------------------------------------------------
    // 2. САМ КОМПОНЕНТ КНОПКИ
    // -------------------------------------------------------------------------
    Button(
        onClick = onClick, // Назначаем действие на клик

        // В Модификаторе настраиваются размеры элемента:
        modifier = modifier
            .scale(scale), // Применяем эффект анимированного сжатия кнопки
        // 🛑 ЧТОБЫ СДЕЛАТЬ КНОПКУ КРУПНЕЕ ПО ВЫСОТЕ (Способ 1):
        // Вы можете жестко зафиксировать высоту кнопке. Напишите тут, например: .height(56.dp)

        enabled = !isLoading, // Блокируем клики во время загрузки ИИ

        // ТУТ НАСТРАИВАЕТСЯ СКРУГЛЕНИЕ УГЛОВ КНОПКИ:
        // Сейчас стоит 14.dp. Если хотите сделать кнопку более квадратной, уменьшите (например, 8.dp).
        // Если хотите сделать её идеально круглой капсулой — увеличьте (например, 24.dp).
        shape = RoundedCornerShape(14.dp),

        colors = ButtonDefaults.buttonColors(containerColor = buttonColor), // Подставляем анимированный цвет

        // Настройка теней (в покое тень приподнимает кнопку на 4dp, при зажатии пальцем — тень падает до 1dp)
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),

        // 🛑 ЧТОБЫ СДЕЛАТЬ КНОПКУ КРУПНЕЕ И СОЛИДНЕЕ (Способ 2 — Профессиональный):
        // Вместо жесткой высоты лучше добавить внутренние отступы (паддинги). Они раздвинут кнопку изнутри.
        // Чтобы применить, раскомментируйте строчку ниже (уберите знаки //) и настройте цифры:
         contentPadding = PaddingValues(vertical = 50.dp, horizontal = 10.dp), // vertical отвечает за высоту!
    ) {
        // -------------------------------------------------------------------------
        // 3. ВНУТРЕННОЕ СОДЕРЖИМОЕ КНОПКИ (Что нарисовано внутри плашки)
        // -------------------------------------------------------------------------
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp), // Отступ между крутилкой загрузки и текстом
            verticalAlignment = Alignment.CenterVertically // Выравниваем текст и крутилку строго по центру внутри кнопки
        ) {
            if (isLoading) {
                // Если ИИ сейчас считает или рисует — показываем колесо загрузки
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), // ТУТ НАСТРАИВАЕТСЯ РАЗМЕР крутилки загрузки
                    color = baseColor,               // Цвет крутилки совпадает с главным цветом кнопки
                    strokeWidth = 2.dp               // Толщина линии крутилки
                )
                Text(
                    text = loadingText,
                    fontWeight = FontWeight.Bold, // ТУТ НАСТРАИВАЕТСЯ ЖИРНОСТЬ ШРИФТА (Bold — жирный)
                    fontSize = 13.sp              // 🛑 ТУТ НАСТРАИВАЕТСЯ РАЗМЕР ТЕКСТА КНОПКИ ПРИ ЗАГРУЗКЕ
                )
            } else {
                // Если кнопка свободна — рисуем обычный текст
                Text(
                    text = text,
                    fontWeight = FontWeight.Bold, // Шрифт текста тоже жирный
                    fontSize = 13.sp              // 🛑 ТУТ НАСТРАИВАЕТСЯ РАЗМЕР ТЕКСТА В ОБЫЧНОМ СОСТОЯНИИ
                )
            }
        }
    }
}






