package com.ishaan.ai.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ClinicalCalcTest {
    private val dob = LocalDate.of(2022, 1, 1)

    @Test fun schwartzComputesWhenValid() {
        val r = BedsideSchwartz.compute(dob, 0.4, "mg/dL", LocalDate.of(2024, 1, 1), true, 86.0, LocalDate.of(2024, 1, 10))
        assertTrue(r is BedsideSchwartz.Result.Value)
        assertEquals(0.413 * 86.0 / 0.4, (r as BedsideSchwartz.Result.Value).egfr, 1e-9)
    }

    @Test fun schwartzRefusesInfants() {
        val r = BedsideSchwartz.compute(dob, 0.4, "mg/dL", LocalDate.of(2022, 8, 1), true, 68.0, LocalDate.of(2022, 8, 1))
        assertTrue(r is BedsideSchwartz.Result.NotCalculated)
    }

    @Test fun schwartzRefusesUnverifiedOrMissingHeightOrFarApart() {
        assertTrue(BedsideSchwartz.compute(dob, 0.4, "mg/dL", LocalDate.of(2024, 1, 1), false, 86.0, LocalDate.of(2024, 1, 1)) is BedsideSchwartz.Result.NotCalculated)
        assertTrue(BedsideSchwartz.compute(dob, 0.4, "mg/dL", LocalDate.of(2024, 1, 1), true, null, null) is BedsideSchwartz.Result.NotCalculated)
        assertTrue(BedsideSchwartz.compute(dob, 0.4, "mg/dL", LocalDate.of(2024, 1, 1), true, 86.0, LocalDate.of(2023, 6, 1)) is BedsideSchwartz.Result.NotCalculated)
        assertTrue(BedsideSchwartz.compute(dob, 0.4, "", LocalDate.of(2024, 1, 1), true, 86.0, LocalDate.of(2024, 1, 1)) is BedsideSchwartz.Result.NotCalculated)
    }

    @Test fun schwartzConvertsMicromolPerLitre() {
        val r = BedsideSchwartz.compute(dob, 35.36, "µmol/L", LocalDate.of(2024, 1, 1), true, 86.0, LocalDate.of(2024, 1, 1)) as BedsideSchwartz.Result.Value
        assertEquals(0.413 * 86.0 / 0.4, r.egfr, 1e-6)
    }

    private fun who() = WhoGrowth(File("src/main/assets/who_lms.csv").readLines())

    @Test fun whoMedianIsZero() {
        val w = who()
        assertEquals(0.0, w.zScore("wfa", "M", 0.0, 3.3464)!!, 1e-6)
        assertEquals(0.0, w.zScore("lhfa", "M", 0.0, 49.8842)!!, 1e-6)
    }

    @Test fun whoPublishedSdLinesMatch() {
        val w = who()
        // WHO boys weight-for-age at 12 months: -2 SD = 7.7 kg, +2 SD = 12.0 kg (published, rounded)
        assertEquals(-2.0, w.zScore("wfa", "M", 12.0, 7.7)!!, 0.08)
        assertEquals(2.0, w.zScore("wfa", "M", 12.0, 12.0)!!, 0.08)
        assertEquals(50.0, WhoGrowth.percentile(0.0), 1e-6)
        assertEquals(97.0, WhoGrowth.percentile(1.881), 0.1)
    }

    @Test fun whoOutsideRangeReturnsNull() { assertNull(who().zScore("wfa", "M", 70.0, 20.0)) }
}
