package com.ishaan.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ishaan.ai.security.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device OCR using ML Kit's bundled Latin text model (shipped inside the APK; works in airplane mode).
 * PDFs are rendered page by page with Android's PdfRenderer and then recognised.
 */
class OcrService(private val context: Context, private val vault: Vault) {

    data class OcrResult(val text: String, val pages: Int)

    suspend fun recognise(fileName: String, mime: String, maxPages: Int = 15): OcrResult = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            if (mime == "application/pdf") {
                val tmp = vault.decryptToTemp(fileName, "ocr.pdf")
                try {
                    ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val sb = StringBuilder()
                            val n = minOf(renderer.pageCount, maxPages)
                            for (i in 0 until n) {
                                renderer.openPage(i).use { page ->
                                    val scale = 2.2f
                                    val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                                    bmp.recycle()
                                    if (n > 1) sb.append("--- page ${i + 1} ---\n")
                                    sb.append(rowsOf(text)).append('\n')
                                }
                            }
                            OcrResult(sb.toString(), renderer.pageCount)
                        }
                    }
                } finally { tmp.delete() }
            } else {
                val bmp = vault.decodeBitmap(fileName, 3000) ?: return@withContext OcrResult("", 1)
                val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                OcrResult(rowsOf(text), 1)
            }
        } finally { recognizer.close() }
    }

    /**
     * ML Kit returns text in blocks, which splits table columns apart (test name in one block,
     * value in another). Re-assemble physical rows by vertical position so "Creatinine 0.4 mg/dL 0.2-0.4"
     * stays on one line.
     */
    private fun rowsOf(text: Text): String {
        data class L(val s: String, val top: Int, val bottom: Int, val left: Int)
        val lines = text.textBlocks.flatMap { b -> b.lines.mapNotNull { l -> l.boundingBox?.let { L(l.text, it.top, it.bottom, it.left) } } }
        if (lines.isEmpty()) return text.text
        val sorted = lines.sortedBy { (it.top + it.bottom) / 2 }
        val rows = mutableListOf<MutableList<L>>()
        for (l in sorted) {
            val c = (l.top + l.bottom) / 2
            val h = (l.bottom - l.top).coerceAtLeast(8)
            val row = rows.lastOrNull()?.takeIf { r -> val rc = r.map { (it.top + it.bottom) / 2 }.average(); kotlin.math.abs(rc - c) < h * 0.6 }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString("   ") { it.s } }
    }
}
