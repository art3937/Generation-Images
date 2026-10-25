package com.example.refactortext

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import android.widget.Toast
import com.airbnb.lottie.compose.rememberLottieAnimatable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
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
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun OrderScreen(viewModel: OrderViewModel, modifier: Modifier = Modifier) {
    // Получаем текущий Context Android внутри Compose-функции
    val context = LocalContext.current

    // Запоминаем версию, чтобы не пересчитывать её при каждом рекомпозите
    // val appVersion = remember(context.packageName) { viewModel.getAppVersion(context) }

    val coroutineScope = rememberCoroutineScope()


    // FocusManager отвечает за фокус элементов ввода. С его помощью мы будем скрывать клавиатуру.
    val focusManager = LocalFocusManager.current

    // Быстрая лямбда-функция для отображения стандартных Toast-сообщений
    val showToast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    // === ЛАУНЧЕРЫ ДЛЯ ДИАЛОГОВ СОХРАНЕНИЯ ФАЙЛОВ ===
    // 1. Для сохранения TXT-файла отчетности
    val txtLauncher = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        uri?.let { (context as MainActivity).saveBytes(it, viewModel.resultText.toByteArray()) }
    }

    val txtLauncherYandex = rememberLauncherForActivityResult(CreateDocument("text/plain")) { uri ->
        uri?.let {
            (context as MainActivity).saveBytes(
                it,
                viewModel.resultTextYandex.toByteArray()
            )
        }
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

    // 2. Для сохранения JPEG-картинки, созданной ИИ от яндекса
    val imgLauncherYandex = rememberLauncherForActivityResult(CreateDocument("image/jpeg")) { uri ->
        uri?.let { u ->
            viewModel.generatedBitmapYandex?.let { b ->
                context.contentResolver.openOutputStream(u)?.use {
                    b.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                Toast.makeText(context, "Сохранено!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. Для сохранения сгенерированной таблицы Excel
    val xlsLauncher =
        rememberLauncherForActivityResult(CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            uri?.let { viewModel.saveExcel(context, it, showToast) }
        }


    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { sourceUri ->
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, sourceUri)
                }
                // Вызываем метод, который мы только что добавили в ViewModel
               viewModel.refactorImageYandex(bitmap, showToast)
            } catch (e: Exception) {
                showToast("Не удалось загрузить изображение")
            }
        }
    }
    // Главный контейнер экрана с поддержкой вертикального скролла (если элементов много, они будут прокручиваться)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp) // Автоматический отступ между элементами в 12dp
    ) {
        Text(
            text = "Введите несколько или одно любое слово чтоб сгенерировать текст, а если нужна картинка, нажмите сгенерировать картинку это займет немногим больше времени",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        // Поле ввода текста (Заменяет EditText)
        Surface( // 👈 Оборачиваем в Surface, чтобы придать форму и контрастный белый цвет
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest, // 👈 Чисто белый фон плашки
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ), // 👈 Едва заметная тонкая серая граница
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
                modifier = Modifier.weight(1.1f)
                    .height(IntrinsicSize.Min) // Кнопка зафиксирует минимально необходимую высоту для текста в 1 строку

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
                    lottieAnimatable.snapTo(
                        composition = deleteComposition,
                        progress = 0f
                    ) // Сбрасываем мусорку в начальный кадр
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Кнопка 1: РАСПРЕДЕЛИТЬ
            AnimatedButton(
                text = "Сгенерировать пост через яндекс",
                loadingText = "Генерирую...",
                isLoading = viewModel.isParsingYandex,
                onClick = { focusManager.clearFocus(); viewModel.parseTextYandex(showToast) },
                modifier = Modifier.weight(1.1f)
                    .height(IntrinsicSize.Min) // Кнопка зафиксирует минимально необходимую высоту для текста в 1 строку

            )

            // Кнопка 2: КАРТИНКА ИИ
            AnimatedButton(
                text = "КАРТИНКА ИИ От Яндекса",
                loadingText = "Рисую...",
                isLoading = viewModel.isGeneratingImageYandex,
                onClick = { focusManager.clearFocus(); viewModel.generateImageYandex(showToast) },
                modifier = Modifier.weight(1.1f),
                isSecondary = true
            )

            AnimatedButton(
                text = "Картинка ИИ от Сбер",
                loadingText = "Рисую...",
                isLoading = viewModel.isGeneratingImageSber, // Берет состояние из ViewModel
                onClick = {
//                    focusManager.clearFocus()
//                    if (viewModel.inputText.isBlank()) {
//                        showToast("Сначала введите описание изменений в поле выше!")
//                    } else {
//                        // Запускаем галерею для выбора фото
//                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
//                    }
                    viewModel.generateImageSber(showToast)
                },
                modifier = Modifier.weight(1f),
                isSecondary = true
            )


            // ВЫЗЫВАЕМ ТУТ: Новая кнопка для генерации 3D-модели
            AnimatedButton(
                text = "3D МОДЕЛЬ ИИ",
                loadingText = "Строю 3D...",
                isLoading = viewModel.isGenerating3D, // Флаг загрузки из твоей ViewModel
                onClick = {
                    focusManager.clearFocus()
                    if (viewModel.inputText.isBlank()) {
                        showToast("Сначала введите описание для 3D модели!")
                    } else {
                        // ВОТ ЗДЕСЬ ВЫЗЫВАЕТСЯ МЕТОД ИЗ VIEWMODEL
                        viewModel.generateModel(context,viewModel.inputText)
                    }
                },
                modifier = Modifier.weight(1.1f),
                isSecondary = true
            )
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = viewModel.resultText, style = MaterialTheme.typography.bodyLarge)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Вместо обычных кнопок ставим ElevatedButton (они с легким объемом)
                        ElevatedButton(
                            onClick = { viewModel.copyToClipboard(context, showToast) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Копировать")
                        }
                        ElevatedButton(
                            onClick = { txtLauncher.launch("zakaz.txt") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Сохранить TXT")
                        }
                    }
                }
            }
        }

        if (viewModel.isResultVisibleYandex) {
            ElevatedCard( // 👈 Вместо Card используем ElevatedCard
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp), // 👈 Современное крупное скругление углов
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow // 👈 Профессиональный мягкий фон
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 5.dp) // 👈 Идеально просчитанная тень
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = viewModel.resultTextYandex,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Вместо обычных кнопок ставим ElevatedButton (они с легким объемом)
                        ElevatedButton(onClick = {
                            viewModel.copyToClipboardYandex(
                                context,
                                showToast
                            )
                        }, modifier = Modifier.weight(1f)) {
                            Text("Копировать")
                        }
                        ElevatedButton(
                            onClick = { txtLauncherYandex.launch("postYandex.txt") },
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
                onClick = { xlsLauncher.launch("post_${System.currentTimeMillis()}.xlsx") },
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
                            setDataAndType(
                                uri,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            )
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


        viewModel.generatedBitmapYandex?.let { bitmap ->
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
                        contentDescription = "Сгенерированная картинка от Яндекса",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )

                    Button(
                        onClick = { imgLauncherYandex.launch("ii_img.jpg") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("СОХРАНИТЬ В JPEG")
                    }
                }
            }
        }

        viewModel.generatedBitmapSber?.let { bitmap ->
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
                        contentDescription = "Сгенерированная картинка от Сбер",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )

                    Button(
                        onClick = { imgLauncherYandex.launch("ii_img.jpg") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("СОХРАНИТЬ В JPEG")
                    }
                }
            }
        }

        // Карточка отображения и открытия сгенерированной 3D-модели
        viewModel.generated3DFilePath?.let { file ->
            // УБРАЛИ if (file.exists()), чтобы Compose не спотыкался о проверку файловой системы в UI-потоке
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Контейнер-заглушка вместо картинки
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📦", fontSize = 64.sp) // Крупная иконка 3D-объекта
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "3D-модель готова: ${file.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Главная кнопка: Открывает файл во внешнем плеере устройства
                    Button(
                        onClick = {
                            try {
                                val authority = "${context.packageName}.fileprovider"
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    authority,
                                    file
                                )

                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "model/gltf-binary")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                showToast("Установите приложение для просмотра 3D (.glb)")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("ОТКРЫТЬ 3D МОДЕЛЬ")
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
        // Динамический размер шрифта. Начинается с ваших 13.sp
        val currentText = if (isLoading) loadingText else text
        val fontSize = remember(currentText) { mutableStateOf(13.sp) }

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
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize.value,        // 1. Подставляем динамический размер вместо жесткого 13.sp
                    maxLines = 1,                     // 2. Строго одна строка
                    softWrap = false,                 // 3. Запрещаем перенос слов на новую строку
                    onTextLayout = { textLayoutResult ->
                        if (textLayoutResult.hasVisualOverflow) {
                            // Проверяем чистое число (значение float)
                            if (fontSize.value.value > 9f) {
                                // Правильное уменьшение: берем текущее число, отнимаем 0.5 и упаковываем обратно в .sp
                                fontSize.value = (fontSize.value.value - 0.5f).sp
                            }
                        }
                    }

                )
            } else {
                // Если кнопка свободна — рисуем обычный текст
                Text(
                    text = text,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize.value,        // 1. Подставляем динамический размер вместо жесткого 13.sp
                    maxLines = 1,                     // 2. Строго одна строка
                    softWrap = false,                 // 3. Запрещаем перенос слов на новую строку
                    onTextLayout = { textLayoutResult ->
                        if (textLayoutResult.hasVisualOverflow) {
                            // Проверяем чистое число (значение float)
                            if (fontSize.value.value > 9f) {
                                // Правильное уменьшение: берем текущее число, отнимаем 0.5 и упаковываем обратно в .sp
                                fontSize.value = (fontSize.value.value - 0.5f).sp
                            }
                        }
                    }

                )

            }
        }
    }

}






