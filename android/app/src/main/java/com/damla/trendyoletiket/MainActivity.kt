package com.damla.trendyoletiket

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val requestPdf = 42
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedPdf: Uri? = null

    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var convertButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var countPicker: NumberPicker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.rgb(244, 246, 248))
        }

        root.addView(TextView(this).apply {
            text = "Trendyol Etiket Düzenleyici"
            textSize = 25f
            setTextColor(Color.rgb(23, 33, 43))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "PDF etiketlerini 58 mm termal yazıcıya uygun JPG şeritlerine dönüştürür."
            textSize = 14f
            setTextColor(Color.rgb(86, 97, 109))
            setPadding(0, dp(8), 0, dp(24))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        card.addView(TextView(this).apply {
            text = "1. Trendyol PDF'sini seç"
            textSize = 16f
            setTextColor(Color.rgb(23, 33, 43))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        fileText = TextView(this).apply {
            text = "Henüz bir PDF seçilmedi"
            textSize = 14f
            setTextColor(Color.rgb(86, 97, 109))
            setPadding(0, dp(14), 0, dp(12))
        }
        card.addView(fileText)

        card.addView(Button(this).apply {
            text = "PDF SEÇ"
            setOnClickListener { choosePdf() }
        })

        val optionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(20))
        }
        optionRow.addView(TextView(this).apply {
            text = "2. Bir JPG'deki etiket"
            textSize = 16f
            setTextColor(Color.rgb(23, 33, 43))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        countPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 10
            value = 5
            wrapSelectorWheel = false
        }
        optionRow.addView(countPicker)
        card.addView(optionRow)

        convertButton = Button(this).apply {
            text = "JPG'LERİ HAZIRLA"
            isEnabled = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(242, 122, 26))
            setOnClickListener { convert() }
        }
        card.addView(convertButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        card.addView(progress)

        statusText = TextView(this).apply {
            text = "PDF seçerek başlayın."
            textSize = 14f
            setTextColor(Color.rgb(86, 97, 109))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        card.addView(statusText)
        setContentView(root)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, requestPdf)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestPdf && resultCode == RESULT_OK) {
            selectedPdf = data?.data
            val uri = selectedPdf ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            fileText.text = "PDF seçildi ve hazır."
            statusText.text = "JPG'leri Hazırla düğmesine basın."
            convertButton.isEnabled = true
        }
    }

    private fun convert() {
        val uri = selectedPdf ?: return
        convertButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "Etiketler hazırlanıyor..."

        executor.execute {
            try {
                val labels = extractLabels(uri)
                if (labels.isEmpty()) error("PDF içinde etiket bulunamadı.")
                val outputs = saveBatches(labels, countPicker.value)
                labels.forEach { it.recycle() }
                runOnUiThread {
                    progress.visibility = View.GONE
                    convertButton.isEnabled = true
                    statusText.text = "${labels.size} etiket, $outputs JPG hazırlandı."
                    Toast.makeText(
                        this,
                        "JPG'ler İndirilenler/Trendyol_Etiketleri klasörüne kaydedildi.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    convertButton.isEnabled = true
                    statusText.text = "İşlem tamamlanamadı."
                    Toast.makeText(this, error.message ?: "Hata oluştu.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractLabels(uri: Uri): MutableList<Bitmap> {
        val labels = mutableListOf<Bitmap>()
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("PDF açılamadı.")
        PdfRenderer(descriptor).use { renderer ->
            for (pageIndex in 0 until renderer.pageCount) {
                renderer.openPage(pageIndex).use { page ->
                    val renderWidth = 1654
                    val renderHeight = (renderWidth * page.height.toFloat() / page.width).roundToInt()
                    val pageBitmap = Bitmap.createBitmap(
                        renderWidth,
                        renderHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(pageBitmap).drawColor(Color.WHITE)
                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val cellWidth = pageBitmap.width / 3
                    val cellHeight = pageBitmap.height / 3
                    for (row in 0..2) {
                        for (column in 0..2) {
                            val left = column * cellWidth
                            val top = row * cellHeight
                            val right = if (column == 2) pageBitmap.width else (column + 1) * cellWidth
                            val bottom = (top + cellHeight * 0.90f).roundToInt()
                                .coerceAtMost(pageBitmap.height)
                            val cell = Bitmap.createBitmap(
                                pageBitmap,
                                left,
                                top,
                                right - left,
                                bottom - top
                            )
                            val bounds = findContentBounds(cell)
                            if (bounds != null) labels.add(resizeLabel(cell, bounds))
                            cell.recycle()
                        }
                    }
                    pageBitmap.recycle()
                }
            }
        }
        return labels
    }

    private fun findContentBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var inkCount = 0

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                val color = pixels[offset + x]
                val gray = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
                if (gray < 245) {
                    inkCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (inkCount < width * height * 0.006) return null
        val margin = 7
        return Rect(
            (minX - margin).coerceAtLeast(0),
            (minY - margin).coerceAtLeast(0),
            (maxX + margin + 1).coerceAtMost(width),
            (maxY + margin + 1).coerceAtMost(height)
        )
    }

    private fun resizeLabel(cell: Bitmap, bounds: Rect): Bitmap {
        val cropped = Bitmap.createBitmap(
            cell,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()
        )
        val targetWidth = 376
        val targetHeight = (cropped.height * targetWidth.toFloat() / cropped.width).roundToInt()
        val resized = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        cropped.recycle()

        val label = Bitmap.createBitmap(384, targetHeight + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(label)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(resized, 4f, 4f, null)
        resized.recycle()
        return label
    }

    private fun saveBatches(labels: List<Bitmap>, perImage: Int): Int {
        var outputCount = 0
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        labels.chunked(perImage).forEachIndexed { batchIndex, batch ->
            val totalHeight = batch.sumOf { it.height } + (batch.size - 1) * 6
            val strip = Bitmap.createBitmap(384, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(strip)
            canvas.drawColor(Color.WHITE)
            var y = 0f
            batch.forEach {
                canvas.drawBitmap(it, 0f, y, null)
                y += it.height + 6
            }

            val values = ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "trendyol_etiketleri_${stamp}_${batchIndex + 1}.jpg"
                )
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Trendyol_Etiketleri"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val outputUri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("JPG kaydedilemedi.")

            contentResolver.openOutputStream(outputUri)?.use {
                if (!strip.compress(Bitmap.CompressFormat.JPEG, 96, it)) {
                    error("JPG oluşturulamadı.")
                }
            } ?: error("JPG dosyası açılamadı.")
            strip.recycle()

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(outputUri, values, null, null)
            outputCount++
        }
        return outputCount
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
