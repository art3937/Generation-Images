package com.example.refactortext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.refactortext.ImageGenerator
import com.example.refactortext.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "BREAD_PARSER_LOG"

    // 1. ЛАУНЧЕР ДЛЯ СОХРАНЕНИЯ ТЕКСТОВОЙ ТАБЛИЦЫ (.txt)
    private val createTextFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val textToSave = binding.tvResult.text.toString()
                contentResolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(textToSave.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Таблица успешно сохранена!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Ошибка при записи файла", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. ЛАУНЧЕР ДЛЯ СОХРАНЕНИЯ КАРТИНКИ ИИ (.jpg) С ВЫБОРОМ ИМЕНИ
    private val createImageFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        if (uri != null) {
            try {
                val drawable = binding.ivGeneratedResult.drawable as? BitmapDrawable
                val bitmapToSave = drawable?.bitmap

                if (bitmapToSave != null) {
                    contentResolver.openOutputStream(uri).use { outputStream ->
                        if (outputStream != null) {
                            bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                            Toast.makeText(
                                this, "Изображение успешно сохранено!", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Toast.makeText(
                        this, "Не удалось извлечь изображение для сохранения", Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Ошибка при сохранении картинки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Кнопка: Распределить (Текст через YandexGPT Pro)
        binding.btnParse.setOnClickListener {
            val rawText = binding.etInputText.text.toString()
            if (rawText.isNotBlank()) {
                binding.btnParse.isEnabled = false
                binding.btnParse.text = "Считаю через ИИ..."

                lifecycleScope.launch {
                    try {
                        val tableResult = OrderParser.parseTextWithAI(rawText)
                        binding.tvResult.text = tableResult
                        binding.layoutResult.visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity, "Произошла ошибка сети!", Toast.LENGTH_SHORT
                        ).show()
                    } finally {
                        binding.btnParse.isEnabled = true
                        binding.btnParse.text = "Распределить"
                    }
                }
            } else {
                Toast.makeText(this, "Поле ввода не должно быть пустым!", Toast.LENGTH_SHORT).show()
            }
        }


        // Кнопка: Генерация изображения (Бесплатный запрос через Cloudflare FLUX)
        binding.btnGenerateImg.setOnClickListener {
            binding.btnParse.visibility = View.GONE
            val prompt = binding.etInputText.text.toString()
            if (prompt.isNotBlank()) {
                binding.btnGenerateImg.isEnabled = false
                binding.btnGenerateImg.text = "ИИ рисует (Бесплатно)..."
                Toast.makeText(this, "Запрос отправлен в Cloudflare FLUX", Toast.LENGTH_SHORT)
                    .show()

                lifecycleScope.launch {
                    val bitmapResult = ImageGenerator.generateImage(this@MainActivity, prompt)
                    if (bitmapResult != null) {
                        binding.ivGeneratedResult.setImageBitmap(bitmapResult)
                        binding.ivGeneratedResult.visibility = View.VISIBLE
                        binding.btnSaveImage.visibility = View.VISIBLE
                        Toast.makeText(
                            this@MainActivity, "ИИ отрисовал картинку!", Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Не удалось получить картинку. Проверьте логи.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    binding.btnGenerateImg.isEnabled = true
                    binding.btnGenerateImg.text = "Сгенерировать картинку ИИ"
                }
            } else {
                Toast.makeText(this, "Введите описание в текстовое поле!", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        // Кнопка: "Сохранить картинку" (С выбором имени в системе)
        binding.btnSaveImage.setOnClickListener {

            if (binding.ivGeneratedResult.visibility == View.VISIBLE) {
                createImageFileLauncher.launch("kartinka_ii.jpg")
            } else {
                Toast.makeText(this, "Сначала сгенерируйте изображение!", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка: Создание TXT файла таблицы в Download
        binding.btnSaveFile.setOnClickListener {
            val textToSave = binding.tvResult.text.toString()
            if (textToSave.isNotBlank()) {
                createTextFileLauncher.launch("zakaz_hleba.txt")
            } else {
                Toast.makeText(this, "Нечего сохранять! Таблица пуста.", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка: Копирование в буфер
        binding.btnCopy.setOnClickListener {
            val textToCopy = binding.tvResult.text.toString()
            if (textToCopy.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Bread Order", textToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Таблица скопирована в буфер!", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка: "Очистить всё"
        binding.btnClear.setOnClickListener {
            binding.btnParse.visibility = View.VISIBLE
            binding.etInputText.setText("")
            binding.tvResult.text = ""
            binding.layoutResult.visibility = View.GONE
            binding.ivGeneratedResult.visibility = View.GONE
            binding.btnSaveImage.visibility = View.GONE
            Toast.makeText(this, "Экран полностью очищен!", Toast.LENGTH_SHORT).show()
        }
    }
}
