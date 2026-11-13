package com.ishaan.ai.domain

import com.ishaan.ai.data.db.*
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

enum class Flag { NONE, INFO, REVIEW, URGENT }

data class Finding(val text: String, val date: Long? = null, val basis: String = "", val flag: Flag = Flag.NONE)
data class Table(val headers: List<String>, val rows: List<List<String>>)
data class Section(val title: String, val findings: List<Finding> = emptyList(), val table: Table? = null, val note: String? = null)

enum class ReportKind(val title: String) {
    DAILY("Daily health summary"),
    WEEKLY("Weekly health report"),
    MONTHLY("Monthly health report"),
    COMPLETE("Complete medical summary"),
    DOCTOR("Doctor consultation report"),
}

data class HealthReport(
    val kind: ReportKind,
    val childName: String,
    val generatedAt: Long,
    val periodStart: Long?,
    val sections: List<Section>,
    val missing: List<String>,
    val questions: List<String>,
) {
    fun asPlainText(): String = buildString {
        appendLine("${kind.title} — $childName")
        appendLine("Generated ${Fmt.dateTime(generatedAt)}" + (periodStart?.let { " · covering from ${Fmt.date(it)}" } ?: ""))
        sections.forEach { s ->
            appendLine(); appendLine("## ${s.title}")
            s.note?.let { appendLine(it) }
            s.findings.forEach { f -> appendLine("- " + (if (f.flag == Flag.URGENT) "[URGENT] " else if (f.flag == Flag.REVIEW) "[Review] " else "") + f.text + (if (f.basis.isNotBlank()) " (${f.basis})" else "")) }
            s.table?.let { t -> appendLine(t.headers.joinToString(" | ")); t.rows.forEach { appendLine(it.joinToString(" | ")) } }
        }
        if (missing.isNotEmpty()) { appendLine(); appendLine("## Missing or incomplete information"); missing.forEach { appendLine("- $it") } }
        if (questions.isNotEmpty()) { appendLine(); appendLine("## Questions for the doctor"); questions.forEach { appendLine("- $it") } }
        appendLine(); appendLine(AnalysisEngine.DISCLAIMER)
    }
}

/**
 * Transparent, rule-based analysis. Every statement is traceable to a stored record with a date
 * and a source label. It describes what was recorded and how numbers changed; it never diagnoses,
 * scores kidney health, or suggests medicine changes.
 */
class AnalysisEngine(private val who: WhoGrowth?) {

    companion object {
        const val DISCLAIMER =
            "This report was generated on the phone by rule-based analysis of records the parent entered or uploaded. " +
                "It is not a diagnosis. A trend only describes how recorded values changed over time; whether a change matters " +
                "clinically must be judged by Ishaan's doctors. Values marked 'OCR – not yet verified' were read automatically " +
                "from a document and may be wrong — check them against the original report."
        const val RANGE_NOTE =
            "Reference ranges shown are exactly as printed by the lab on each report. Many labs print adult ranges; the app does not " +
                "apply its own ranges to a child. Ask the doctor which range applies to Ishaan."
        private const val DAY = 86_400_000L
    }

    fun generate(kind: ReportKind, s: HealthSnapshot, parentQuestions: List<String> = emptyList()): HealthReport {
        val since = when (kind) {
            ReportKind.DAILY -> s.now - DAY
            ReportKind.WEEKLY -> s.now - 7 * DAY
            ReportKind.MONTHLY -> s.now - 30 * DAY
            else -> null
        }
        val sections = mutableListOf<Section>()
        sections += profileSection(s)
        when (kind) {
            ReportKind.DAILY -> {
                sections += newRecordsSection(s, since!!)
                sections += adherenceSection(s, since)
                sections += symptomsSection(s, since)
                sections += urineSection(s, since)
                sections += stoolSection(s, since)
                sections += recentLabsSection(s, since)
            }
            ReportKind.WEEKLY -> {
                sections += growthSection(s, since)
                sections += labTrendSection(s, listOf(LabTest.CREATININE), null, "Creatinine (all results)")
                sections += recentLabsSection(s, since!!)
                sections += urineSection(s, since)
                sections += stoolSection(s, since)
                sections += symptomsSection(s, since)
                sections += adherenceSection(s, since)
                sections += medicationChangesSection(s, since)
                sections += eventsSection(s, since, "New medical events this week")
            }
            ReportKind.MONTHLY -> {
                sections += labTrendSection(s, LabTest.kidneyPanel, null, "Kidney-related laboratory trends")
                sections += labTrendSection(s, LabTest.urinePanel, null, "Urine test results")
                sections += imagingSections(s)
                sections += growthSection(s, since)
                sections += medicationsSection(s)
                sections += medicationChangesSection(s, since!!)
                sections += adherenceSection(s, since)
                sections += admissionsSection(s, since)
                sections += symptomsSection(s, since)
                sections += urineSection(s, since)
                sections += stoolSection(s, since)
            }
            ReportKind.COMPLETE, ReportKind.DOCTOR -> {
                sections += diagnosisSection(s)
                sections += surgerySection(s)
                sections += admissionsSection(s, null)
                sections += infectionSection(s)
                sections += labTrendSection(s, LabTest.kidneyPanel, null, "Kidney-related laboratory trends")
                sections += egfrSection(s)
                sections += labTrendSection(s, LabTest.urinePanel, null, "Urine tests and cultures")
                sections += labTrendSection(s, listOf(LabTest.BP_SYSTOLIC, LabTest.BP_DIASTOLIC), null, "Blood pressure")
                sections += imagingSections(s)
                sections += medicationsSection(s)
                sections += medicationHistorySection(s)
                sections += growthSection(s, null)
                sections += symptomsSection(s, s.now - 30 * DAY)
                sections += urineSection(s, s.now - 30 * DAY)
                sections += stoolSection(s, s.now - 30 * DAY)
                if (kind == ReportKind.COMPLETE) sections += timelineSection(s)
                sections += appointmentsSection(s)
            }
        }
        val questions = if (kind == ReportKind.DOCTOR || kind == ReportKind.MONTHLY || kind == ReportKind.COMPLETE)
            parentQuestions.filter { it.isNotBlank() } + suggestedQuestions(s) else emptyList()
        return HealthReport(kind, s.childName, s.now, since, sections.filter { it.findings.isNotEmpty() || it.table != null || it.note != null }, missingInfo(s), questions.distinct())
    }

    // ------------------------------------------------------------------ sections

    private fun profileSection(s: HealthSnapshot): Section {
        val p = s.profile ?: return Section("Patient profile", note = "No profile has been entered yet.")
        val f = mutableListOf<Finding>()
        f += Finding("Name: ${p.name}")
        f += Finding("Date of birth: ${Fmt.date(s.dob)} (age ${Fmt.age(s.dob)})")
        if (p.primaryDiagnosis.isNotBlank()) f += Finding("Primary diagnosis: ${p.primaryDiagnosis}")
        p.birthWeightKg?.let { f += Finding("Birth weight: ${Fmt.num(it)} kg") }
        p.gestationWeeks?.let { f += Finding("Gestation at birth: $it weeks") }
        if (p.bloodGroup.isNotBlank()) f += Finding("Blood group: ${p.bloodGroup}")
        val w = s.latestGrowthWith { it.weightKg }; val h = s.latestGrowthWith { it.lengthCm }
        w?.let { f += Finding("Latest weight: ${Fmt.num(it.weightKg)} kg", it.date, if (it.measuredAt == "CLINIC") "measured at clinic" else "measured at home") }
        h?.let { f += Finding("Latest length/height: ${Fmt.num(it.lengthCm)} cm", it.date, if (it.measuredAt == "CLINIC") "measured at clinic" else "measured at home") }
        f += Finding("Surgeries/procedures recorded: ${s.surgeries.size}")
        return Section("Patient profile", f)
    }

    private fun diagnosisSection(s: HealthSnapshot) = Section(
        "Diagnoses",
        s.diagnoses.map { Finding("${it.name} — ${it.status.lowercase()}" + (if (it.details.isNotBlank()) ": ${it.details}" else "") + (if (it.diagnosedBy.isNotBlank()) " (by ${it.diagnosedBy})" else ""), it.dateDiagnosed, "entered by parent") },
    )

    private fun surgerySection(s: HealthSnapshot) = Section(
        "Surgery and procedure timeline",
        table = if (s.surgeries.isEmpty()) null else Table(
            listOf("Date", "Procedure", "Hospital / surgeon", "Findings / outcome"),
            s.surgeries.sortedBy { it.dateTime }.map {
                listOf(Fmt.date(it.dateTime), it.procedureName, listOf(it.hospital, it.surgeon).filter { x -> x.isNotBlank() }.joinToString(" / "), listOf(it.findings, it.outcome).filter { x -> x.isNotBlank() }.joinToString("; "))
            },
        ),
        findings = s.events.filter { it.category in setOf(EventCategory.CATHETER_INSERTED.name, EventCategory.CATHETER_REMOVED.name) }
            .map { Finding("${EventCategory.of(it.category).label}: ${it.title}", it.dateTime, "entered by parent") },
    )

    private fun admissionsSection(s: HealthSnapshot, since: Long?): Section {
        val list = s.admissions.filter { since == null || it.admitDate >= since || (it.dischargeDate ?: s.now) >= since }
        return Section("Hospital admissions", list.map {
            val stay = it.dischargeDate?.let { d -> " to ${Fmt.date(d)}" } ?: " (no discharge date recorded)"
            Finding("${it.hospital.ifBlank { "Hospital not recorded" }}: ${Fmt.date(it.admitDate)}$stay. Reason: ${it.reason.ifBlank { "not recorded" }}" +
                (if (it.intensiveCare != "NONE") " · ${it.intensiveCare} care" else "") + (if (it.treatment.isNotBlank()) " · ${it.treatment}" else ""), it.admitDate, "entered by parent")
        })
    }

    private fun infectionSection(s: HealthSnapshot): Section {
        val cats = setOf(EventCategory.UTI.name, EventCategory.BLOOD_INFECTION.name, EventCategory.ANTIBIOTIC_COURSE.name)
        val ev = s.events.filter { it.category in cats }.sortedBy { it.dateTime }
        val cultures = s.labSeries(LabTest.URINE_CULTURE)
        val f = ev.map { Finding("${EventCategory.of(it.category).label}: ${it.title}" + (if (it.description.isNotBlank()) " — ${it.description}" else ""), it.dateTime, "entered by parent") } +
            cultures.map { Finding("Urine culture: ${it.valueText}", it.sampleDate, Fmt.verificationLabel(it.verification)) }
        return Section("Infections and antibiotic courses", f.sortedBy { it.date })
    }

    fun labTrendSection(s: HealthSnapshot, tests: List<LabTest>, since: Long?, title: String): Section {
        val rows = mutableListOf<List<String>>()
        val findings = mutableListOf<Finding>()
        for (t in tests) {
            val series = s.labSeries(t).filter { since == null || it.sampleDate >= since }
            if (series.isEmpty()) continue
            var prev: ExtractedLabResult? = null
            for (r in series) {
                rows += listOf(t.label, Fmt.date(r.sampleDate), "${r.valueText} ${r.unit}".trim(), changeText(prev, r), r.refText.ifBlank { "not printed" }, shortStatus(r))
                prev = r
            }
            val numeric = series.filter { it.valueNumeric != null }
            if (numeric.size >= 2 && t.isNumericTrendable) {
                val first = numeric.first(); val last = numeric.last()
                if (first.unit.equals(last.unit, true)) {
                    val fv = first.valueNumeric!!; val lv = last.valueNumeric!!
                    val delta = lv - fv
                    val pct = if (fv != 0.0) delta / fv * 100 else null
                    val dir = when { abs(delta) < 1e-9 -> "the same as"; delta > 0 -> "higher than"; else -> "lower than" }
                    findings += Finding(
                        "${t.label}: latest ${Fmt.num(last.valueNumeric)} ${last.unit} (${Fmt.date(last.sampleDate)}) is $dir the first recorded ${Fmt.num(first.valueNumeric)} ${first.unit} (${Fmt.date(first.sampleDate)})" +
                            (pct?.let { p -> ", a change of ${String.format(Locale.ENGLISH, "%+.0f", p)}%" } ?: "") + ". ${numeric.size} results in total.",
                        last.sampleDate, "trend across stored results",
                    )
                }
            }
            series.filter { outsidePrinted(it) }.lastOrNull()?.let {
                findings += Finding("${t.label} ${it.valueText} ${it.unit} on ${Fmt.date(it.sampleDate)} is outside the reference range printed on that report (${it.refText}). Discuss with the doctor whether this range applies to Ishaan.", it.sampleDate, Fmt.verificationLabel(it.verification), Flag.REVIEW)
            }
            series.filter { it.verification == Verification.UNVERIFIED }.takeIf { it.isNotEmpty() }?.let {
                findings += Finding("${it.size} ${t.label} value(s) were read by OCR and are not yet verified.", null, "OCR", Flag.INFO)
            }
        }
        return Section(
            title, findings,
            table = if (rows.isEmpty()) null else Table(listOf("Test", "Date", "Result", "Change vs previous", "Reference (as printed)", "Status"), rows),
            note = if (rows.isEmpty()) null else RANGE_NOTE,
        )
    }

    private fun recentLabsSection(s: HealthSnapshot, since: Long): Section {
        val recent = s.labs.filter { it.sampleDate >= since || it.createdAt >= since }.sortedBy { it.sampleDate }
        return Section("Test results added in this period", recent.map {
            Finding("${LabTest.of(it.testCode).let { t -> if (t == LabTest.OTHER) it.displayName else t.label }}: ${it.valueText} ${it.unit}".trim() + (if (it.refText.isNotBlank()) " (printed reference ${it.refText})" else ""), it.sampleDate, Fmt.verificationLabel(it.verification))
        })
    }

    private fun changeText(prev: ExtractedLabResult?, cur: ExtractedLabResult): String {
        val a = prev?.valueNumeric ?: return "—"
        val b = cur.valueNumeric ?: return "—"
        if (!prev!!.unit.equals(cur.unit, true)) return "units differ — not compared"
        val d = b - a
        val pct = if (a != 0.0) String.format(Locale.ENGLISH, " (%+.0f%%)", d / a * 100) else ""
        return (if (d >= 0) "+" else "") + Fmt.num(d) + pct
    }

    private fun shortStatus(r: ExtractedLabResult) = when (r.verification) {
        Verification.UNVERIFIED -> "OCR, unverified"
        Verification.VERIFIED -> "verified"
        Verification.CORRECTED -> "corrected"
        else -> "parent-entered"
    }

    private fun outsidePrinted(r: ExtractedLabResult): Boolean {
        val v = r.valueNumeric ?: return false
        return (r.refHigh != null && v > r.refHigh) || (r.refLow != null && v < r.refLow)
    }

    fun egfrSection(s: HealthSnapshot): Section {
        val cr = s.labSeries(LabTest.CREATININE).lastOrNull { it.valueNumeric != null }
            ?: return Section("Estimated GFR", note = "Not calculated: no numeric serum creatinine recorded.")
        val crDate = Fmt.toLocalDate(cr.sampleDate)
        val h = s.growth.filter { it.lengthCm != null }.minByOrNull { abs(it.date - cr.sampleDate) }
        val res = BedsideSchwartz.compute(
            s.dob, cr.valueNumeric, cr.unit, crDate, cr.verification != Verification.UNVERIFIED,
            h?.lengthCm, h?.date?.let { Fmt.toLocalDate(it) },
        )
        return when (res) {
            is BedsideSchwartz.Result.Value -> Section(
                "Estimated GFR",
                listOf(Finding("eGFR ≈ ${Fmt.num(Math.round(res.egfr * 10) / 10.0)} mL/min/1.73m² using ${BedsideSchwartz.NAME}: 0.413 × ${Fmt.num(res.heightCm)} cm ÷ ${Fmt.num(res.creatinineMgDl)} mg/dL. " + res.notes.joinToString(" "), cr.sampleDate, "calculated from stored creatinine and height")),
                note = BedsideSchwartz.LIMITATIONS,
            )
            is BedsideSchwartz.Result.NotCalculated -> Section("Estimated GFR", res.reasons.map { Finding(it) }, note = "eGFR was not calculated. The app never estimates kidney function from creatinine alone.")
        }
    }

    fun imagingSections(s: HealthSnapshot): List<Section> = listOf("ULTRASOUND", "DMSA", "MCU").mapNotNull { m ->
        val f = s.imaging.filter { it.modality == m }
        if (f.isEmpty()) return@mapNotNull null
        val dates = f.map { Fmt.toLocalDate(it.studyDate) }.distinct().sorted()
        val shown = if (dates.size > 4) listOf(dates.first()) + dates.takeLast(3) else dates
        val keys = f.filter { it.parameter != ImagingParam.IMPRESSION.name }.map { it.side to it.parameter }.distinct()
            .sortedWith(compareBy({ sideOrder(it.first) }, { ImagingParam.of(it.second).ordinal }))
        val rows = keys.map { (side, param) ->
            listOf(sideLabel(side), ImagingParam.of(param).label) + shown.map { d ->
                f.filter { it.side == side && it.parameter == param && Fmt.toLocalDate(it.studyDate) == d }
                    .joinToString("; ") { v -> (v.valueNumeric?.let { n -> "${Fmt.num(n)} ${v.unit}".trim() } ?: v.valueText) + if (v.verification == Verification.UNVERIFIED) " *" else "" }
                    .ifBlank { "—" }
            }
        }
        val impressions = f.filter { it.parameter == ImagingParam.IMPRESSION.name }.sortedBy { it.studyDate }
            .map { Finding("Impression (${Fmt.date(it.studyDate)}): ${it.valueText}", it.studyDate, Fmt.verificationLabel(it.verification)) }
        val title = when (m) { "ULTRASOUND" -> "Ultrasound comparison"; "DMSA" -> "DMSA comparison"; else -> "MCU / VCUG findings" }
        Section(
            title, impressions,
            Table(listOf("Side", "Finding") + shown.map { Fmt.date(it) }, rows),
            note = "Imaging findings are shown as reported by the radiologist/nuclear medicine report and are kept separate from laboratory values. * = OCR, not yet verified." +
                (if (dates.size > 4) " Showing the first study and the 3 most recent of ${dates.size}." else ""),
        )
    }

    private fun sideOrder(s: String) = when (s) { "RIGHT" -> 0; "LEFT" -> 1; "BLADDER" -> 2; else -> 3 }
    private fun sideLabel(s: String) = when (s) { "RIGHT" -> "Right"; "LEFT" -> "Left"; "BLADDER" -> "Bladder"; "BOTH" -> "Both"; else -> "—" }

    private fun medicationsSection(s: HealthSnapshot) = Section(
        "Current medicines (as prescribed)",
        s.activeMeds.map { m ->
            val times = s.schedules[m.id].orEmpty().filter { it.enabled }.joinToString(", ") { Fmt.minuteOfDay(it.minuteOfDay) }
            Finding(
                "${m.name}${if (m.genericName.isNotBlank()) " (${m.genericName})" else ""} ${m.strength} — ${m.prescribedDose} ${m.doseUnit}, ${m.frequency}".replace("  ", " ") +
                    (if (times.isNotBlank()) " · reminders: $times" else "") + (if (m.prescribingDoctor.isNotBlank()) " · prescribed by ${m.prescribingDoctor}" else "") +
                    (if (m.needsConfirmation) " · PRESCRIPTION MARKED UNCLEAR — confirm with doctor/pharmacist" else ""),
                m.startDate, "entered by parent from prescription", if (m.needsConfirmation) Flag.REVIEW else Flag.NONE,
            )
        },
        note = "Doses are shown exactly as entered from the prescription. The app never calculates or changes doses.",
    )

    private fun medicationHistorySection(s: HealthSnapshot) = Section(
        "Medication history",
        table = if (s.medications.isEmpty()) null else Table(
            listOf("Medicine", "Dose (as prescribed)", "Started", "Stopped", "Reason stopped"),
            s.medications.sortedBy { it.startDate ?: it.createdAt }.map {
                listOf(it.name, "${it.prescribedDose} ${it.doseUnit} ${it.frequency}".trim(), Fmt.date(it.startDate), if (it.active) "ongoing" else Fmt.date(it.discontinuedDate ?: it.endDate), it.discontinuedReason)
            },
        ),
    )

    private fun medicationChangesSection(s: HealthSnapshot, since: Long) = Section(
        "Medicine changes in this period",
        s.medications.filter { (it.startDate ?: 0) >= since }.map { Finding("Started ${it.name} — ${it.prescribedDose} ${it.doseUnit} ${it.frequency}".trim(), it.startDate, "entered by parent") } +
            s.medications.filter { (it.discontinuedDate ?: 0) >= since }.map { Finding("Stopped ${it.name}" + (if (it.discontinuedReason.isNotBlank()) ": ${it.discontinuedReason}" else ""), it.discontinuedDate, "entered by parent") },
    )

    private fun adherenceSection(s: HealthSnapshot, since: Long): Section {
        val logs = s.medLogs.filter { it.actionTime >= since }
        if (logs.isEmpty()) return Section("Medication log", note = if (s.activeMeds.isNotEmpty()) "No doses were logged in this period." else null)
        val byMed = logs.groupBy { it.medicationId }
        return Section("Medication log", byMed.map { (id, l) ->
            val name = s.medications.firstOrNull { it.id == id }?.name ?: "Medicine #$id"
            val given = l.count { it.status == "GIVEN" }; val missed = l.count { it.status == "MISSED" }; val skipped = l.count { it.status == "SKIPPED" }
            Finding("$name: $given given, $missed missed, $skipped skipped (as logged)", null, "parent log", if (missed > 0) Flag.INFO else Flag.NONE)
        })
    }

    private fun urineSection(s: HealthSnapshot, since: Long): Section {
        val l = s.urine.filter { it.dateTime >= since }
        if (l.isEmpty()) return Section("Urine observations", note = "No urine observations logged in this period.")
        val days = maxOf(1.0, (s.now - since) / DAY.toDouble())
        val f = mutableListOf<Finding>()
        f += Finding("${l.size} urine observations logged (about ${String.format(Locale.ENGLISH, "%.1f", l.size / days)} per day — reflects logging, not necessarily every void).", null, "parent observations")
        val measured = l.filter { it.volumeMl != null }
        if (measured.isNotEmpty()) f += Finding("Measured volumes: ${measured.joinToString(", ") { "${Fmt.num(it.volumeMl)} ml" }}", null, "parent measurements")
        fun cnt(label: String, n: Int, flag: Flag = Flag.INFO) { if (n > 0) f += Finding("$label recorded $n time(s)", null, "parent observations", flag) }
        cnt("Weak stream", l.count { it.flow == "WEAK" })
        cnt("Interrupted stream", l.count { it.flow == "INTERRUPTED" })
        cnt("Dribbling", l.count { it.flow == "DRIBBLING" })
        cnt("Straining", l.count { it.straining })
        cnt("Crying / pain while passing urine", l.count { it.cryingOrPain })
        cnt("Cloudy-looking urine", l.count { it.cloudy })
        cnt("Foul smell", l.count { it.foulSmell })
        cnt("Blood-like colour", l.count { it.bloodLike }, Flag.REVIEW)
        return Section("Urine observations", f, note = "These are parent observations. Appearance and stream cannot confirm or rule out infection, obstruction or valve problems.")
    }

    private fun stoolSection(s: HealthSnapshot, since: Long): Section {
        val l = s.stool.filter { it.dateTime >= since }
        if (l.isEmpty()) return Section("Potty / stool observations", note = "No stool observations logged in this period.")
        val f = mutableListOf(Finding("${l.size} stool observations logged.", null, "parent observations"))
        l.mapNotNull { it.bristolType }.takeIf { it.isNotEmpty() }?.let { b -> f += Finding("Bristol types chosen: ${b.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(", ") { "type ${it.key} ×${it.value}" }}", null, "parent's choice from reference chart") }
        if (l.any { it.hard || it.constipation }) f += Finding("Hard stool / constipation noted ${l.count { it.hard || it.constipation }} time(s)", null, "parent observations", Flag.INFO)
        if (l.any { it.loose || it.diarrhoea }) f += Finding("Loose stool / diarrhoea noted ${l.count { it.loose || it.diarrhoea }} time(s)", null, "parent observations", Flag.INFO)
        if (l.any { it.bloodLike }) f += Finding("Blood-like appearance noted ${l.count { it.bloodLike }} time(s)", null, "parent observations", Flag.REVIEW)
        return Section("Potty / stool observations", f)
    }

    private fun symptomsSection(s: HealthSnapshot, since: Long) = Section(
        "Symptoms recorded",
        s.symptoms.filter { it.dateTime >= since }.sortedBy { it.dateTime }.map {
            val t = SymptomType.of(it.symptom)
            Finding("${t.label}${it.temperatureC?.let { c -> " ${Fmt.num(c)} °C" } ?: ""} — ${it.severity.lowercase()}" + (if (it.notes.isNotBlank()) ": ${it.notes}" else "") + (if (it.resolved) " (resolved)" else ""),
                it.dateTime, "parent observation", if (t.urgent) Flag.URGENT else if (t == SymptomType.FEVER || t == SymptomType.REDUCED_URINE || t == SymptomType.BLOOD_LIKE_URINE) Flag.REVIEW else Flag.NONE)
        },
    )

    fun growthSection(s: HealthSnapshot, since: Long?): Section {
        val g = s.growth.sortedBy { it.date }
        if (g.isEmpty()) return Section("Growth", note = "No growth measurements recorded.")
        val f = mutableListOf<Finding>()
        val dob = s.dob; val sex = s.profile?.sex ?: "M"
        fun z(ind: String, rec: GrowthRecord, v: Double?): String {
            if (v == null || dob == null || who == null) return ""
            val am = WhoGrowth.ageMonths(dob, Fmt.toLocalDate(rec.date))
            val zz = who.zScore(ind, sex, am, v) ?: return " (outside WHO 0–5 y chart range)"
            return String.format(Locale.ENGLISH, " (WHO z = %.2f, about %.0fth percentile)", zz, WhoGrowth.percentile(zz))
        }
        s.latestGrowthWith { it.weightKg }?.let { f += Finding("Latest weight ${Fmt.num(it.weightKg)} kg" + z("wfa", it, it.weightKg), it.date, it.measuredAt.lowercase() + " measurement") }
        s.latestGrowthWith { it.lengthCm }?.let { f += Finding("Latest length/height ${Fmt.num(it.lengthCm)} cm" + z("lhfa", it, it.lengthCm), it.date, it.measuredAt.lowercase() + " measurement") }
        s.latestGrowthWith { it.headCm }?.let { f += Finding("Latest head circumference ${Fmt.num(it.headCm)} cm" + z("hcfa", it, it.headCm), it.date, it.measuredAt.lowercase() + " measurement") }
        val w = g.filter { it.weightKg != null }
        val inPeriod = if (since != null) w.filter { it.date >= since } else w
        if (inPeriod.size >= 2) {
            val a = inPeriod.first(); val b = inPeriod.last()
            f += Finding("Weight changed from ${Fmt.num(a.weightKg)} kg (${Fmt.date(a.date)}) to ${Fmt.num(b.weightKg)} kg (${Fmt.date(b.date)}): ${String.format(Locale.ENGLISH, "%+.2f", b.weightKg!! - a.weightKg!!)} kg.", b.date, "measured values")
        }
        val prem = (s.profile?.gestationWeeks ?: 40) < 37
        return Section("Growth", f, table = Table(listOf("Date", "Weight (kg)", "Length/height (cm)", "Head (cm)", "Where"), g.takeLast(12).map {
            listOf(Fmt.date(it.date), Fmt.num(it.weightKg), Fmt.num(it.lengthCm), Fmt.num(it.headCm), it.measuredAt.lowercase())
        }), note = "Z-scores use WHO Child Growth Standards (LMS method, interpolated between monthly values) and chronological age." +
            (if (prem) " Ishaan was born before 37 weeks; doctors often use corrected age until 2 years." else ""))
    }

    private fun eventsSection(s: HealthSnapshot, since: Long, title: String) = Section(
        title, s.events.filter { it.dateTime >= since || it.createdAt >= since }.sortedBy { it.dateTime }
            .map { Finding("${EventCategory.of(it.category).label}: ${it.title}", it.dateTime, "entered by parent") },
    )

    private fun newRecordsSection(s: HealthSnapshot, since: Long): Section {
        val f = mutableListOf<Finding>()
        s.events.filter { it.createdAt >= since }.forEach { f += Finding("New history entry: ${it.title}", it.dateTime) }
        s.reports.filter { it.createdAt >= since }.forEach { f += Finding("Report uploaded: ${it.title}", it.reportDate, if (it.verified) "reviewed" else "extraction not yet reviewed", if (!it.verified) Flag.INFO else Flag.NONE) }
        s.growth.filter { it.createdAt >= since }.forEach { f += Finding("Growth measurement added", it.date) }
        return Section("New records", f, note = if (f.isEmpty()) "No new records in the last 24 hours." else null)
    }

    private fun timelineSection(s: HealthSnapshot) = Section("Chronological timeline", buildTimeline(s).map { Finding("${it.label}: ${it.text}", it.date) })

    private fun appointmentsSection(s: HealthSnapshot) = Section(
        "Upcoming appointments",
        s.appointments.filter { !it.completed && it.dateTime >= s.now }.map {
            Finding("${it.specialty.ifBlank { "Appointment" }} with ${it.doctorName.ifBlank { "doctor" }} at ${it.hospital.ifBlank { "—" }}" + (if (it.purpose.isNotBlank()) " — ${it.purpose}" else "") + (if (it.testsToDo.isNotBlank()) ". Tests to do: ${it.testsToDo}" else ""), it.dateTime)
        },
    )

    // ------------------------------------------------------------------ missing info & questions

    fun missingInfo(s: HealthSnapshot): List<String> {
        val m = mutableListOf<String>()
        if (s.profile == null) return listOf("No child profile has been created.")
        if (s.dob == null) m += "Date of birth is missing (needed for age, growth charts and eGFR)."
        if (s.profile.birthWeightKg == null) m += "Birth weight is not recorded."
        if (s.surgeries.isEmpty()) m += "No surgeries/procedures recorded (e.g. valve ablation/fulguration date)."
        if (s.labSeries(LabTest.CREATININE).isEmpty()) m += "No serum creatinine results recorded."
        else if (s.labSeries(LabTest.CREATININE).last().sampleDate < s.now - 180 * DAY) m += "Latest creatinine is more than 6 months old (${Fmt.date(s.labSeries(LabTest.CREATININE).last().sampleDate)})."
        if (s.imaging.none { it.modality == "ULTRASOUND" }) m += "No ultrasound findings recorded."
        if (s.imaging.none { it.modality == "DMSA" }) m += "No DMSA scan findings recorded (if one was done)."
        if (s.growth.none { it.lengthCm != null }) m += "No length/height measurements (needed for eGFR and growth charts)."
        if (s.labSeries(LabTest.BP_SYSTOLIC).isEmpty()) m += "No blood pressure readings recorded."
        val unverified = s.labs.count { it.verification == Verification.UNVERIFIED } + s.imaging.count { it.verification == Verification.UNVERIFIED }
        if (unverified > 0) m += "$unverified OCR-extracted value(s) are not yet verified against the original documents."
        val unread = s.reports.count { it.ocrStatus == "FAILED" || it.ocrStatus == "NO_TEXT" }
        if (unread > 0) m += "$unread uploaded report(s) could not be read by OCR — enter key values manually."
        if (s.reports.any { it.reportDate == null }) m += "${s.reports.count { it.reportDate == null }} report(s) have no date."
        if (s.medications.any { it.needsConfirmation }) m += "One or more prescriptions are marked unclear and need confirmation."
        return m
    }

    fun suggestedQuestions(s: HealthSnapshot): List<String> {
        val q = mutableListOf<String>()
        val cr = s.labSeries(LabTest.CREATININE).filter { it.valueNumeric != null }
        if (cr.size >= 2) {
            val a = cr[cr.size - 2]; val b = cr.last()
            if (a.unit.equals(b.unit, true)) q += "Creatinine was ${Fmt.num(a.valueNumeric)} ${a.unit} on ${Fmt.date(a.sampleDate)} and ${Fmt.num(b.valueNumeric)} ${b.unit} on ${Fmt.date(b.sampleDate)}. How do you interpret this change for Ishaan, and which reference range applies to his age?"
        } else if (cr.size == 1) q += "What creatinine range do you expect for Ishaan at his age, and when should it be repeated?"
        val us = s.imaging.filter { it.modality == "ULTRASOUND" }.map { Fmt.toLocalDate(it.studyDate) }.distinct().sorted()
        if (us.size >= 2) q += "Comparing the ultrasound of ${Fmt.date(us.first())} with ${Fmt.date(us.last())}: has the dilatation (pelvis/ureter) changed in a way that matters?"
        if (us.isNotEmpty() && us.last().isBefore(LocalDate.now().minusMonths(6))) q += "The last recorded ultrasound was ${Fmt.date(us.last())}. When is the next one due?"
        if (s.imaging.any { it.modality == "DMSA" }) q += "What do the DMSA split function and any scarring mean for long-term follow-up?"
        val since30 = s.now - 30 * DAY
        val flowIssues = s.urine.count { it.dateTime >= since30 && (it.flow in setOf("WEAK", "INTERRUPTED", "DRIBBLING") || it.straining) }
        if (flowIssues > 0) q += "In the last 30 days we recorded weak/interrupted stream or straining $flowIssues time(s) (videos are saved). Does this need any evaluation?"
        val utis = s.events.count { it.category == EventCategory.UTI.name }
        if (utis > 0) q += "Ishaan has had $utis recorded urinary infection(s). What signs should prompt an urgent urine test, and what is the plan to prevent further infections?"
        if (s.labSeries(LabTest.BP_SYSTOLIC).isEmpty()) q += "Should Ishaan's blood pressure be checked at visits?"
        if (s.activeMeds.isNotEmpty()) q += "Please review the current medicine list (${s.activeMeds.joinToString { it.name }}) — is each one still needed at the current dose?"
        if (s.symptoms.any { it.dateTime >= since30 && SymptomType.of(it.symptom) == SymptomType.FEVER }) q += "Fever was recorded in the last month. Should a urine culture be done with each fever?"
        q += "Which warning signs mean we should go to emergency immediately?"
        return q
    }
}

data class TimelineItem(val date: Long, val label: String, val text: String, val kind: String, val refId: Long)

fun buildTimeline(s: HealthSnapshot): List<TimelineItem> {
    val t = mutableListOf<TimelineItem>()
    s.dob?.let { t += TimelineItem(Fmt.startOfDay(it), "Birth", "${s.childName} born" + (s.profile?.birthWeightKg?.let { w -> ", ${Fmt.num(w)} kg" } ?: "") + (s.profile?.birthHospital?.takeIf { h -> h.isNotBlank() }?.let { h -> " at $h" } ?: ""), "BIRTH", 0) }
    s.events.forEach { t += TimelineItem(it.dateTime, EventCategory.of(it.category).label, it.title + (if (it.hospitalName.isNotBlank()) " · ${it.hospitalName}" else ""), "EVENT", it.id) }
    s.diagnoses.forEach { d -> d.dateDiagnosed?.let { t += TimelineItem(it, "Diagnosis", d.name, "DIAGNOSIS", d.id) } }
    s.surgeries.forEach { t += TimelineItem(it.dateTime, "Surgery", it.procedureName + (if (it.hospital.isNotBlank()) " · ${it.hospital}" else ""), "SURGERY", it.id) }
    s.admissions.forEach { t += TimelineItem(it.admitDate, "Admission", (it.reason.ifBlank { "Hospital admission" }) + (if (it.intensiveCare != "NONE") " (${it.intensiveCare})" else ""), "ADMISSION", it.id) }
    s.reports.forEach { r -> t += TimelineItem(r.reportDate ?: r.createdAt, ReportCategory.of(r.category).label, r.title, "REPORT", r.id) }
    s.medications.forEach { m ->
        m.startDate?.let { t += TimelineItem(it, "Medicine started", "${m.name} ${m.prescribedDose} ${m.doseUnit}".trim(), "MEDICATION", m.id) }
        m.discontinuedDate?.let { t += TimelineItem(it, "Medicine stopped", m.name, "MEDICATION", m.id) }
    }
    return t.sortedBy { it.date }
}
