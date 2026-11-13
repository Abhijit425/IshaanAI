package com.ishaan.ai.domain

import com.ishaan.ai.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EngineTest {
    private fun ms(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val dob = LocalDate.now().minusYears(3)
    private val profile = ChildProfile(id = 1, name = "Test", dateOfBirthEpochDay = dob.toEpochDay(), primaryDiagnosis = "PUV")
    private fun cr(v: Double, d: LocalDate, ver: String = Verification.VERIFIED) =
        ExtractedLabResult(childId = 1, testCode = LabTest.CREATININE.name, displayName = "Creatinine", valueText = v.toString(), valueNumeric = v, unit = "mg/dL", refText = "0.2-0.4", refLow = 0.2, refHigh = 0.4, sampleDate = ms(d), verification = ver)

    private val engine = AnalysisEngine(null)

    @Test fun emptyRecordsReportMissingInfoNotInventedValues() {
        val s = HealthSnapshot(profile)
        val r = engine.generate(ReportKind.DOCTOR, s)
        assertTrue(r.missing.any { it.contains("No serum creatinine") })
        val text = r.asPlainText()
        assertFalse(text.contains("mg/dL"))
        assertTrue(text.contains("not a diagnosis"))
    }

    @Test fun creatinineTrendIsDescribedNotDiagnosed() {
        val s = HealthSnapshot(profile, labs = listOf(cr(0.3, LocalDate.now().minusMonths(6)), cr(0.45, LocalDate.now().minusDays(5))))
        val sec = engine.labTrendSection(s, listOf(LabTest.CREATININE), null, "x")
        assertTrue(sec.findings.any { it.text.contains("higher than the first recorded") && it.text.contains("+50%") })
        assertTrue(sec.findings.any { it.flag == Flag.REVIEW && it.text.contains("printed on that report") })
        assertEquals(2, sec.table!!.rows.size)
        val all = sec.findings.joinToString { it.text }.lowercase()
        assertFalse(all.contains("kidney failure")); assertFalse(all.contains("ckd"))
    }

    @Test fun egfrNotCalculatedFromUnverifiedOcr() {
        val s = HealthSnapshot(profile, labs = listOf(cr(0.4, LocalDate.now().minusDays(2), Verification.UNVERIFIED)),
            growth = listOf(GrowthRecord(childId = 1, date = ms(LocalDate.now().minusDays(2)), lengthCm = 95.0)))
        assertTrue(engine.egfrSection(s).findings.any { it.text.contains("not been verified") })
    }

    @Test fun egfrCalculatedWithAllInputs() {
        val s = HealthSnapshot(profile, labs = listOf(cr(0.4, LocalDate.now().minusDays(2))),
            growth = listOf(GrowthRecord(childId = 1, date = ms(LocalDate.now().minusDays(2)), lengthCm = 95.0)))
        val f = engine.egfrSection(s)
        assertTrue(f.findings.first().text.contains("Bedside CKiD Schwartz"))
        assertEquals(BedsideSchwartz.LIMITATIONS, f.note)
    }

    @Test fun medicationsShownExactlyAsPrescribed() {
        val m = Medication(id = 5, childId = 1, name = "Septran", prescribedDose = "2.5", doseUnit = "ml", frequency = "at night")
        val r = engine.generate(ReportKind.COMPLETE, HealthSnapshot(profile, medications = listOf(m)))
        assertTrue(r.asPlainText().contains("Septran"))
        assertTrue(r.asPlainText().contains("2.5 ml"))
    }

    @Test fun imagingComparisonKeepsSides() {
        val a = ImagingFinding(childId = 1, modality = "ULTRASOUND", studyDate = ms(LocalDate.now().minusMonths(6)), side = "RIGHT", parameter = ImagingParam.APRPD.name, valueNumeric = 12.0, unit = "mm")
        val b = a.copy(studyDate = ms(LocalDate.now()), valueNumeric = 8.0)
        val c = a.copy(side = "LEFT", valueNumeric = 5.0)
        val sec = engine.imagingSections(HealthSnapshot(profile, imaging = listOf(a, b, c))).first()
        assertEquals(listOf("Right", "Left"), sec.table!!.rows.map { it[0] })
        assertEquals("12 mm", sec.table!!.rows[0][2]); assertEquals("8 mm", sec.table!!.rows[0][3])
    }

    @Test fun urgentSymptomRaisesUrgentAlert() {
        val s = HealthSnapshot(profile, symptoms = listOf(SymptomLog(id = 3, childId = 1, dateTime = System.currentTimeMillis(), symptom = SymptomType.NO_URINE.name)))
        val a = AlertEngine.evaluate(s)
        assertTrue(a.any { it.severity == "URGENT" && it.message.contains("emergency") })
    }

    @Test fun feverInYoungInfantIsUrgent() {
        val baby = profile.copy(dateOfBirthEpochDay = LocalDate.now().minusDays(40).toEpochDay())
        val s = HealthSnapshot(baby, symptoms = listOf(SymptomLog(id = 4, childId = 1, dateTime = System.currentTimeMillis(), symptom = SymptomType.FEVER.name, temperatureC = 38.2)))
        assertEquals("URGENT", AlertEngine.evaluate(s).first().severity)
    }

    @Test fun timelineStartsAtBirthAndIsChronological() {
        val s = HealthSnapshot(profile, surgeries = listOf(Surgery(childId = 1, procedureName = "Fulguration", dateTime = ms(dob.plusDays(4)))))
        val t = buildTimeline(s)
        assertEquals("Birth", t.first().label)
        assertEquals(t.sortedBy { it.date }, t)
    }
}
