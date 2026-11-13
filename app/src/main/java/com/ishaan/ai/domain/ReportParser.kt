package com.ishaan.ai.domain

import com.ishaan.ai.data.db.ImagingParam
import com.ishaan.ai.data.db.LabTest
import com.ishaan.ai.data.db.ReportCategory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class ParsedLab(
    val test: LabTest,
    val displayName: String,
    val valueText: String,
    val valueNumeric: Double?,
    val unit: String,
    val refLow: Double?,
    val refHigh: Double?,
    val refText: String,
    val line: String,
)

data class ParsedImaging(
    val modality: String,
    val side: String,
    val param: ImagingParam,
    val valueNumeric: Double?,
    val valueText: String,
    val unit: String,
    val line: String,
)

data class DetectedDate(val date: LocalDate, val raw: String, val ambiguous: Boolean, val nearKeyword: Boolean)

data class ParsedReport(
    val category: ReportCategory,
    val dates: List<DetectedDate>,
    val labs: List<ParsedLab>,
    val imaging: List<ParsedImaging>,
) {
    val bestDate: DetectedDate? get() = dates.firstOrNull { it.nearKeyword } ?: dates.firstOrNull()
}

/**
 * Rule-based extraction from OCR text. This is deliberately conservative: anything it finds is
 * a *suggestion* stored as UNVERIFIED, together with the exact OCR line, so the parent can check
 * it against the original document. It never invents a value that isn't in the text.
 */
object ReportParser {

    private val units = listOf(
        "mg/dl", "mg/dL", "µmol/l", "umol/l", "μmol/l", "mmol/l", "meq/l", "g/dl", "mg/l", "/hpf", "/cumm",
        "cells/cumm", "/µl", "/ul", "cfu/ml", "mmhg", "ml/min/1.73m2", "%", "mm", "cm", "ml",
    )

    private data class Alias(val alias: String, val test: LabTest, val regex: Regex)

    private val aliases: List<Alias> = LabTest.entries.flatMap { t ->
        t.aliases.map { a -> Alias(a, t, Regex("(?<![a-z])" + Regex.escape(a.trim()) + "(?![a-z])", RegexOption.IGNORE_CASE)) }
    }.sortedByDescending { it.alias.length }

    private val numberRx = Regex("""(?<![\w.])(\d{1,6}(?:\.\d{1,4})?)(?![\d.])""")
    private val rangeRx = Regex("""(\d+(?:\.\d+)?)\s*(?:-|–|—|to)\s*(\d+(?:\.\d+)?)""")
    private val ltRx = Regex("""(?:<|upto|up to|less than)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val gtRx = Regex("""(?:>|more than|greater than)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val qualitative = Regex(
        """\b(nil|absent|present|trace|negative|positive|sterile|no growth|not detected|detected|\+{1,4}|1\+|2\+|3\+|4\+)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): ParsedReport {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val category = detectCategory(text)
        val dates = detectDates(lines)
        val labs = if (category in setOf(ReportCategory.ULTRASOUND, ReportCategory.DMSA, ReportCategory.MCU)) emptyList() else parseLabs(lines, category)
        val imaging = when (category) {
            ReportCategory.ULTRASOUND -> parseUltrasound(lines)
            ReportCategory.DMSA -> parseDmsa(lines)
            ReportCategory.MCU -> parseImpression(lines, "MCU")
            else -> emptyList()
        }
        return ParsedReport(category, dates, labs, imaging)
    }

    fun detectCategory(text: String): ReportCategory {
        val t = text.lowercase(Locale.ROOT)
        return when {
            "dmsa" in t || "dimercaptosuccinic" in t -> ReportCategory.DMSA
            "micturating" in t || "vcug" in t || Regex("\\bmcu\\b").containsMatchIn(t) -> ReportCategory.MCU
            "ultrasound" in t || "ultrasonography" in t || "sonography" in t || Regex("\\busg\\b").containsMatchIn(t) -> ReportCategory.ULTRASOUND
            "discharge summary" in t || "date of discharge" in t -> ReportCategory.DISCHARGE_SUMMARY
            "operation note" in t || "operative note" in t || "procedure note" in t -> ReportCategory.OPERATION_NOTE
            "culture" in t && "urine" in t -> ReportCategory.URINE_CULTURE
            "culture" in t && "blood" in t -> ReportCategory.BLOOD_CULTURE
            ("urine" in t && ("routine" in t || "r/e" in t || "examination" in t || "pus cells" in t)) -> ReportCategory.URINE_ROUTINE
            "creatinine" in t || "urea" in t || "kidney function" in t || "renal function" in t || "kft" in t || "rft" in t -> ReportCategory.KIDNEY_FUNCTION
            "sodium" in t || "potassium" in t || "electrolyte" in t -> ReportCategory.ELECTROLYTES
            Regex("\\b(rx|tab\\.?|syp\\.?|syrup|drops)\\b").containsMatchIn(t) -> ReportCategory.PRESCRIPTION
            "haemoglobin" in t || "hemoglobin" in t || "cbc" in t || "complete blood count" in t -> ReportCategory.BLOOD_TEST
            else -> ReportCategory.OTHER
        }
    }

    // ---------------------------------------------------------------- dates

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val numericDate = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")
    private val isoDate = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val textDate = Regex("""\b(\d{1,2})[\s\-/]*([A-Za-z]{3,9})[\s\-/,]*(\d{2,4})\b""")
    private val dateKeywords = Regex("(collect|sample|report|receiv|registered|date of (study|exam|scan)|study date|exam date)", RegexOption.IGNORE_CASE)

    fun detectDates(lines: List<String>): List<DetectedDate> {
        val out = mutableListOf<DetectedDate>()
        lines.forEachIndexed { i, line ->
            val near = dateKeywords.containsMatchIn(line) || (i > 0 && dateKeywords.containsMatchIn(lines[i - 1]) && line.length < 25)
            isoDate.findAll(line).forEach { m ->
                safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { out += DetectedDate(it, m.value, false, near) }
            }
            numericDate.findAll(line).forEach { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); var y = m.groupValues[3].toInt()
                if (y < 100) y += 2000
                // Indian reports are day-first. Flag when both orders would be valid.
                val d = safeDate(y, b, a)
                if (d != null) out += DetectedDate(d, m.value, ambiguous = a <= 12 && b <= 12 && a != b, nearKeyword = near)
            }
            textDate.findAll(line).forEach { m ->
                val mon = months[m.groupValues[2].lowercase(Locale.ROOT).take(4).let { if (it == "sept") it else it.take(3) }] ?: return@forEach
                var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
                safeDate(y, mon, m.groupValues[1].toInt())?.let { out += DetectedDate(it, m.value, false, near) }
            }
        }
        val today = LocalDate.now()
        return out.filter { !it.date.isAfter(today.plusDays(1)) && it.date.year >= 1990 }.distinctBy { it.date to it.nearKeyword }
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    fun parseUserDate(s: String): LocalDate? {
        val t = s.trim()
        for (p in listOf("dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "dd.MM.yyyy")) {
            try { return LocalDate.parse(t, DateTimeFormatter.ofPattern(p)) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    // ---------------------------------------------------------------- labs

    private fun parseLabs(lines: List<String>, category: ReportCategory): List<ParsedLab> {
        val out = mutableListOf<ParsedLab>()
        val urineReport = category in setOf(ReportCategory.URINE_ROUTINE, ReportCategory.URINE_CULTURE)
        for ((i, line) in lines.withIndex()) {
            val lower = line.lowercase(Locale.ROOT)
            val hit = aliases.firstOrNull { it.regex.containsMatchIn(lower) } ?: continue
            var test = hit.test
            // Specimen guard: "urine creatinine" / "urine protein" must not be filed as serum values, and vice versa.
            val lineSaysUrine = "urine" in lower
            if (test.specimen == "SERUM" && (lineSaysUrine || urineReport)) test = LabTest.OTHER
            if (test.specimen == "URINE" && !urineReport && !lineSaysUrine && test in setOf(LabTest.URINE_PROTEIN, LabTest.URINE_PH, LabTest.URINE_RBC, LabTest.URINE_SG)) continue
            if (test == LabTest.URINE_CULTURE && !urineReport) continue

            val afterName = line.substring(hit.regex.find(lower)!!.range.last + 1)
            var parsed = parseValuePart(afterName)
            if (parsed == null) {
                // Tables often come out of OCR with the value on the next line.
                for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
                    val nxt = lines[j]
                    if (aliases.any { it.regex.containsMatchIn(nxt.lowercase(Locale.ROOT)) }) break
                    parsed = parseValuePart(nxt)
                    if (parsed != null) break
                }
            }
            if (parsed == null) continue
            if (out.any { it.test == test && test != LabTest.OTHER }) continue // first occurrence wins; parent can add others
            out += ParsedLab(
                test = test,
                displayName = line.take(hit.regex.find(lower)!!.range.last + 1).trim().ifEmpty { test.label },
                valueText = parsed.valueText,
                valueNumeric = parsed.numeric,
                unit = parsed.unit.ifEmpty { "" },
                refLow = parsed.refLow,
                refHigh = parsed.refHigh,
                refText = parsed.refText,
                line = line,
            )
        }
        return out
    }

    data class ValuePart(val valueText: String, val numeric: Double?, val unit: String, val refLow: Double?, val refHigh: Double?, val refText: String)

    fun parseValuePart(raw: String): ValuePart? {
        val s = raw.replace(':', ' ').replace(',', '.').trim()
        if (s.isEmpty()) return null
        // Reference range first, so its numbers aren't mistaken for the result.
        val range = rangeRx.find(s)
        val lt = if (range == null) ltRx.find(s) else null
        val gt = if (range == null && lt == null) gtRx.find(s) else null
        val refMatch = range ?: lt ?: gt
        val withoutRef = if (refMatch != null) s.removeRange(refMatch.range) else s
        val num = numberRx.find(withoutRef)
        val unit = units.firstOrNull { withoutRef.contains(it, ignoreCase = true) || s.contains(it, ignoreCase = true) } ?: ""
        val qual = qualitative.find(withoutRef)
        val valueText: String
        val numeric: Double?
        if (num != null && (qual == null || num.range.first < qual.range.first)) {
            valueText = num.value; numeric = num.value.toDoubleOrNull()
        } else if (qual != null) {
            valueText = qual.value; numeric = null
        } else return null
        val refLow = range?.groupValues?.get(1)?.toDoubleOrNull() ?: gt?.groupValues?.get(1)?.toDoubleOrNull()
        val refHigh = range?.groupValues?.get(2)?.toDoubleOrNull() ?: lt?.groupValues?.get(1)?.toDoubleOrNull()
        return ValuePart(valueText, numeric, normaliseUnit(unit), refLow, refHigh, refMatch?.value?.trim() ?: "")
    }

    /** Parses a reference range typed or printed as "0.2 - 0.4", "< 5", "> 60". */
    fun parseRange(text: String): Pair<Double?, Double?> {
        val s = text.replace(',', '.')
        rangeRx.find(s)?.let { return it.groupValues[1].toDoubleOrNull() to it.groupValues[2].toDoubleOrNull() }
        ltRx.find(s)?.let { return null to it.groupValues[1].toDoubleOrNull() }
        gtRx.find(s)?.let { return it.groupValues[1].toDoubleOrNull() to null }
        return null to null
    }

    private fun normaliseUnit(u: String) = when (u.lowercase(Locale.ROOT)) {
        "mg/dl" -> "mg/dL"; "umol/l", "μmol/l", "µmol/l" -> "µmol/L"; "mmol/l" -> "mmol/L"; "meq/l" -> "mEq/L"
        "g/dl" -> "g/dL"; "mg/l" -> "mg/L"; "mmhg" -> "mmHg"; else -> u
    }

    // ---------------------------------------------------------------- imaging

    private fun sideOf(lower: String, current: String): String = when {
        Regex("\\b(right|rt\\.?)\\b").containsMatchIn(lower) && !Regex("\\b(left|lt\\.?)\\b").containsMatchIn(lower) -> "RIGHT"
        Regex("\\b(left|lt\\.?)\\b").containsMatchIn(lower) && !Regex("\\b(right|rt\\.?)\\b").containsMatchIn(lower) -> "LEFT"
        "bladder" in lower -> "BLADDER"
        else -> current
    }

    private val mmOrCm = Regex("""(\d+(?:\.\d+)?)\s*(mm|cm)\b""", RegexOption.IGNORE_CASE)
    private val sizeRx = Regex("""(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+(?:\.\d+)?)(?:\s*[x×*]\s*(\d+(?:\.\d+)?))?\s*(mm|cm)""", RegexOption.IGNORE_CASE)
    private val mlRx = Regex("""(\d+(?:\.\d+)?)\s*(ml|cc)\b""", RegexOption.IGNORE_CASE)
    private val gradeRx = Regex("""(?:grade|sfu)\s*[-:]?\s*(iv|iii|ii|i|[1-5])\b""", RegexOption.IGNORE_CASE)

    /** Text from the first keyword match onward, so "58 x 28 mm ... APD 11 mm" yields 11, not 28. */
    private fun after(line: String, kw: Regex): String = kw.find(line.lowercase(Locale.ROOT))?.let { line.substring(it.range.first) } ?: line

    fun parseUltrasound(lines: List<String>): List<ParsedImaging> {
        val out = mutableListOf<ParsedImaging>()
        var side = "NA"
        var inImpression = false
        for (line in lines) {
            val l = line.lowercase(Locale.ROOT)
            side = sideOf(l, side)
            if (l.startsWith("impression") || l.startsWith("conclusion") || l.startsWith("opinion")) inImpression = true
            if (inImpression) {
                out += ParsedImaging("ULTRASOUND", "NA", ImagingParam.IMPRESSION, null, line, "", line); continue
            }
            if ("kidney" in l && (side == "RIGHT" || side == "LEFT")) {
                sizeRx.find(line)?.let { m ->
                    out += ParsedImaging("ULTRASOUND", side, ImagingParam.KIDNEY_LENGTH, m.groupValues[1].toDoubleOrNull(), m.value, m.groupValues[4].lowercase(), line)
                } ?: run {
                    if ("measur" in l || "length" in l || "size" in l) mmOrCm.find(line)?.let { m ->
                        out += ParsedImaging("ULTRASOUND", side, ImagingParam.KIDNEY_LENGTH, m.groupValues[1].toDoubleOrNull(), m.value, m.groupValues[2].lowercase(), line)
                    }
                }
            }
            val apdKw = Regex("\\b(apd|aprpd|ap diameter|antero-?posterior|pelvis)")
            if (Regex("\\b(apd|aprpd|ap diameter|antero-?posterior)").containsMatchIn(l) || ("pelvis" in l && mmOrCm.containsMatchIn(after(line, apdKw)))) {
                mmOrCm.find(after(line, apdKw))?.let { m -> out += ParsedImaging("ULTRASOUND", side, ImagingParam.APRPD, m.groupValues[1].toDoubleOrNull(), m.value, m.groupValues[2].lowercase(), line) }
            }
            if ("hydronephrosis" in l || "pelvicalyceal" in l || "pcs" in l) {
                gradeRx.find(line)?.let { m -> out += ParsedImaging("ULTRASOUND", side, ImagingParam.HYDRONEPHROSIS_GRADE, null, m.value, "", line) }
            }
            if ("ureter" in l && mmOrCm.containsMatchIn(after(line, Regex("ureter")))) {
                mmOrCm.find(after(line, Regex("ureter")))?.let { m -> out += ParsedImaging("ULTRASOUND", side, ImagingParam.HYDROURETER, m.groupValues[1].toDoubleOrNull(), m.value, m.groupValues[2].lowercase(), line) }
            }
            if ("bladder" in l && "wall" in l) {
                mmOrCm.find(after(line, Regex("wall")))?.let { m -> out += ParsedImaging("ULTRASOUND", "BLADDER", ImagingParam.BLADDER_WALL, m.groupValues[1].toDoubleOrNull(), m.value, m.groupValues[2].lowercase(), line) }
            }
            if ("post void" in l || "post-void" in l || "residual" in l || Regex("\\bpvr\\b").containsMatchIn(l)) {
                mlRx.find(after(line, Regex("post.?void|residual|\\bpvr\\b")))?.let { m -> out += ParsedImaging("ULTRASOUND", "BLADDER", ImagingParam.POST_VOID_RESIDUAL, m.groupValues[1].toDoubleOrNull(), m.value, "ml", line) }
            } else if (("pre void" in l || "pre-void" in l || "prevoid" in l) && "bladder" in l) {
                mlRx.find(line)?.let { m -> out += ParsedImaging("ULTRASOUND", "BLADDER", ImagingParam.PRE_VOID_VOLUME, m.groupValues[1].toDoubleOrNull(), m.value, "ml", line) }
            }
        }
        return out
    }

    private val pctRx = Regex("""\b(right|rt|left|lt)\b[^%\d]{0,40}?(\d{1,3}(?:\.\d{1,2})?)\s*%""", RegexOption.IGNORE_CASE)

    fun parseDmsa(lines: List<String>): List<ParsedImaging> {
        val out = mutableListOf<ParsedImaging>()
        var inImpression = false
        for (line in lines) {
            val l = line.lowercase(Locale.ROOT)
            pctRx.findAll(line).forEach { m ->
                val s = if (m.groupValues[1].lowercase(Locale.ROOT).startsWith("r")) "RIGHT" else "LEFT"
                val v = m.groupValues[2].toDoubleOrNull()
                if (v != null && v <= 100 && out.none { it.param == ImagingParam.SPLIT_FUNCTION && it.side == s }) {
                    out += ParsedImaging("DMSA", s, ImagingParam.SPLIT_FUNCTION, v, m.groupValues[2] + "%", "%", line)
                }
            }
            if ("scar" in l || "photopenic" in l || "cortical defect" in l) {
                out += ParsedImaging("DMSA", sideOf(l, "NA"), ImagingParam.SCARRING, null, line, "", line)
            }
            if (l.startsWith("impression") || l.startsWith("conclusion")) inImpression = true
            if (inImpression) out += ParsedImaging("DMSA", "NA", ImagingParam.IMPRESSION, null, line, "", line)
        }
        return out
    }

    private fun parseImpression(lines: List<String>, modality: String): List<ParsedImaging> {
        val out = mutableListOf<ParsedImaging>()
        var inImpression = false
        for (line in lines) {
            val l = line.lowercase(Locale.ROOT)
            if ("reflux" in l || "vur" in l) gradeRx.find(line)?.let { m ->
                out += ParsedImaging(modality, sideOf(l, "NA"), ImagingParam.VUR_GRADE, null, m.value, "", line)
            }
            if (l.startsWith("impression") || l.startsWith("conclusion")) inImpression = true
            if (inImpression) out += ParsedImaging(modality, "NA", ImagingParam.IMPRESSION, null, line, "", line)
        }
        return out
    }
}
