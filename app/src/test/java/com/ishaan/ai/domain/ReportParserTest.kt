package com.ishaan.ai.domain

import com.ishaan.ai.data.db.ImagingParam
import com.ishaan.ai.data.db.LabTest
import com.ishaan.ai.data.db.ReportCategory
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReportParserTest {
    private val kft = """
        CITY DIAGNOSTICS
        Patient: Test Child     Age: 2 Y   Sex: M
        Sample Collected: 12/03/2025 09:10
        KIDNEY FUNCTION TEST
        Test Name      Result   Unit    Biological Ref. Interval
        Serum Creatinine   0.42   mg/dL   0.20 - 0.43
        Blood Urea   18   mg/dL   10 - 38
        Sodium   138   mmol/L   135 - 145
        Potassium   4.6   mmol/L   3.5 - 5.5
        Bicarbonate   21   mmol/L   22 - 29
    """.trimIndent()

    @Test fun detectsKidneyReportAndValues() {
        val r = ReportParser.parse(kft)
        assertEquals(ReportCategory.KIDNEY_FUNCTION, r.category)
        val cr = r.labs.first { it.test == LabTest.CREATININE }
        assertEquals(0.42, cr.valueNumeric!!, 1e-9)
        assertEquals("mg/dL", cr.unit)
        assertEquals(0.20, cr.refLow!!, 1e-9)
        assertEquals(0.43, cr.refHigh!!, 1e-9)
        assertEquals(18.0, r.labs.first { it.test == LabTest.UREA }.valueNumeric!!, 1e-9)
        assertEquals(4.6, r.labs.first { it.test == LabTest.POTASSIUM }.valueNumeric!!, 1e-9)
        assertEquals(21.0, r.labs.first { it.test == LabTest.BICARBONATE }.valueNumeric!!, 1e-9)
    }

    @Test fun detectsDayFirstDateNearKeyword() {
        val r = ReportParser.parse(kft)
        assertEquals(LocalDate.of(2025, 3, 12), r.bestDate!!.date)
        assertTrue(r.bestDate!!.nearKeyword)
    }

    @Test fun neverInventsValues() {
        val r = ReportParser.parse("Serum creatinine\nPending\nRemarks: sample haemolysed")
        assertTrue(r.labs.none { it.test == LabTest.CREATININE })
    }

    @Test fun valueOnNextLineIsFound() {
        val r = ReportParser.parse("Kidney function\nSerum Creatinine\n0.38 mg/dL 0.2-0.4")
        assertEquals(0.38, r.labs.first { it.test == LabTest.CREATININE }.valueNumeric!!, 1e-9)
    }

    @Test fun urineCreatinineIsNotFiledAsSerum() {
        val r = ReportParser.parse("Urine creatinine 20 mg/dL\nSerum Creatinine 0.5 mg/dL")
        val serum = r.labs.filter { it.test == LabTest.CREATININE }
        assertEquals(1, serum.size)
        assertEquals(0.5, serum[0].valueNumeric!!, 1e-9)
    }

    @Test fun urineCultureQualitative() {
        val r = ReportParser.parse("URINE CULTURE AND SENSITIVITY\nCulture: No growth after 48 hours of incubation")
        assertEquals(ReportCategory.URINE_CULTURE, r.category)
        assertEquals("No growth", r.labs.first { it.test == LabTest.URINE_CULTURE }.valueText)
    }

    @Test fun ultrasoundSidesAndMeasurements() {
        val t = """
            ULTRASONOGRAPHY KUB
            Date of study: 05-Jan-2025
            Right kidney measures 58 x 28 mm. Renal pelvis AP diameter 11 mm. SFU grade 2 hydronephrosis.
            Left kidney measures 55 x 26 mm. APD 6 mm.
            Urinary bladder wall thickness 3.5 mm. Post void residual 8 ml.
            IMPRESSION: Right moderate hydronephrosis.
        """.trimIndent()
        val r = ReportParser.parse(t)
        assertEquals(ReportCategory.ULTRASOUND, r.category)
        assertEquals(LocalDate.of(2025, 1, 5), r.bestDate!!.date)
        assertEquals(58.0, r.imaging.first { it.side == "RIGHT" && it.param == ImagingParam.KIDNEY_LENGTH }.valueNumeric!!, 1e-9)
        assertEquals(11.0, r.imaging.first { it.side == "RIGHT" && it.param == ImagingParam.APRPD }.valueNumeric!!, 1e-9)
        assertEquals(6.0, r.imaging.first { it.side == "LEFT" && it.param == ImagingParam.APRPD }.valueNumeric!!, 1e-9)
        assertEquals(3.5, r.imaging.first { it.param == ImagingParam.BLADDER_WALL }.valueNumeric!!, 1e-9)
        assertEquals(8.0, r.imaging.first { it.param == ImagingParam.POST_VOID_RESIDUAL }.valueNumeric!!, 1e-9)
        assertTrue(r.imaging.any { it.param == ImagingParam.IMPRESSION })
    }

    @Test fun dmsaSplitFunction() {
        val r = ReportParser.parse("Tc-99m DMSA renal cortical scan\nDifferential function: Right kidney 41.5 %, Left kidney 58.5 %\nImpression: scar in right upper pole")
        assertEquals(ReportCategory.DMSA, r.category)
        assertEquals(41.5, r.imaging.first { it.side == "RIGHT" && it.param == ImagingParam.SPLIT_FUNCTION }.valueNumeric!!, 1e-9)
        assertEquals(58.5, r.imaging.first { it.side == "LEFT" && it.param == ImagingParam.SPLIT_FUNCTION }.valueNumeric!!, 1e-9)
    }

    @Test fun rangeParser() {
        assertEquals(0.2 to 0.4, ReportParser.parseRange("0.2 - 0.4"))
        assertEquals(null to 5.0, ReportParser.parseRange("< 5"))
    }
}
