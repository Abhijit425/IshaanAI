package com.ishaan.ai.domain

import com.ishaan.ai.data.db.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the analysis engine, assistant and PDF need, read once from the database. */
data class HealthSnapshot(
    val profile: ChildProfile?,
    val events: List<MedicalEvent> = emptyList(),
    val diagnoses: List<Diagnosis> = emptyList(),
    val surgeries: List<Surgery> = emptyList(),
    val admissions: List<HospitalAdmission> = emptyList(),
    val reports: List<MedicalReport> = emptyList(),
    val labs: List<ExtractedLabResult> = emptyList(),
    val imaging: List<ImagingFinding> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val schedules: Map<Long, List<MedicationSchedule>> = emptyMap(),
    val medLogs: List<MedicationLog> = emptyList(),
    val urine: List<UrineLog> = emptyList(),
    val stool: List<StoolLog> = emptyList(),
    val growth: List<GrowthRecord> = emptyList(),
    val feeding: List<FeedingLog> = emptyList(),
    val symptoms: List<SymptomLog> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
    val now: Long = System.currentTimeMillis(),
) {
    val dob: LocalDate? get() = profile?.dateOfBirthEpochDay?.let { LocalDate.ofEpochDay(it) }
    val childName: String get() = profile?.name?.ifBlank { null } ?: "the child"

    fun labSeries(test: LabTest) = labs.filter { it.testCode == test.name }.sortedBy { it.sampleDate }
    fun latestLab(test: LabTest) = labSeries(test).lastOrNull()
    fun latestGrowthWith(sel: (GrowthRecord) -> Double?) = growth.filter { sel(it) != null }.maxByOrNull { it.date }
    val activeMeds get() = medications.filter { it.active }
}

object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val d = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val dt = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH)
    private val t = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun date(ms: Long?): String = ms?.let { d.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "date unknown"
    fun date(ld: LocalDate?): String = ld?.let { d.format(it) } ?: "date unknown"
    fun dateTime(ms: Long?): String = ms?.let { dt.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "—"
    fun time(ms: Long): String = t.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun minuteOfDay(m: Int): String = t.format(LocalDateTime.of(2000, 1, 1, m / 60, m % 60))
    fun toLocalDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun startOfDay(ld: LocalDate): Long = ld.atStartOfDay(zone).toInstant().toEpochMilli()
    fun num(v: Double?): String = when {
        v == null -> "—"
        v == Math.floor(v) && kotlin.math.abs(v) < 1e7 -> v.toLong().toString()
        else -> String.format(Locale.ENGLISH, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    fun age(dob: LocalDate?, on: LocalDate = LocalDate.now()): String {
        if (dob == null) return "age unknown"
        val p = java.time.Period.between(dob, on)
        return when {
            p.years >= 2 -> "${p.years} y ${p.months} m"
            p.years == 1 -> "${12 + p.months} months"
            p.months >= 1 -> "${p.months} m ${p.days} d"
            else -> "${java.time.temporal.ChronoUnit.DAYS.between(dob, on)} days"
        }
    }

    fun verificationLabel(v: String): String = when (v) {
        Verification.UNVERIFIED -> "OCR – not yet verified"
        Verification.VERIFIED -> "Verified from report"
        Verification.CORRECTED -> "Corrected by parent (original kept)"
        Verification.PARENT_ENTERED -> "Entered by parent"
        else -> v
    }
}
