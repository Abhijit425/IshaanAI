package com.ishaan.ai.domain

import com.ishaan.ai.data.db.EventCategory
import com.ishaan.ai.data.db.ImagingParam
import com.ishaan.ai.data.db.LabTest
import com.ishaan.ai.data.db.Verification
import java.util.Locale

data class AssistantAnswer(
    val text: String,
    val sources: List<String> = emptyList(),
    val mode: String = "RULES",          // RULES or LOCAL_MODEL
    val reportKind: ReportKind? = null,  // set when the answer offers a full report
)

/**
 * "Ishaan Health Assistant" — rule-based mode.
 * Matches the question to a known intent and answers ONLY from stored records, with dates and sources.
 * If nothing is stored, it says the information is missing. It never produces medical advice.
 */
class RuleAssistant(private val engine: AnalysisEngine) {

    fun answer(question: String, s: HealthSnapshot): AssistantAnswer {
        val q = question.lowercase(Locale.ROOT).trim()
        if (q.isBlank()) return help()
        if (safetyRedirect(q) != null) return AssistantAnswer(safetyRedirect(q)!!)

        val test = matchTest(q)
        return when {
            has(q, "egfr", "gfr", "kidney function estimate") -> egfr(s)
            has(q, "question") && has(q, "doctor", "nephrolog", "urolog", "ask") -> questions(s)
            has(q, "summary", "summarise", "summarize", "prepare") && has(q, "doctor", "visit", "consult", "appointment", "tomorrow") ->
                AssistantAnswer("I can prepare the doctor consultation report from all stored records. Tap \"Open report\" to view it or export it as a PDF.", reportKind = ReportKind.DOCTOR)
            has(q, "complete summary", "full summary", "medical summary", "whole history") -> AssistantAnswer("Here is the complete medical summary built from stored records.", reportKind = ReportKind.COMPLETE)
            has(q, "surgery", "surgeries", "operation", "fulguration", "ablation", "cystoscopy", "procedure") -> surgeries(s)
            has(q, "dmsa") -> imaging(s, "DMSA", q)
            has(q, "ultrasound", "usg", "sonography", "scan") -> imaging(s, "ULTRASOUND", q)
            has(q, "culture") -> cultures(s, q)
            has(q, "medicine", "medication", "drug", "prescri", "tablet", "syrup", "antibiotic") -> meds(s, q)
            has(q, "weight", "height", "length", "growth", "head circ") -> growth(s, q)
            has(q, "admission", "admitted", "hospitali", "nicu", "icu") -> admissions(s)
            has(q, "infection", "uti", "sepsis") -> infections(s)
            has(q, "appointment", "next visit", "follow up", "follow-up") -> appointments(s)
            has(q, "urine", "pee", "stream", "flow") && test == null -> urine(s)
            has(q, "potty", "stool", "poo", "constipat", "diarr") -> stool(s)
            has(q, "missing", "incomplete", "what else") -> missing(s)
            test != null -> lab(s, test, q)
            has(q, "help", "what can you") -> help()
            else -> AssistantAnswer(
                "I couldn't match that question to the stored records. I answer from Ishaan's records only — try asking about creatinine, ultrasound, DMSA, surgeries, medicines, urine cultures, weight, or \"prepare a summary for the doctor\".",
            )
        }
    }

    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    private fun safetyRedirect(q: String): String? {
        val medWord = has(q, "dose", "dosage", "medicine", "medication", "syrup", "tablet", "antibiotic", "drops", "mg")
        val action = has(q, "should i", "can i", "how much", "increase", "decrease", "reduce", "stop", "double", "skip", "change the")
        if ((medWord && action) || has(q, "how much should i give")) {
            return "I can't advise on starting, stopping or changing medicines or doses. Please ask Ishaan's doctor or pharmacist. " +
                "I can show the doses exactly as recorded from his prescriptions — ask \"what medicines is he on?\""
        }
        if (has(q, "is he ok", "is it serious", "diagnose", "does he have kidney", "does he have an infection", "does he have a uti", "is this normal", "is it normal", "kidney failure", "ckd stage")) {
            return "I can't diagnose or tell whether something is normal. I can show what is recorded and how values changed over time, so you can discuss it with Ishaan's doctor. " +
                "If he seems unwell, cannot pass urine, has trouble breathing or is unusually drowsy, seek emergency care now."
        }
        return null
    }

    private fun matchTest(q: String): LabTest? {
        val all = LabTest.entries.filter { it != LabTest.OTHER }.flatMap { t -> (t.aliases + t.label.lowercase()).map { it to t } }
            .filter { it.first.length >= 3 }.sortedByDescending { it.first.length }
        return all.firstOrNull { q.contains(it.first) }?.second
    }

    private fun lab(s: HealthSnapshot, t: LabTest, q: String): AssistantAnswer {
        val series = s.labSeries(t)
        if (series.isEmpty()) return AssistantAnswer("No ${t.label} results are stored yet. Upload the report in the Reports tab or add the value manually.")
        val wantAll = has(q, "trend", "all", "history", "compare", "change", "progress", "graph")
        val lines = if (wantAll) series else listOf(series.last())
        val text = buildString {
            if (!wantAll) {
                val r = series.last()
                append("Latest ${t.label}: ${r.valueText} ${r.unit} on ${Fmt.date(r.sampleDate)}.")
                if (r.refText.isNotBlank()) append(" Reference printed on that report: ${r.refText}.")
                append(" Status: ${Fmt.verificationLabel(r.verification)}.")
                if (series.size > 1) {
                    val p = series[series.size - 2]
                    append(" Previous: ${p.valueText} ${p.unit} on ${Fmt.date(p.sampleDate)}.")
                }
            } else {
                appendLine("${t.label} — ${series.size} result(s):")
                lines.forEach { appendLine("• ${Fmt.date(it.sampleDate)}: ${it.valueText} ${it.unit}".trimEnd() + (if (it.verification == Verification.UNVERIFIED) " (OCR, unverified)" else "")) }
                val f = engine.labTrendSection(s, listOf(t), null, "").findings.firstOrNull()
                f?.let { append(it.text) }
            }
            append("\n\nA change in a value is a trend, not a diagnosis — please discuss its meaning with Ishaan's doctor.")
        }
        return AssistantAnswer(text, lines.map { src(it.reportId, it.sampleDate, it.verification) })
    }

    private fun src(reportId: Long?, date: Long, v: String) = (if (reportId != null) "Report #$reportId" else "Manual entry") + ", ${Fmt.date(date)}, ${Fmt.verificationLabel(v)}"

    private fun egfr(s: HealthSnapshot): AssistantAnswer {
        val sec = engine.egfrSection(s)
        return AssistantAnswer((sec.findings.joinToString("\n") { "• " + it.text } + "\n\n" + (sec.note ?: "")).trim())
    }

    private fun imaging(s: HealthSnapshot, modality: String, q: String): AssistantAnswer {
        val f = s.imaging.filter { it.modality == modality }
        val name = if (modality == "DMSA") "DMSA" else "ultrasound"
        if (f.isEmpty()) return AssistantAnswer("No $name findings are stored yet. Upload the $name report in the Reports tab and confirm the extracted findings.")
        val dates = f.map { Fmt.toLocalDate(it.studyDate) }.distinct().sorted()
        val pick = if (dates.size >= 2 && has(q, "first")) listOf(dates.first(), dates.last()) else dates.takeLast(2)
        val text = buildString {
            appendLine(if (pick.size == 2) "Comparing $name of ${Fmt.date(pick[0])} with ${Fmt.date(pick[1])}:" else "$name of ${Fmt.date(pick[0])} (only one study stored):")
            val keys = f.map { it.side to it.param }.distinct()
            for ((side, param) in keys) {
                val vals = pick.map { d -> f.filter { it.side == side && it.param == param && Fmt.toLocalDate(it.studyDate) == d }.joinToString("; ") { v -> v.valueNumeric?.let { n -> "${Fmt.num(n)} ${v.unit}".trim() } ?: v.valueText } }
                if (vals.all { it.isBlank() }) continue
                val sideTxt = when (side) { "RIGHT" -> "Right"; "LEFT" -> "Left"; "BLADDER" -> "Bladder"; else -> "" }
                appendLine("• ${listOf(sideTxt, ImagingParam.of(param).label).filter { it.isNotBlank() }.joinToString(" – ")}: ${vals.joinToString(" → ") { it.ifBlank { "not reported" } }}")
            }
            if (f.any { it.verification == Verification.UNVERIFIED }) appendLine("Some values were read by OCR and are not yet verified.")
            append("These are the findings as written in the reports. What a difference means clinically should be interpreted by Ishaan's doctor.")
        }
        return AssistantAnswer(text, pick.map { "$name study ${Fmt.date(it)}" })
    }

    private fun surgeries(s: HealthSnapshot): AssistantAnswer {
        val catheter = s.events.filter { it.category in setOf(EventCategory.CATHETER_INSERTED.name, EventCategory.CATHETER_REMOVED.name, EventCategory.CYSTOSCOPY.name, EventCategory.FULGURATION.name, EventCategory.SURGERY.name) }
        if (s.surgeries.isEmpty() && catheter.isEmpty()) return AssistantAnswer("No surgeries or procedures are recorded yet. Add them in Medical History → Surgeries.")
        val items = s.surgeries.map { Triple(it.dateTime, it.procedureName, listOf(it.hospital, it.surgeon, it.outcome).filter { x -> x.isNotBlank() }.joinToString(" · ")) } +
            catheter.map { Triple(it.dateTime, it.title, EventCategory.of(it.category).label) }
        val text = "Procedure history (${items.size}):\n" + items.sortedBy { it.first }.joinToString("\n") { "• ${Fmt.date(it.first)} — ${it.second}" + if (it.third.isNotBlank()) " (${it.third})" else "" }
        return AssistantAnswer(text, listOf("Medical history, entered by parent"))
    }

    private fun cultures(s: HealthSnapshot, q: String): AssistantAnswer {
        val c = s.labSeries(LabTest.URINE_CULTURE)
        if (c.isEmpty()) return AssistantAnswer("No urine culture results are stored yet.")
        val n = Regex("\\b(\\d+)\\b").find(q)?.value?.toIntOrNull() ?: if (has(q, "last three", "last 3")) 3 else c.size
        val pick = c.takeLast(n.coerceIn(1, c.size))
        return AssistantAnswer("Urine culture results (${pick.size} of ${c.size}):\n" + pick.joinToString("\n") { "• ${Fmt.date(it.sampleDate)}: ${it.valueText}" + (if (it.notes.isNotBlank()) " — ${it.notes}" else "") + (if (it.verification == Verification.UNVERIFIED) " (OCR, unverified)" else "") }, pick.map { src(it.reportId, it.sampleDate, it.verification) })
    }

    private fun meds(s: HealthSnapshot, q: String): AssistantAnswer {
        if (s.medications.isEmpty()) return AssistantAnswer("No medicines are recorded yet.")
        val lastMonth = has(q, "last month", "past month", "this month")
        val list = if (lastMonth) s.medications.filter { (it.startDate ?: it.createdAt) >= s.now - 31L * 86_400_000L } else if (has(q, "all", "history", "ever", "past")) s.medications else s.activeMeds
        if (list.isEmpty()) return AssistantAnswer(if (lastMonth) "No medicines were recorded as started in the last month." else "No current medicines are recorded.")
        val text = (if (lastMonth) "Medicines started in the last month:\n" else if (list === s.activeMeds) "Current medicines (exactly as prescribed):\n" else "All recorded medicines:\n") +
            list.joinToString("\n") { "• ${it.name} ${it.strength} — ${it.prescribedDose} ${it.doseUnit}, ${it.frequency}".replace("  ", " ") + " (from ${Fmt.date(it.startDate)}${if (!it.active) ", stopped ${Fmt.date(it.discontinuedDate ?: it.endDate)}" else ""})" } +
            "\n\nI never suggest dose changes; ask the doctor or pharmacist about any change."
        return AssistantAnswer(text, listOf("Medication records, entered by parent"))
    }

    private fun growth(s: HealthSnapshot, q: String): AssistantAnswer {
        val key: (com.ishaan.ai.data.db.GrowthRecord) -> Double? = when {
            has(q, "height", "length") -> { r -> r.lengthCm }
            has(q, "head") -> { r -> r.headCm }
            else -> { r -> r.weightKg }
        }
        val unit = when { has(q, "height", "length") -> "cm"; has(q, "head") -> "cm"; else -> "kg" }
        val name = when { has(q, "height", "length") -> "Length/height"; has(q, "head") -> "Head circumference"; else -> "Weight" }
        val g = s.growth.filter { key(it) != null }.sortedBy { it.date }
        if (g.isEmpty()) return AssistantAnswer("No $name measurements are stored yet. Add them in Monitoring → Growth.")
        val sec = engine.growthSection(s, null)
        return AssistantAnswer("$name progression (${g.size} measurements):\n" + g.joinToString("\n") { "• ${Fmt.date(it.date)}: ${Fmt.num(key(it))} $unit (${it.measuredAt.lowercase()})" } +
            "\n\n" + sec.findings.joinToString("\n") { it.text }, listOf("Growth records"))
    }

    private fun admissions(s: HealthSnapshot) = if (s.admissions.isEmpty()) AssistantAnswer("No hospital admissions are recorded.") else
        AssistantAnswer("Hospital admissions:\n" + s.admissions.joinToString("\n") { "• ${Fmt.date(it.admitDate)}${it.dischargeDate?.let { d -> " – ${Fmt.date(d)}" } ?: ""}: ${it.hospital} — ${it.reason}" + if (it.intensiveCare != "NONE") " (${it.intensiveCare})" else "" })

    private fun infections(s: HealthSnapshot): AssistantAnswer {
        val ev = s.events.filter { it.category in setOf(EventCategory.UTI.name, EventCategory.BLOOD_INFECTION.name, EventCategory.ANTIBIOTIC_COURSE.name) }
        if (ev.isEmpty() && s.labSeries(LabTest.URINE_CULTURE).isEmpty()) return AssistantAnswer("No infections or cultures are recorded.")
        return AssistantAnswer("Infection-related records:\n" + ev.joinToString("\n") { "• ${Fmt.date(it.dateTime)} — ${EventCategory.of(it.category).label}: ${it.title}" } +
            s.labSeries(LabTest.URINE_CULTURE).joinToString("") { "\n• ${Fmt.date(it.sampleDate)} — Urine culture: ${it.valueText}" })
    }

    private fun appointments(s: HealthSnapshot): AssistantAnswer {
        val up = s.appointments.filter { !it.completed && it.dateTime >= s.now }
        if (up.isEmpty()) return AssistantAnswer("No upcoming appointments are recorded.")
        return AssistantAnswer("Upcoming appointments:\n" + up.joinToString("\n") { "• ${Fmt.dateTime(it.dateTime)} — ${it.specialty} ${it.doctorName} at ${it.hospital}".replace("  ", " ") + (if (it.testsToDo.isNotBlank()) ". Tests: ${it.testsToDo}" else "") })
    }

    private fun urine(s: HealthSnapshot): AssistantAnswer {
        val sec = engine.generate(ReportKind.WEEKLY, s).sections.firstOrNull { it.title == "Urine observations" }
            ?: return AssistantAnswer("No urine observations in the last 7 days.")
        return AssistantAnswer("Urine observations, last 7 days:\n" + sec.findings.joinToString("\n") { "• ${it.text}" } + "\n\n" + (sec.note ?: ""))
    }

    private fun stool(s: HealthSnapshot): AssistantAnswer {
        val sec = engine.generate(ReportKind.WEEKLY, s).sections.firstOrNull { it.title.startsWith("Potty") }
            ?: return AssistantAnswer("No stool observations in the last 7 days.")
        return AssistantAnswer("Stool observations, last 7 days:\n" + sec.findings.joinToString("\n") { "• ${it.text}" })
    }

    private fun questions(s: HealthSnapshot) = AssistantAnswer(
        "Questions you could ask, based on the stored records:\n" + engine.suggestedQuestions(s).joinToString("\n") { "• $it" },
        reportKind = ReportKind.DOCTOR,
    )

    private fun missing(s: HealthSnapshot): AssistantAnswer {
        val m = engine.missingInfo(s)
        return AssistantAnswer(if (m.isEmpty()) "I didn't find obvious gaps in the records." else "Missing or incomplete information:\n" + m.joinToString("\n") { "• $it" })
    }

    private fun help() = AssistantAnswer(
        "I answer from Ishaan's stored records only. Try:\n• What is Ishaan's latest creatinine?\n• Show creatinine trend\n• Compare his first and latest ultrasound\n• What changed between the DMSA reports?\n" +
            "• Show his surgery history\n• Which medicines were prescribed last month?\n• Summarise his last three urine cultures\n• Show his weight progression\n• What questions should I ask the nephrologist?\n• Prepare a summary for tomorrow's doctor visit\n• What information is missing?",
    )
}
