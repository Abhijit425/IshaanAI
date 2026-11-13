package com.ishaan.ai.data.db

enum class EventCategory(val label: String) {
    BIRTH("Birth details"),
    DIAGNOSIS("Diagnosis"),
    SURGERY("Surgery / procedure"),
    CYSTOSCOPY("Cystoscopy"),
    FULGURATION("Valve fulguration"),
    CATHETER_INSERTED("Catheter inserted"),
    CATHETER_REMOVED("Catheter removed"),
    ADMISSION("Hospital admission"),
    DISCHARGE("Discharge"),
    NICU_ICU("NICU / ICU stay"),
    UTI("Urinary tract infection"),
    BLOOD_INFECTION("Blood infection / sepsis"),
    ANTIBIOTIC_COURSE("Antibiotic treatment"),
    KIDNEY_COMPLICATION("Kidney-related complication"),
    IMAGING("Scan / imaging"),
    LAB_TEST("Lab test"),
    DOCTOR_OBSERVATION("Doctor observation"),
    FOLLOW_UP("Follow-up visit"),
    VACCINATION("Vaccination"),
    OTHER("Other");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

enum class ReportCategory(val label: String) {
    BLOOD_TEST("Blood test"),
    KIDNEY_FUNCTION("Kidney function (RFT/KFT)"),
    ELECTROLYTES("Electrolytes"),
    URINE_ROUTINE("Urine routine"),
    URINE_CULTURE("Urine culture"),
    BLOOD_CULTURE("Blood culture"),
    ULTRASOUND("Ultrasound"),
    DMSA("DMSA scan"),
    MCU("MCU / VCUG"),
    PRESCRIPTION("Prescription"),
    DISCHARGE_SUMMARY("Discharge summary"),
    OPERATION_NOTE("Operation note"),
    OTHER("Other document");

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * Lab tests the app recognises. Aliases are matched case-insensitively against OCR lines.
 * No reference ranges are hard-coded: paediatric ranges depend on age, method and lab,
 * so the app only ever shows the range printed on the child's own report.
 */
enum class LabTest(val label: String, val defaultUnit: String, val specimen: String, val aliases: List<String>) {
    CREATININE("Serum creatinine", "mg/dL", "SERUM", listOf("serum creatinine", "s. creatinine", "s.creatinine", "creatinine, serum", "creatinine")),
    UREA("Blood urea", "mg/dL", "SERUM", listOf("blood urea", "serum urea", "urea")),
    BUN("BUN", "mg/dL", "SERUM", listOf("blood urea nitrogen", "bun", "urea nitrogen")),
    SODIUM("Sodium", "mmol/L", "SERUM", listOf("serum sodium", "sodium", "na+")),
    POTASSIUM("Potassium", "mmol/L", "SERUM", listOf("serum potassium", "potassium", "k+")),
    CHLORIDE("Chloride", "mmol/L", "SERUM", listOf("serum chloride", "chloride", "cl-")),
    BICARBONATE("Bicarbonate", "mmol/L", "SERUM", listOf("bicarbonate", "hco3", "total co2", "tco2")),
    CALCIUM("Calcium", "mg/dL", "SERUM", listOf("serum calcium", "calcium")),
    PHOSPHORUS("Phosphorus", "mg/dL", "SERUM", listOf("phosphorus", "phosphate", "inorganic phosphorus")),
    URIC_ACID("Uric acid", "mg/dL", "SERUM", listOf("uric acid")),
    HEMOGLOBIN("Haemoglobin", "g/dL", "BLOOD", listOf("haemoglobin", "hemoglobin", "hb")),
    TLC("Total leucocyte count", "/cumm", "BLOOD", listOf("total leucocyte count", "total leukocyte count", "tlc", "wbc")),
    CRP("CRP", "mg/L", "SERUM", listOf("c-reactive protein", "c reactive protein", "crp")),
    CYSTATIN_C("Cystatin C", "mg/L", "SERUM", listOf("cystatin c", "cystatin-c")),
    URINE_PUS_CELLS("Urine pus cells", "/hpf", "URINE", listOf("pus cells", "pus cell", "leucocytes", "wbc/hpf")),
    URINE_RBC("Urine RBC", "/hpf", "URINE", listOf("red blood cells", "rbc", "r.b.c")),
    URINE_PROTEIN("Urine protein / albumin", "", "URINE", listOf("protein", "albumin")),
    URINE_SG("Urine specific gravity", "", "URINE", listOf("specific gravity", "sp. gravity", "sp gravity")),
    URINE_PH("Urine pH", "", "URINE", listOf("reaction (ph)", "ph")),
    URINE_NITRITE("Urine nitrite", "", "URINE", listOf("nitrite")),
    URINE_CULTURE("Urine culture", "", "URINE", listOf("culture", "organism isolated", "colony count", "growth")),
    URINE_PCR("Urine protein:creatinine ratio", "mg/mg", "URINE", listOf("protein creatinine ratio", "upcr", "protein/creatinine")),
    BP_SYSTOLIC("BP systolic", "mmHg", "", listOf()),
    BP_DIASTOLIC("BP diastolic", "mmHg", "", listOf()),
    URINE_OUTPUT("Urine output (measured)", "ml", "", listOf()),
    OTHER("Other test", "", "", listOf());

    val isNumericTrendable: Boolean get() = this !in setOf(URINE_CULTURE, URINE_NITRITE, OTHER)

    companion object {
        fun of(code: String) = entries.firstOrNull { it.name == code } ?: OTHER
        val kidneyPanel = listOf(CREATININE, UREA, BUN, SODIUM, POTASSIUM, BICARBONATE, PHOSPHORUS, CALCIUM, CYSTATIN_C)
        val urinePanel = listOf(URINE_PUS_CELLS, URINE_RBC, URINE_PROTEIN, URINE_NITRITE, URINE_CULTURE, URINE_PCR)
    }
}

enum class ImagingParam(val label: String, val defaultUnit: String, val numeric: Boolean) {
    KIDNEY_LENGTH("Kidney length", "mm", true),
    APRPD("Renal pelvis AP diameter (APRPD)", "mm", true),
    HYDRONEPHROSIS_GRADE("Hydronephrosis grade (as reported)", "", false),
    HYDROURETER("Ureter diameter (hydroureter)", "mm", true),
    PARENCHYMA("Parenchymal thickness", "mm", true),
    CORTICOMEDULLARY("Corticomedullary differentiation", "", false),
    ECHOGENICITY("Echogenicity", "", false),
    BLADDER_WALL("Bladder wall thickness", "mm", true),
    PRE_VOID_VOLUME("Pre-void bladder volume", "ml", true),
    POST_VOID_RESIDUAL("Post-void residual urine", "ml", true),
    SPLIT_FUNCTION("Split renal function (DMSA)", "%", true),
    SCARRING("Scarring / photopenic areas (DMSA)", "", false),
    VUR_GRADE("Vesicoureteric reflux grade (MCU)", "", false),
    IMPRESSION("Impression / conclusion", "", false),
    OTHER("Other finding", "", false);

    companion object {
        fun of(code: String) = entries.firstOrNull { it.name == code } ?: OTHER
        fun forModality(m: String): List<ImagingParam> = when (m) {
            "DMSA" -> listOf(SPLIT_FUNCTION, SCARRING, KIDNEY_LENGTH, IMPRESSION, OTHER)
            "MCU" -> listOf(VUR_GRADE, BLADDER_WALL, POST_VOID_RESIDUAL, IMPRESSION, OTHER)
            else -> listOf(KIDNEY_LENGTH, APRPD, HYDRONEPHROSIS_GRADE, HYDROURETER, PARENCHYMA, CORTICOMEDULLARY, ECHOGENICITY, BLADDER_WALL, PRE_VOID_VOLUME, POST_VOID_RESIDUAL, IMPRESSION, OTHER)
        }
    }
}

enum class SymptomType(val label: String, val urgent: Boolean) {
    FEVER("Fever", false),
    VOMITING("Vomiting", false),
    POOR_FEEDING("Poor feeding", false),
    REDUCED_URINE("Reduced urine output", false),
    NO_URINE("Unable to pass urine", true),
    BLOOD_LIKE_URINE("Blood-like urine colour", false),
    FOUL_URINE("Foul-smelling urine", false),
    IRRITABLE("Unusually irritable / crying on passing urine", false),
    BREATHING_DIFFICULTY("Severe breathing difficulty", true),
    UNUSUAL_DROWSINESS("Unusual drowsiness / hard to wake", true),
    SEIZURE("Fits / seizure", true),
    DEHYDRATION("Signs of dehydration (dry mouth, no tears, sunken fontanelle)", true),
    SWELLING("Swelling (face, legs, abdomen)", false),
    DIARRHOEA("Diarrhoea", false),
    RASH("Rash", false),
    OTHER("Other", false);

    companion object { fun of(n: String) = entries.firstOrNull { it.name == n } ?: OTHER }
}
