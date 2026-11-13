package com.ishaan.ai.data

import android.net.Uri
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.ParsedReport
import com.ishaan.ai.domain.ReportParser
import com.ishaan.ai.security.Vault
import java.time.LocalDate

/** Upload → encrypt original → OCR → rule-based extraction (stored as UNVERIFIED) → parent review. */
class ReportRepository(private val db: AppDatabase, private val vault: Vault, private val ocr: OcrService) {
    private val dao = db.reportDao()

    data class ImportOutcome(val reportId: Long, val parsed: ParsedReport?, val error: String?)

    suspend fun importReport(childId: Long, uri: Uri, title: String, category: ReportCategory?, runOcr: Boolean = true): ImportOutcome {
        val stored = vault.importUri(uri)
        val isSupported = stored.mimeType == "application/pdf" || stored.mimeType.startsWith("image/")
        var report = MedicalReport(
            childId = childId,
            title = title.ifBlank { stored.originalName },
            category = (category ?: ReportCategory.OTHER).name,
            fileName = stored.fileName,
            originalName = stored.originalName,
            mimeType = stored.mimeType,
        )
        val id = dao.insertReport(report)
        report = report.copy(id = id)
        if (!runOcr || !isSupported) {
            dao.updateReport(report.copy(ocrStatus = if (isSupported) "NONE" else "FAILED", notes = if (isSupported) "" else "File type not supported by OCR. Enter values manually."))
            return ImportOutcome(id, null, if (isSupported) null else "This file type can't be read by OCR; it is stored safely and you can enter values manually.")
        }
        return runExtraction(report, category)
    }

    suspend fun runExtraction(report: MedicalReport, forcedCategory: ReportCategory? = null): ImportOutcome {
        return try {
            val res = ocr.recognise(report.fileName, report.mimeType)
            if (res.text.isBlank()) {
                dao.updateReport(report.copy(ocrStatus = "NO_TEXT", pageCount = res.pages))
                return ImportOutcome(report.id, null, "No text was found. The photo may be blurred — the original is saved, and you can enter values manually.")
            }
            val parsed = ReportParser.parse(res.text)
            val cat = forcedCategory?.takeIf { it != ReportCategory.OTHER } ?: parsed.category
            val date = parsed.bestDate
            val sampleMs = date?.date?.let { Fmt.startOfDay(it) } ?: report.createdAt
            dao.updateReport(
                report.copy(
                    ocrText = res.text, ocrStatus = "DONE", pageCount = res.pages, category = cat.name,
                    reportDate = date?.date?.let { Fmt.startOfDay(it) }, reportDateSource = Source.OCR,
                    notes = listOfNotNull(
                        if (date == null) "Report date was not detected. Please set it — results are dated to the upload day until you do." else null,
                        if (date?.ambiguous == true) "Date '${date.raw}' was read as day/month/year. Please confirm." else null,
                    ).joinToString(" "),
                ),
            )
            dao.insertLabs(parsed.labs.map {
                ExtractedLabResult(
                    childId = report.childId, reportId = report.id, testCode = it.test.name,
                    displayName = it.displayName.take(80), valueText = it.valueText, valueNumeric = it.valueNumeric,
                    unit = it.unit, refLow = it.refLow, refHigh = it.refHigh, refText = it.refText,
                    specimen = it.test.specimen, sampleDate = sampleMs, source = Source.OCR,
                    verification = Verification.UNVERIFIED, originalOcrLine = it.line, originalValueText = it.valueText,
                )
            })
            dao.insertImaging(parsed.imaging.map {
                ImagingFinding(
                    childId = report.childId, reportId = report.id, modality = it.modality, studyDate = sampleMs,
                    side = it.side, parameter = it.param.name, valueNumeric = it.valueNumeric, valueText = it.valueText.take(300),
                    unit = it.unit, source = Source.OCR, verification = Verification.UNVERIFIED, originalOcrLine = it.line,
                )
            })
            ImportOutcome(report.id, parsed, null)
        } catch (t: Throwable) {
            dao.updateReport(report.copy(ocrStatus = "FAILED", notes = "OCR failed: ${t.message ?: t.javaClass.simpleName}"))
            ImportOutcome(report.id, null, "OCR could not read this document. The original is saved; you can enter values manually.")
        }
    }

    /** Changing the report date re-dates every result that came from it (they share the sample date). */
    suspend fun setReportDate(report: MedicalReport, date: LocalDate) {
        val ms = Fmt.startOfDay(date)
        dao.updateReport(report.copy(reportDate = ms, reportDateSource = Source.MANUAL, notes = report.notes.replace(Regex("Report date was not detected[^.]*\\.[^.]*\\.\\s?"), "").replace(Regex("Date '[^']*' was read as day/month/year\\. Please confirm\\.\\s?"), "")))
        db.reportDao().labs(report.childId).filter { it.reportId == report.id }.forEach { dao.upsertLab(it.copy(sampleDate = ms, updatedAt = System.currentTimeMillis())) }
        db.reportDao().imaging(report.childId).filter { it.reportId == report.id }.forEach { dao.upsertImaging(it.copy(studyDate = ms)) }
    }

    /** Parent confirms a value matches the document. */
    suspend fun verifyLab(l: ExtractedLabResult) = dao.upsertLab(l.copy(verification = Verification.VERIFIED, updatedAt = System.currentTimeMillis()))

    /** Parent corrects an OCR value. originalValueText/originalOcrLine are preserved untouched. */
    suspend fun correctLab(l: ExtractedLabResult, valueText: String, unit: String, refText: String, testCode: String) {
        val num = valueText.replace(',', '.').trim().toDoubleOrNull()
        val range = ReportParser.parseRange(refText)
        val changed = valueText != l.valueText || unit != l.unit || refText != l.refText || testCode != l.testCode
        dao.upsertLab(
            l.copy(
                valueText = valueText.trim(), valueNumeric = num, unit = unit.trim(), refText = refText.trim(),
                refLow = range.first, refHigh = range.second,
                testCode = testCode, verification = if (l.source == Source.OCR) (if (changed) Verification.CORRECTED else Verification.VERIFIED) else Verification.PARENT_ENTERED,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun verifyImaging(f: ImagingFinding) = dao.upsertImaging(f.copy(verification = Verification.VERIFIED))

    suspend fun deleteReport(r: MedicalReport, deleteExtracted: Boolean) {
        if (deleteExtracted) {
            dao.labs(r.childId).filter { it.reportId == r.id }.forEach { dao.deleteLab(it) }
            dao.imaging(r.childId).filter { it.reportId == r.id }.forEach { dao.deleteImaging(it) }
        }
        dao.deleteReport(r)
        vault.delete(r.fileName)
    }
}
