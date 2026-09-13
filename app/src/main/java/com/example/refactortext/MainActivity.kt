package com.example.refactortext

import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.refactortext.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var lastOrder: OrderParser.ParsedOrder? = null
    private var lastSavedExcelUri: android.net.Uri? = null

    private val txtLauncher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
        uri?.let { saveBytes(it, binding.tvResult.text.toString().toByteArray()) }
    }
    private val imgLauncher = registerForActivityResult(CreateDocument("image/jpeg")) { uri ->
        val bmp = (binding.ivGeneratedResult.drawable as? BitmapDrawable)?.bitmap
        uri?.let { u -> bmp?.let { b -> contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } } }
    }
    private val xlsLauncher = registerForActivityResult(CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        uri?.let { u -> lastOrder?.let { o ->
            toggleExcelBtn(false, "Сохраняю...")
            lifecycleScope.launch {
                val ok = OrderExcelExporter.saveToExcel(this@MainActivity, u, o)
                if (ok) {
                    lastSavedExcelUri = u
                    binding.btnOpenExcel.visibility = View.VISIBLE
                    toast("Excel успешно сохранён!")
                } else { toast("Ошибка Excel") }
                toggleExcelBtn(true, "СОХРАНИТЬ В EXCEL")
            }
        }}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater).also { setContentView(it.root) }

        binding.btnParse.setOnClickListener {
            val txt = binding.etInputText.text.toString()
            if (txt.isBlank()) return@setOnClickListener toast("Поле ввода пусто!")
            binding.btnParse.isEnabled = false; binding.btnParse.text = "Считаю..."
            lifecycleScope.launch {
                try {
                    val res = OrderParser.parseTextWithAI(txt)
                    binding.tvResult.text = res.text
                    binding.layoutResult.visibility = View.VISIBLE
                    lastOrder = res.order
                    if (res.order.newPositions.isNotEmpty() || res.order.exchangePositions.isNotEmpty()) binding.btnSaveExcel.visibility = View.VISIBLE
                } catch (e: Exception) { toast("Ошибка сети!") }
                finally {
                    binding.btnParse.isEnabled = true; binding.btnParse.text = "РАСПРЕДЕЛИТЬ"
                    hideKeyboard() // ФОКУС: Прячем клаву сразу после вывода таблицы
                }
            }
        }

        binding.btnSaveExcel.setOnClickListener { xlsLauncher.launch("zakaz_${System.currentTimeMillis()}.xlsx") }
        binding.btnSaveFile.setOnClickListener { if(binding.tvResult.text.isNotBlank()) txtLauncher.launch("zakaz.txt") else toast("Пусто!") }
        binding.btnSaveImage.setOnClickListener { if(binding.ivGeneratedResult.visibility == View.VISIBLE) imgLauncher.launch("ii_img.jpg") else toast("Нет фото!") }

        binding.btnOpenExcel.setOnClickListener {
            lastSavedExcelUri?.let { u ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(u, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) { toast("Установите Excel для просмотра!") }
            } ?: toast("Файл ещё не сохранён!")
        }

        binding.btnCopy.setOnClickListener {
            val t = binding.tvResult.text.toString()
            if (t.isBlank()) return@setOnClickListener toast("Нечего копировать!")
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Order", t))
            toast("Скопировано!")
        }

        binding.btnClear.setOnClickListener {
            binding.etInputText.setText(""); binding.tvResult.text = ""; lastOrder = null; lastSavedExcelUri = null
            listOf(binding.layoutResult, binding.ivGeneratedResult, binding.btnSaveImage, binding.btnSaveExcel, binding.btnOpenExcel).forEach { it.visibility = View.GONE }
            toast("Очищено!")
        }

        binding.btnGenerateImg.setOnClickListener {
            val pr = binding.etInputText.text.toString()
            if (pr.isBlank()) return@setOnClickListener toast("Введите описание!")
            binding.btnGenerateImg.isEnabled = false; binding.btnGenerateImg.text = "Рисую..."
            lifecycleScope.launch {
                try {
                    val b = ImageGenerator.generateImage(this@MainActivity, pr)
                    if (b != null) { binding.ivGeneratedResult.setImageBitmap(b); binding.ivGeneratedResult.visibility = View.VISIBLE; binding.btnSaveImage.visibility = View.VISIBLE }
                    else { toast("Ошибка картинки") }
                } catch(e: Exception) { toast("Ошибка ИИ") }
                finally {
                    binding.btnGenerateImg.isEnabled = true; binding.btnGenerateImg.text = "Сгенерировать картинку"
                    hideKeyboard() // ФОКУС: Прячем клаву сразу после того, как ИИ дорисовал
                }
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun saveBytes(u: android.net.Uri, b: ByteArray) = try { contentResolver.openOutputStream(u)?.use { it.write(b) }; toast("Сохранено!") } catch(e: Exception) { toast("Ошибка записи") }
    private fun toggleExcelBtn(en: Boolean, t: String) { binding.btnSaveExcel.isEnabled = en; binding.btnSaveExcel.text = t }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
