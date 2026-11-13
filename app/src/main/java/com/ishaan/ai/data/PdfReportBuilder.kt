package com.ishaan.ai.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.ishaan.ai.data.db.LabTest
import com.ishaan.ai.domain.AnalysisEngine
import com.ishaan.ai.domain.Flag
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.HealthReport
import com.ishaan.ai.domain.HealthSnapshot
import com.ishaan.ai.domain.ReportKind
import com.ishaan.ai.domain.Table
import java.io.File

/** Builds a clean A4 PDF from a HealthReport using Android's built-in PdfDocument (no third-party library). */
class PdfReportBuilder {
    private val W = 595; private val H = 842; private val M = 40f
    private val contentW get() = W - 2 * M

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(33, 33, 33) }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.rgb(97, 97, 97) }
    private val h1 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(13, 71, 161) }
    private val h2 = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(21, 101, 192) }
    private val cell = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.rgb(33, 33, 33) }
    private val cellBold = TextPaint(cell).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
    private val line = Paint().apply { color = Color.rgb(207, 216, 220); strokeWidth = 0.6f }

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var c: Canvas
    private var y = 0f
    private var pageNo = 0
    private var title = ""

    fun build(report: HealthReport, snapshot: HealthSnapshot, out: File) {
        doc = PdfDocument(); pageNo = 0; title = "${report.kind.title} — ${report.childName}"
        newPage()
        text(report.kind.title, h1)
        text("${report.childName} · generated ${Fmt.dateTime(report.generatedAt)}" + (report.periodStart?.let { " · from ${Fmt.date(it)}" } ?: ""), small)
        y += 6
        if (report.kind == ReportKind.DOCTOR || report.kind == ReportKind.COMPLETE || report.kind == ReportKind.MONTHLY) {
            creatinineChart(snapshot)
        }
        for (s in report.sections) {
            ensure(40f)
            y += 6; text(s.title, h2); y += 2
            for (f in s.findings) {
                val prefix = when (f.flag) { Flag.URGENT -> "URGENT: "; Flag.REVIEW -> "For review: "; else -> "" }
                val meta = listOfNotNull(f.date?.let { Fmt.date(it) }, f.basis.takeIf { it.isNotBlank() }).joinToString(" · ")
                text("• $prefix${f.text}" + (if (meta.isNotBlank()) "  [$meta]" else ""), body, indent = 6f, color = if (f.flag == Flag.URGENT) Color.rgb(183, 28, 28) else if (f.flag == Flag.REVIEW) Color.rgb(230, 81, 0) else null)
            }
            s.table?.let { table(it) }
            s.note?.let { y += 2; text(it, small) }
        }
        if (report.missing.isNotEmpty()) { ensure(40f); y += 6; text("Missing or incomplete information", h2); report.missing.forEach { text("• $it", body, 6f) } }
        if (report.questions.isNotEmpty()) { ensure(40f); y += 6; text("Questions for the doctor", h2); report.questions.forEachIndexed { i, q -> text("${i + 1}. $q", body, 6f) } }
        y += 10; text(AnalysisEngine.DISCLAIMER, small)
        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        c = page!!.canvas
        c.drawRect(0f, 0f, W.toFloat(), 22f, Paint().apply { color = Color.rgb(21, 101, 192) })
        c.drawText("Ishaan AI  ·  $title", M, 15f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.WHITE })
        c.drawText("Page $pageNo", W - M - 30, 15f, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.WHITE })
        c.drawText("Generated on-device from records entered by the parent. Not a diagnosis. Check OCR values against original reports.", M, H - 16f, small)
        y = 36f
    }

    private fun finishPage() { page?.let { doc.finishPage(it) }; page = null }

    private fun ensure(h: Float) { if (y + h > H - 34) newPage() }

    private fun layout(t: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1f, 1f).build()

    private fun text(t: String, p: TextPaint, indent: Float = 0f, color: Int? = null) {
        val paint = if (color != null) TextPaint(p).apply { this.color = color } else p
        val l = layout(t, paint, (contentW - indent).toInt())
        // Split long paragraphs across pages line by line.
        var start = 0
        while (start < l.lineCount) {
            val avail = H - 34 - y
            var end = start
            while (end < l.lineCount && l.getLineBottom(end) - l.getLineTop(start) <= avail) end++
            if (end == start) { newPage(); continue }
            c.save(); c.translate(M + indent, y - l.getLineTop(start)); c.clipRect(0f, l.getLineTop(start).toFloat(), contentW, l.getLineBottom(end - 1).toFloat())
            l.draw(c); c.restore()
            y += l.getLineBottom(end - 1) - l.getLineTop(start) + 2
            start = end
            if (start < l.lineCount) newPage()
        }
    }

    private fun table(t: Table) {
        val n = t.headers.size
        val weights = (0 until n).map { i -> maxOf(t.headers[i].length, t.rows.maxOfOrNull { it.getOrNull(i)?.length?.coerceAtMost(40) ?: 0 } ?: 0).coerceAtLeast(5).toFloat() }
        val total = weights.sum()
        val widths = weights.map { it / total * contentW }
        fun drawRow(cells: List<String>, header: Boolean) {
            val ls = cells.mapIndexed { i, s -> layout(s, if (header) cellBold else cell, (widths[i] - 6).toInt().coerceAtLeast(10)) }
            val h = (ls.maxOf { it.height } + 6).toFloat()
            if (y + h > H - 34) { newPage(); if (!header) drawRow(t.headers, true) }
            if (header) c.drawRect(M, y, M + contentW, y + h, Paint().apply { color = Color.rgb(21, 101, 192) })
            var x = M
            ls.forEachIndexed { i, l -> c.save(); c.translate(x + 3, y + 3); l.draw(c); c.restore(); x += widths[i] }
            c.drawLine(M, y + h, M + contentW, y + h, line)
            y += h
        }
        y += 2
        drawRow(t.headers, true)
        t.rows.forEach { drawRow(it, false) }
        y += 4
    }

    private fun creatinineChart(s: HealthSnapshot) {
        val pts = s.labSeries(LabTest.CREATININE).filter { it.valueNumeric != null }
        if (pts.size < 2) return
        val unit = pts.last().unit
        val series = pts.filter { it.unit.equals(unit, true) }
        if (series.size < 2) return
        ensure(170f)
        text("Serum creatinine over time ($unit)", h2)
        val left = M + 30; val top = y + 6; val w = contentW - 40; val h = 120f
        val minT = series.first().sampleDate.toFloat(); val maxT = series.last().sampleDate.toFloat().coerceAtLeast(minT + 1)
        val maxV = (series.maxOf { maxOf(it.valueNumeric!!, it.refHigh ?: 0.0) } * 1.15).toFloat()
        val axis = Paint().apply { color = Color.GRAY; strokeWidth = 0.8f }
        c.drawLine(left, top, left, top + h, axis); c.drawLine(left, top + h, left + w, top + h, axis)
        for (k in 0..4) { val v = maxV * k / 4; val yy = top + h - h * k / 4; c.drawText(Fmt.num(Math.round(v * 100) / 100.0), M, yy + 3, small); c.drawLine(left, yy, left + w, yy, line) }
        val path = Path(); val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 101, 192) }
        series.forEachIndexed { i, r ->
            val x = left + w * ((r.sampleDate - minT) / (maxT - minT)); val yy = top + h - h * (r.valueNumeric!!.toFloat() / maxV)
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            c.drawCircle(x, yy, 2.5f, dot)
            if (i == 0 || i == series.lastIndex || series.size <= 8) c.drawText(Fmt.date(r.sampleDate), x - 18, top + h + 10, small)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 101, 192); style = Paint.Style.STROKE; strokeWidth = 1.5f })
        y = top + h + 18
        text("Each point is a stored result; reference ranges are listed per report in the table below.", small)
    }
}
