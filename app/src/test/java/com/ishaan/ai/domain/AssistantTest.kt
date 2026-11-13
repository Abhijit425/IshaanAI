package com.ishaan.ai.domain

import com.ishaan.ai.data.db.*
import org.junit.Assert.*
import org.junit.Test

class AssistantTest {
    private val a = RuleAssistant(AnalysisEngine(null))
    private val p = ChildProfile(id = 1, name = "Ishaan", dateOfBirthEpochDay = 19000)

    @Test fun missingDataIsSaidPlainly() {
        val r = a.answer("What is Ishaan's latest creatinine?", HealthSnapshot(p))
        assertTrue(r.text.contains("No Serum creatinine results are stored"))
    }

    @Test fun latestCreatinineComesFromRecords() {
        val l = ExtractedLabResult(childId = 1, testCode = "CREATININE", displayName = "", valueText = "0.41", valueNumeric = 0.41, unit = "mg/dL", sampleDate = 1_700_000_000_000, verification = Verification.UNVERIFIED)
        val r = a.answer("What is Ishaan's latest creatinine?", HealthSnapshot(p, labs = listOf(l)))
        assertTrue(r.text.contains("0.41 mg/dL"))
        assertTrue(r.text.contains("not yet verified"))
        assertTrue(r.sources.isNotEmpty())
    }

    @Test fun refusesDoseAdvice() {
        val r = a.answer("Should I increase his antibiotic dose?", HealthSnapshot(p))
        assertTrue(r.text.contains("can't advise"))
    }

    @Test fun doctorSummaryOffersReport() {
        assertEquals(ReportKind.DOCTOR, a.answer("Prepare a medical summary for tomorrow's doctor visit", HealthSnapshot(p)).reportKind)
    }

    @Test fun cultureSummaryRespectsCount() {
        val cs = (1..5).map { ExtractedLabResult(childId = 1, testCode = "URINE_CULTURE", displayName = "", valueText = "result $it", sampleDate = it * 1_000_000_000L) }
        val r = a.answer("Summarize his last three urine culture reports", HealthSnapshot(p, labs = cs))
        assertTrue(r.text.contains("3 of 5")); assertTrue(r.text.contains("result 5")); assertFalse(r.text.contains("result 2"))
    }
}
