package com.ishaan.ai.data

import com.ishaan.ai.data.db.*
import java.time.LocalDate
import java.time.ZoneId

/**
 * Optional, clearly-labelled sample data for trying the app. It is NOT about Ishaan, can only be loaded
 * into an empty app, every row is flagged isSample, and "Remove sample data" deletes all of it.
 * The production dashboard never shows hard-coded values.
 */
class SampleData(private val db: AppDatabase) {

    suspend fun canLoad(): Boolean = db.profileDao().count() == 0

    suspend fun load() {
        check(canLoad()) { "Sample data can only be loaded into an empty app." }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun ms(d: LocalDate, h: Int = 10) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val dob = today.minusMonths(20)
        val child = db.profileDao().upsert(ChildProfile(name = "Sample Child (demo)", dateOfBirthEpochDay = dob.toEpochDay(), sex = "M", primaryDiagnosis = "Demo diagnosis — not real data", birthWeightKg = 2.9, gestationWeeks = 38, isSample = true))
        val h = db.historyDao()
        h.upsertEvent(MedicalEvent(childId = child, category = EventCategory.DIAGNOSIS.name, title = "Demo: antenatal scan finding", dateTime = ms(dob.minusDays(30)), isSample = true))
        h.upsertSurgery(Surgery(childId = child, procedureName = "Demo procedure 1", dateTime = ms(dob.plusDays(5)), hospital = "Demo Hospital", isSample = true))
        h.upsertSurgery(Surgery(childId = child, procedureName = "Demo procedure 2", dateTime = ms(dob.plusMonths(4)), hospital = "Demo Hospital", isSample = true))
        h.upsertAdmission(HospitalAdmission(childId = child, admitDate = ms(dob), dischargeDate = ms(dob.plusDays(12)), hospital = "Demo Hospital", reason = "Demo admission", intensiveCare = "NICU", isSample = true))
        h.upsertEvent(MedicalEvent(childId = child, category = EventCategory.UTI.name, title = "Demo infection episode", dateTime = ms(dob.plusMonths(7)), isSample = true))
        val r = db.reportDao()
        listOf(0.9 to 2, 0.6 to 5, 0.45 to 9, 0.4 to 14, 0.42 to 19).forEach { (v, m) ->
            r.upsertLab(ExtractedLabResult(childId = child, testCode = LabTest.CREATININE.name, displayName = "Serum creatinine", valueText = v.toString(), valueNumeric = v, unit = "mg/dL", refText = "demo range", specimen = "SERUM", sampleDate = ms(dob.plusMonths(m.toLong())), source = Source.SAMPLE, verification = Verification.VERIFIED, isSample = true))
        }
        r.upsertLab(ExtractedLabResult(childId = child, testCode = LabTest.URINE_CULTURE.name, displayName = "Urine culture", valueText = "No growth", specimen = "URINE", sampleDate = ms(dob.plusMonths(14)), source = Source.SAMPLE, verification = Verification.VERIFIED, isSample = true))
        listOf(4 to 12.0, 14 to 9.0).forEach { (m, apd) ->
            val d = ms(dob.plusMonths(m.toLong()))
            r.upsertImaging(ImagingFinding(childId = child, modality = "ULTRASOUND", studyDate = d, side = "RIGHT", parameter = ImagingParam.APRPD.name, valueNumeric = apd, unit = "mm", source = Source.SAMPLE, isSample = true))
            r.upsertImaging(ImagingFinding(childId = child, modality = "ULTRASOUND", studyDate = d, side = "LEFT", parameter = ImagingParam.APRPD.name, valueNumeric = apd - 3, unit = "mm", source = Source.SAMPLE, isSample = true))
        }
        r.upsertImaging(ImagingFinding(childId = child, modality = "DMSA", studyDate = ms(dob.plusMonths(10)), side = "RIGHT", parameter = ImagingParam.SPLIT_FUNCTION.name, valueNumeric = 46.0, unit = "%", source = Source.SAMPLE, isSample = true))
        r.upsertImaging(ImagingFinding(childId = child, modality = "DMSA", studyDate = ms(dob.plusMonths(10)), side = "LEFT", parameter = ImagingParam.SPLIT_FUNCTION.name, valueNumeric = 54.0, unit = "%", source = Source.SAMPLE, isSample = true))
        val mon = db.monitoringDao()
        listOf(0 to (2.9 to 49.0), 3 to (5.6 to 59.0), 6 to (7.4 to 66.0), 12 to (9.4 to 75.0), 19 to (10.8 to 82.0)).forEach { (m, wl) ->
            mon.upsertGrowth(GrowthRecord(childId = child, date = ms(dob.plusMonths(m.toLong())), weightKg = wl.first, lengthCm = wl.second, measuredAt = "CLINIC", isSample = true))
        }
        mon.upsertUrine(UrineLog(childId = child, dateTime = System.currentTimeMillis() - 3_600_000, colour = "pale yellow", flow = "GOOD_STREAM", notes = "Demo entry", isSample = true))
        val med = db.medicationDao().upsert(Medication(childId = child, name = "Demo Medicine", strength = "demo", prescribedDose = "as prescribed (demo)", frequency = "once daily", startDate = ms(today.minusMonths(2)), isSample = true))
        db.medicationDao().insertSchedule(MedicationSchedule(medicationId = med, minuteOfDay = 21 * 60))
        db.planDao().upsertAppointment(Appointment(childId = child, dateTime = ms(today.plusDays(10), 11), doctorName = "Demo Doctor", specialty = "Paediatric nephrology", hospital = "Demo Hospital", purpose = "Demo follow-up", isSample = true))
    }

    suspend fun remove() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM health_alert WHERE childId IN (SELECT id FROM child_profile WHERE isSample = 1)")
        db.profileDao().deleteSampleProfiles()
    }
}
