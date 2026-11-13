package com.ishaan.ai.domain

import com.ishaan.ai.data.db.HealthAlert
import com.ishaan.ai.data.db.LabTest
import com.ishaan.ai.data.db.SymptomType
import com.ishaan.ai.data.db.Verification
import java.time.temporal.ChronoUnit

/**
 * Configurable, rule-based alerts built only from what the parent recorded.
 * Urgent symptoms always show emergency guidance immediately — no AI assessment is involved or awaited.
 */
object AlertEngine {
    private const val DAY = 86_400_000L

    const val EMERGENCY_GUIDANCE =
        "Seek emergency medical care now (go to the nearest emergency department or call 112 in India) if Ishaan: " +
            "cannot pass urine or has a very distended/painful tummy; has severe difficulty breathing; is unusually drowsy, floppy or hard to wake; " +
            "has a fit/seizure; shows signs of dehydration (no wet nappy for many hours, no tears, sunken soft spot); or looks seriously unwell. " +
            "Do not wait for this app or any AI assessment."

    fun evaluate(s: HealthSnapshot): List<HealthAlert> {
        val child = s.profile?.id ?: return emptyList()
        val out = mutableListOf<HealthAlert>()
        fun add(key: String, sev: String, title: String, msg: String) { out += HealthAlert(childId = child, sourceKey = "rule:$key", severity = sev, title = title, message = msg) }

        val recent = s.symptoms.filter { !it.resolved && it.dateTime >= s.now - 3 * DAY }
        val ageDays = s.dob?.let { ChronoUnit.DAYS.between(it, Fmt.toLocalDate(s.now)) }
        for (sym in recent) {
            val t = SymptomType.of(sym.symptom)
            when {
                t.urgent -> add("sym:${sym.id}", "URGENT", "${t.label} recorded", EMERGENCY_GUIDANCE)
                t == SymptomType.FEVER -> {
                    val infantFever = ageDays != null && ageDays < 90 && (sym.temperatureC ?: 0.0) >= 38.0
                    if (infantFever) add("sym:${sym.id}", "URGENT", "Fever in a baby under 3 months", "A temperature of 38 °C or more in a baby under 3 months needs urgent medical assessment today. $EMERGENCY_GUIDANCE")
                    else add("sym:${sym.id}", "REVIEW", "Fever recorded ${Fmt.dateTime(sym.dateTime)}", "Contact Ishaan's doctor today. In children with PUV, fever can be a sign of a urinary infection and a urine test may be needed.")
                }
                t == SymptomType.REDUCED_URINE -> add("sym:${sym.id}", "REVIEW", "Reduced urine output recorded", "Contact Ishaan's doctor. If he cannot pass urine at all, or seems unwell, seek emergency care.")
                t == SymptomType.BLOOD_LIKE_URINE -> add("sym:${sym.id}", "REVIEW", "Blood-like urine recorded", "Contact Ishaan's doctor and keep the photo/video to show them.")
                t == SymptomType.VOMITING || t == SymptomType.POOR_FEEDING -> add("sym:${sym.id}", "REVIEW", "${t.label} recorded", "Contact the doctor if it continues, if he cannot keep fluids down, or has fewer wet nappies than usual.")
                t == SymptomType.SWELLING -> add("sym:${sym.id}", "REVIEW", "Swelling recorded", "Please inform Ishaan's doctor about the swelling.")
                else -> {}
            }
        }
        s.urine.filter { it.dateTime >= s.now - 2 * DAY && it.bloodLike }.forEach {
            add("urine-blood:${it.id}", "REVIEW", "Blood-like urine colour logged", "Recorded ${Fmt.dateTime(it.dateTime)}. Please show the saved photo/video to Ishaan's doctor. Colour on video can be misleading; it needs clinical checking.")
        }

        // Medicines
        val missed = s.medLogs.filter { it.actionTime >= s.now - 7 * DAY && it.status == "MISSED" }
        if (missed.size >= 2) add("missed:${Fmt.toLocalDate(s.now)}", "INFO", "${missed.size} missed doses this week", "Doses logged as missed in the last 7 days. If doses are often missed, mention it to the doctor. Do not double doses unless the doctor has told you to.")
        s.medications.filter { it.active && it.needsConfirmation }.forEach {
            add("unclear-rx:${it.id}", "REVIEW", "Prescription unclear: ${it.name}", "This prescription was marked unclear. Confirm the dose and timing with the doctor or pharmacist before relying on it.")
        }

        // Appointments
        s.appointments.filter { !it.completed && it.dateTime in s.now..(s.now + 3 * DAY) }.forEach {
            add("appt:${it.id}", "INFO", "Appointment ${Fmt.dateTime(it.dateTime)}", "${it.specialty.ifBlank { "Visit" }} with ${it.doctorName.ifBlank { "doctor" }}" + (if (it.testsToDo.isNotBlank()) ". Tests to do before: ${it.testsToDo}" else "") + ". You can generate a doctor report from the AI tab.")
        }

        // Follow-up gaps (stated as gaps in records, not as medical advice)
        val cr = s.labSeries(LabTest.CREATININE)
        if (cr.isNotEmpty() && cr.last().sampleDate < s.now - 180 * DAY) add("cr-gap", "INFO", "No creatinine in 6 months", "The latest stored creatinine is from ${Fmt.date(cr.last().sampleDate)}. If a newer test was done, add the report; otherwise ask the doctor when the next one is due.")
        val us = s.imaging.filter { it.modality == "ULTRASOUND" }.maxOfOrNull { it.studyDate }
        if (us != null && us < s.now - 365 * DAY) add("us-gap", "INFO", "No ultrasound in a year", "The latest stored ultrasound is from ${Fmt.date(us)}. Ask the doctor about the follow-up schedule.")

        val unverified = s.labs.count { it.verification == Verification.UNVERIFIED } + s.imaging.count { it.verification == Verification.UNVERIFIED }
        if (unverified > 0) add("unverified:$unverified", "INFO", "$unverified values need checking", "Values read by OCR are marked unverified until you compare them with the original document (Reports tab).")

        return out
    }
}
