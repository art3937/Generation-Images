package com.example.refactortext


import OrderViewModel
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    // Получаем ленивую ссылку на нашу ViewModel с привязкой к жизненному циклу Activity
    private val viewModel: OrderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Включаем отображение контента "под" системными барами (статус-бар и навигация)
        enableEdgeToEdge()

        // Устанавливаем Jetpack Compose контент вместо setContentView(R.layout.activity_main)
        setContent {
            MaterialTheme {
                // Scaffold автоматически обрабатывает системные отступы (Edge-to-Edge Padding)
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OrderScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // Низкоуровневый метод для записи ByteArray в переданный URI.
    // Мы вызываем его из Compose-слоя, передавая URI из системного диалога сохранения файлов.
    fun saveBytes(uri: android.net.Uri, bytes: ByteArray) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка записи", Toast.LENGTH_SHORT).show()
        }
    }
}
