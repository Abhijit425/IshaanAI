package com.ishaan.ai.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Bedside CKiD ("updated") Schwartz equation, 2009:  eGFR = 0.413 × height(cm) / serum creatinine(mg/dL)
 *
 * The app only calculates it when every requirement is met, and otherwise explains why not.
 * It is never used to label kidney function as normal/abnormal or to stage CKD.
 */
object BedsideSchwartz {
    const val NAME = "Bedside CKiD Schwartz (2009)"
    const val LIMITATIONS =
        "Estimate only, not a measured GFR. Developed in children aged about 1–16 years with chronic kidney disease, " +
            "using enzymatic (IDMS-traceable) creatinine. Not valid under 1 year of age, when kidney function is still maturing. " +
            "Less reliable with low muscle mass, acute illness or rapidly changing creatinine. Interpret only with Ishaan's nephrologist."

    sealed class Result {
        data class Value(val egfr: Double, val heightCm: Double, val creatinineMgDl: Double, val notes: List<String>) : Result()
        data class NotCalculated(val reasons: List<String>) : Result()
    }

    fun compute(
        dob: LocalDate?,
        creatinineValue: Double?,
        creatinineUnit: String,
        creatinineDate: LocalDate?,
        creatinineVerified: Boolean,
        heightCm: Double?,
        heightDate: LocalDate?,
    ): Result {
        val reasons = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (dob == null) reasons += "Date of birth is not recorded."
        if (creatinineValue == null || creatinineDate == null) reasons += "No numeric serum creatinine with a date."
        if (heightCm == null || heightDate == null) reasons += "No measured height/length with a date."
        if (!creatinineVerified) reasons += "The creatinine value has not been verified against the original report."
        if (reasons.isNotEmpty()) return Result.NotCalculated(reasons)
        val ageYears = ChronoUnit.DAYS.between(dob, creatinineDate) / 365.25
        if (ageYears < 1.0) reasons += "Ishaan was under 1 year old at the time of this test. The equation is not validated for infants."
        if (ageYears > 18.0) reasons += "Age over 18 at the time of the test."
        val gap = abs(ChronoUnit.DAYS.between(heightDate, creatinineDate))
        if (gap > 30) reasons += "The nearest height measurement is $gap days from the creatinine test (needs to be within 30 days)."
        val unit = creatinineUnit.lowercase().replace("μ", "µ")
        val scr = when {
            unit == "mg/dl" -> creatinineValue!!
            unit == "µmol/l" || unit == "umol/l" -> { notes += "Creatinine converted from µmol/L (÷ 88.4)."; creatinineValue!! / 88.4 }
            else -> { reasons += "Creatinine unit is '${creatinineUnit.ifBlank { "unknown" }}' — needs mg/dL or µmol/L."; 0.0 }
        }
        if (scr <= 0.0 && reasons.isEmpty()) reasons += "Creatinine value must be greater than zero."
        if (reasons.isNotEmpty()) return Result.NotCalculated(reasons)
        return Result.Value(0.413 * heightCm!! / scr, heightCm, scr, notes)
    }
}

/** WHO Child Growth Standards z-scores from LMS parameters (0–60 months). */
class WhoGrowth(rows: List<String>) {
    data class Lms(val l: Double, val m: Double, val s: Double)
    private val table: Map<String, Map<Int, Lms>>

    init {
        val t = mutableMapOf<String, MutableMap<Int, Lms>>()
        rows.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("indicator") }.forEach { r ->
            val p = r.split(',')
            t.getOrPut("${p[0]}_${p[1]}") { mutableMapOf() }[p[2].toInt()] = Lms(p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
        }
        table = t
    }

    /** indicator: wfa (weight kg), lhfa (length/height cm), hcfa (head circumference cm); sex: M/F. */
    fun lmsAt(indicator: String, sex: String, ageMonths: Double): Lms? {
        val tab = table["${indicator}_$sex"] ?: return null
        if (ageMonths < 0 || ageMonths > 60) return null
        val lo = ageMonths.toInt().coerceAtMost(60)
        val hi = (lo + 1).coerceAtMost(60)
        val a = tab[lo] ?: return null
        val b = tab[hi] ?: return a
        val f = ageMonths - lo
        return Lms(a.l + (b.l - a.l) * f, a.m + (b.m - a.m) * f, a.s + (b.s - a.s) * f)
    }

    fun zScore(indicator: String, sex: String, ageMonths: Double, x: Double): Double? {
        val p = lmsAt(indicator, sex, ageMonths) ?: return null
        return zFromLms(p, x, restricted = indicator == "wfa")
    }

    fun valueAtZ(indicator: String, sex: String, ageMonths: Double, z: Double): Double? {
        val p = lmsAt(indicator, sex, ageMonths) ?: return null
        return xAt(p, z)
    }

    companion object {
        fun xAt(p: Lms, z: Double): Double =
            if (abs(p.l) < 1e-9) p.m * exp(p.s * z) else p.m * (1 + p.l * p.s * z).pow(1 / p.l)

        /** WHO method, including the restricted adjustment beyond ±3 SD used for weight indicators. */
        fun zFromLms(p: Lms, x: Double, restricted: Boolean): Double {
            val z = if (abs(p.l) < 1e-9) ln(x / p.m) / p.s else ((x / p.m).pow(p.l) - 1) / (p.l * p.s)
            if (!restricted) return z
            return when {
                z > 3 -> { val sd3 = xAt(p, 3.0); val sd23 = sd3 - xAt(p, 2.0); 3 + (x - sd3) / sd23 }
                z < -3 -> { val sd3 = xAt(p, -3.0); val sd23 = xAt(p, -2.0) - sd3; -3 + (x - sd3) / sd23 }
                else -> z
            }
        }

        fun percentile(z: Double): Double = 100.0 * normalCdf(z)

        private fun normalCdf(z: Double): Double {
            // Abramowitz–Stegun 7.1.26 approximation of erf, accurate to ~1e-7
            val t = 1.0 / (1.0 + 0.3275911 * abs(z) / sqrt(2.0))
            val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z / 2)
            return if (z >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
        }

        /** Percentile lines WHO uses on its charts. */
        val PERCENTILE_LINES = listOf(3.0 to -1.881, 15.0 to -1.036, 50.0 to 0.0, 85.0 to 1.036, 97.0 to 1.881)

        fun ageMonths(dob: LocalDate, on: LocalDate): Double = ChronoUnit.DAYS.between(dob, on) / 30.4375
    }
}
