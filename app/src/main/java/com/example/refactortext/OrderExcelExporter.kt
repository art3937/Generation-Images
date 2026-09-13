package com.example.refactortext

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook

object OrderExcelExporter {
    private const val TAG = "OrderExcelExporter"

    // Новый метод для автоматического сохранения в кэш без системных окон
    fun saveToCacheFile(context: Context, file: java.io.File, order: OrderParser.ParsedOrder): Boolean {
        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Заказ")

            val headerFont = workbook.createFont().apply { setBold(true); fontHeightInPoints = 12 }
            val sectionFont = workbook.createFont().apply { setBold(true); fontHeightInPoints = 12; color = IndexedColors.WHITE.index }
            val totalFont = workbook.createFont().apply { setBold(true) }

            val headerStyle = workbook.createCellStyle().apply { setFont(headerFont); alignment = HorizontalAlignment.CENTER; verticalAlignment = VerticalAlignment.CENTER }
            val sectionStyle = workbook.createCellStyle().apply { setFont(sectionFont); fillForegroundColor = IndexedColors.BROWN.index; fillPattern = FillPatternType.SOLID_FOREGROUND }
            val dataStyle = workbook.createCellStyle().apply { alignment = HorizontalAlignment.LEFT; borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN; borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN; verticalAlignment = VerticalAlignment.CENTER }
            val numStyle = workbook.createCellStyle().apply { alignment = HorizontalAlignment.RIGHT; borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN; borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN; verticalAlignment = VerticalAlignment.CENTER }
            val totalStyle = workbook.createCellStyle().apply { setFont(totalFont); fillForegroundColor = IndexedColors.GREY_25_PERCENT.index; fillPattern = FillPatternType.SOLID_FOREGROUND; borderTop = BorderStyle.DOUBLE; borderBottom = BorderStyle.DOUBLE; alignment = HorizontalAlignment.LEFT }
            val totalNumStyle = workbook.createCellStyle().apply { setFont(totalFont); fillForegroundColor = IndexedColors.GREY_25_PERCENT.index; fillPattern = FillPatternType.SOLID_FOREGROUND; borderTop = BorderStyle.DOUBLE; borderBottom = BorderStyle.DOUBLE; alignment = HorizontalAlignment.RIGHT }

            var rowIdx = 0
            sheet.createRow(rowIdx++).apply {
                createCell(0).apply { setCellValue("Наименование"); cellStyle = headerStyle }
                createCell(1).apply { setCellValue("Количество"); cellStyle = headerStyle }
            }

            if (order.newPositions.isNotEmpty()) {
                sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue("Новые позиции"); cellStyle = sectionStyle }; createCell(1).apply { cellStyle = sectionStyle } }
                for ((name, qty) in order.newPositions) {
                    sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue(name); cellStyle = dataStyle }; createCell(1).apply { setCellValue(qty.toDouble()); cellStyle = numStyle } }
                }
                sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue("ИТОГО ЗАКАЗ"); cellStyle = totalStyle }; createCell(1).apply { setCellValue(order.totalNew.toDouble()); cellStyle = totalNumStyle } }
                rowIdx++
            }

            if (order.exchangePositions.isNotEmpty()) {
                sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue("На обмен"); cellStyle = sectionStyle }; createCell(1).apply { cellStyle = sectionStyle } }
                for ((name, qty) in order.exchangePositions) {
                    sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue(name); cellStyle = dataStyle }; createCell(1).apply { setCellValue(qty.toDouble()); cellStyle = numStyle } }
                }
                sheet.createRow(rowIdx++).apply { createCell(0).apply { setCellValue("ИТОГО ОБМЕН"); cellStyle = totalStyle }; createCell(1).apply { setCellValue(order.totalExchange.toDouble()); cellStyle = totalNumStyle } }
            }

            sheet.setColumnWidth(0, 12000)
            sheet.setColumnWidth(1, 4000)

            // Записываем напрямую в файл
            file.outputStream().use { workbook.write(it) }
            workbook.close()
            return true
        } catch (e: Exception) {
            Log.e("OrderExcelExporter", "Ошибка сохранения в кэш: ${e.localizedMessage}")
            return false
        }
    }

    suspend fun saveToExcel(context: Context, uri: Uri, order: OrderParser.ParsedOrder): Boolean = withContext(Dispatchers.IO) {
        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Заказ")

            // ── Шрифты ──────────────────────────────────────────────
            val headerFont = workbook.createFont().apply { setBold(true); fontHeightInPoints = 12 }
            val sectionFont = workbook.createFont().apply { setBold(true); fontHeightInPoints = 12; color = IndexedColors.WHITE.index }
            val totalFont = workbook.createFont().apply { setBold(true) }

            // ── Стили ячеек ─────────────────────────────────────────
            val headerStyle = workbook.createCellStyle().apply {
                setFont(headerFont)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
            }

            val sectionStyle = workbook.createCellStyle().apply {
                setFont(sectionFont)
                fillForegroundColor = IndexedColors.BROWN.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }

            val dataStyle = workbook.createCellStyle().apply {
                alignment = HorizontalAlignment.LEFT
                borderBottom = BorderStyle.THIN
                borderTop = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
                verticalAlignment = VerticalAlignment.CENTER
            }

            val numStyle = workbook.createCellStyle().apply {
                alignment = HorizontalAlignment.RIGHT
                borderBottom = BorderStyle.THIN
                borderTop = BorderStyle.THIN
                borderLeft = BorderStyle.THIN
                borderRight = BorderStyle.THIN
                verticalAlignment = VerticalAlignment.CENTER
            }

            val totalStyle = workbook.createCellStyle().apply {
                setFont(totalFont)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                borderTop = BorderStyle.DOUBLE
                borderBottom = BorderStyle.DOUBLE
                alignment = HorizontalAlignment.LEFT
            }

            val totalNumStyle = workbook.createCellStyle().apply {
                setFont(totalFont)
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                borderTop = BorderStyle.DOUBLE
                borderBottom = BorderStyle.DOUBLE
                alignment = HorizontalAlignment.RIGHT
            }

            // ── Шапка таблицы ───────────────────────────────────────
            var rowIdx = 0
            sheet.createRow(rowIdx++).apply {
                createCell(0).apply { setCellValue("Наименование"); cellStyle = headerStyle }
                createCell(1).apply { setCellValue("Количество"); cellStyle = headerStyle }
            }

            // ── Новые позиции ───────────────────────────────────────
            if (order.newPositions.isNotEmpty()) {
                sheet.createRow(rowIdx++).apply {
                    createCell(0).apply { setCellValue("Новые позиции"); cellStyle = sectionStyle }
                    createCell(1).apply { cellStyle = sectionStyle }
                }
                for ((name, qty) in order.newPositions) {
                    sheet.createRow(rowIdx++).apply {
                        createCell(0).apply { setCellValue(name); cellStyle = dataStyle }
                        createCell(1).apply { setCellValue(qty.toDouble()); cellStyle = numStyle }
                    }
                }
                sheet.createRow(rowIdx++).apply {
                    createCell(0).apply { setCellValue("ИТОГО ЗАКАЗ"); cellStyle = totalStyle }
                    createCell(1).apply { setCellValue(order.totalNew.toDouble()); cellStyle = totalNumStyle }
                }
                rowIdx++ // Пустая строка-отступ
            }

            // ── На обмен ────────────────────────────────────────────
            if (order.exchangePositions.isNotEmpty()) {
                sheet.createRow(rowIdx++).apply {
                    createCell(0).apply { setCellValue("На обмен"); cellStyle = sectionStyle }
                    createCell(1).apply { cellStyle = sectionStyle }
                }
                for ((name, qty) in order.exchangePositions) {
                    sheet.createRow(rowIdx++).apply {
                        createCell(0).apply { setCellValue(name); cellStyle = dataStyle }
                        createCell(1).apply { setCellValue(qty.toDouble()); cellStyle = numStyle }
                    }
                }
                sheet.createRow(rowIdx++).apply {
                    createCell(0).apply { setCellValue("ИТОГО ОБМЕН"); cellStyle = totalStyle }
                    createCell(1).apply { setCellValue(order.totalExchange.toDouble()); cellStyle = totalNumStyle }
                }
            }

            // ── Размеры колонок ─────────────────────────────────────
            sheet.setColumnWidth(0, 12000)
            sheet.setColumnWidth(1, 4000)

            // ── Сохранение ──────────────────────────────────────────
            context.contentResolver.openOutputStream(uri)?.use { workbook.write(it) }
            workbook.close()
            Log.d(TAG, "[EXCEL] Успешно сохранено")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[EXCEL] Ошибка: ${e.localizedMessage}")
            false
        }
    }
}
