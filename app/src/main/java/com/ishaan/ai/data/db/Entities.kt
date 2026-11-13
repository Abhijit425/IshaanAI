package com.ishaan.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Conventions used across every entity
 * - Dates/times are epoch milliseconds (UTC) unless the name says "EpochDay".
 * - "verification" is one of Verification.* : nothing produced by OCR is trusted until the parent confirms it.
 * - "source" says where a value came from (Source.*), so reports can separate lab-confirmed values
 *   from parent observations.
 * - Original OCR text is stored once and never overwritten; corrections go into separate fields.
 */

object Verification {
    const val UNVERIFIED = "UNVERIFIED"   // came from OCR, parent has not checked it yet
    const val VERIFIED = "VERIFIED"       // parent checked it against the original document
    const val CORRECTED = "CORRECTED"     // parent changed the OCR value; the original is kept
    const val PARENT_ENTERED = "PARENT_ENTERED" // typed in by the parent
}

object Source {
    const val OCR = "OCR"
    const val MANUAL = "MANUAL"
    const val SAMPLE = "SAMPLE" // sample/test data, never shown unless sample mode is on
}

@Entity(tableName = "child_profile")
data class ChildProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dateOfBirthEpochDay: Long?,
    val sex: String = "M",                 // "M" or "F"; used only to choose WHO charts
    val primaryDiagnosis: String = "",
    val photoFile: String? = null,         // encrypted vault file name
    val bloodGroup: String = "",
    val birthWeightKg: Double? = null,
    val gestationWeeks: Int? = null,
    val birthHospital: String = "",
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "doctor")
data class Doctor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val specialty: String = "",
    val hospital: String = "",
    val phone: String = "",
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "medical_event",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime"), Index("category")],
)
data class MedicalEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val category: String,                  // EventCategory name
    val title: String,
    val description: String = "",
    val dateTime: Long,
    val hospitalName: String = "",
    val doctorName: String = "",
    val notes: String = "",
    val parentVerified: Boolean = true,
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Documents and photos attached to an event, medication, appointment, etc. Files are encrypted on disk. */
@Entity(tableName = "attachment", indices = [Index(value = ["ownerType", "ownerId"])])
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerType: String,                 // EVENT, MEDICATION, APPOINTMENT, SURGERY, ADMISSION
    val ownerId: Long,
    val fileName: String,                  // vault file name
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "diagnosis",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId")],
)
data class Diagnosis(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val name: String,
    val dateDiagnosed: Long? = null,
    val diagnosedBy: String = "",
    val hospital: String = "",
    val status: String = "ACTIVE",         // ACTIVE, RESOLVED, MONITORING
    val details: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "surgery",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class Surgery(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val procedureName: String,             // e.g. "Cystoscopy and PUV fulguration" — as written by the surgeon
    val dateTime: Long,
    val hospital: String = "",
    val surgeon: String = "",
    val indication: String = "",
    val findings: String = "",
    val outcome: String = "",
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "hospital_admission",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("admitDate")],
)
data class HospitalAdmission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val admitDate: Long,
    val dischargeDate: Long? = null,
    val hospital: String = "",
    val reason: String = "",
    val intensiveCare: String = "NONE",    // NONE, NICU, PICU
    val treatment: String = "",
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "medical_report",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("reportDate"), Index("category")],
)
data class MedicalReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val title: String,
    val category: String,                  // ReportCategory name
    val reportDate: Long? = null,
    val reportDateSource: String = Source.MANUAL, // OCR if detected from text, MANUAL if typed/confirmed
    val labName: String = "",
    val fileName: String,                  // original document, encrypted, never modified
    val originalName: String,
    val mimeType: String,
    val pageCount: Int = 1,
    val ocrText: String? = null,           // raw OCR output, never edited
    val ocrStatus: String = "NONE",        // NONE, DONE, FAILED, NO_TEXT
    val verified: Boolean = false,         // parent reviewed the extracted data
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "lab_result",
    foreignKeys = [
        ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MedicalReport::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("childId"), Index("reportId"), Index(value = ["testCode", "sampleDate"])],
)
data class ExtractedLabResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val reportId: Long? = null,
    val testCode: String,                  // LabTest code, e.g. CREATININE
    val displayName: String,               // name as printed on the report
    val valueText: String,                 // current value (parent-corrected if CORRECTED)
    val valueNumeric: Double? = null,
    val unit: String = "",
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val refText: String = "",              // reference range exactly as printed by the lab
    val specimen: String = "",             // SERUM, PLASMA, URINE, BLOOD
    val sampleDate: Long,
    val source: String = Source.MANUAL,
    val verification: String = Verification.PARENT_ENTERED,
    val originalOcrLine: String? = null,   // the OCR line it came from — never changed
    val originalValueText: String? = null, // the OCR value before any correction — never changed
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "imaging_finding",
    foreignKeys = [
        ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MedicalReport::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("childId"), Index("reportId"), Index(value = ["modality", "studyDate"])],
)
data class ImagingFinding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val reportId: Long? = null,
    val modality: String,                  // ULTRASOUND, DMSA, MCU, OTHER
    val studyDate: Long,
    val side: String,                      // RIGHT, LEFT, BLADDER, BOTH, NA
    val parameter: String,                 // ImagingParam code
    val valueNumeric: Double? = null,
    val valueText: String = "",            // e.g. "SFU grade 3", "photopenic area upper pole"
    val unit: String = "",
    val source: String = Source.MANUAL,
    val verification: String = Verification.PARENT_ENTERED,
    val originalOcrLine: String? = null,
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "medication",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("active")],
)
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val name: String,
    val genericName: String = "",
    val strength: String = "",             // as printed, e.g. "125 mg / 5 ml"
    val prescribedDose: String,            // exactly as the doctor wrote it — never calculated
    val doseUnit: String = "",
    val frequency: String = "",            // as written, e.g. "Once daily at night"
    val route: String = "",
    val startDate: Long? = null,
    val endDate: Long? = null,
    val prescribingDoctor: String = "",
    val specialInstructions: String = "",
    val active: Boolean = true,
    val needsConfirmation: Boolean = false, // prescription unclear: parent must confirm with doctor/pharmacist
    val discontinuedDate: Long? = null,
    val discontinuedReason: String = "",
    val prescriptionFile: String? = null,
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "medication_schedule",
    foreignKeys = [ForeignKey(entity = Medication::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicationId")],
)
data class MedicationSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val minuteOfDay: Int,                  // 0..1439, local time
    val enabled: Boolean = true,
)

@Entity(
    tableName = "medication_log",
    foreignKeys = [ForeignKey(entity = Medication::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicationId"), Index("actionTime")],
)
data class MedicationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduledTime: Long? = null,
    val actionTime: Long,
    val status: String,                    // GIVEN, MISSED, SKIPPED
    val note: String = "",
    val isSample: Boolean = false,
)

@Entity(
    tableName = "urine_log",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class UrineLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val dateTime: Long,
    val colour: String = "",               // parent's description
    val volumeMl: Double? = null,          // only if measured
    val volumeEstimate: String = "",       // e.g. "small", "wet diaper"
    val durationSec: Int? = null,
    val flow: String = "NOT_OBSERVED",     // GOOD_STREAM, WEAK, INTERRUPTED, DRIBBLING, NOT_OBSERVED
    val straining: Boolean = false,
    val cryingOrPain: Boolean = false,
    val cloudy: Boolean = false,
    val bloodLike: Boolean = false,
    val foulSmell: Boolean = false,
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "urine_media",
    foreignKeys = [ForeignKey(entity = UrineLog::class, parentColumns = ["id"], childColumns = ["urineLogId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("urineLogId")],
)
data class UrineMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val urineLogId: Long,
    val fileName: String,
    val mimeType: String,
    val kind: String,                      // PHOTO, VIDEO
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "stool_log",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class StoolLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val dateTime: Long,
    val bristolType: Int? = null,          // 1..7, optional, chosen by parent
    val consistency: String = "",
    val colour: String = "",
    val hard: Boolean = false,
    val loose: Boolean = false,
    val constipation: Boolean = false,
    val diarrhoea: Boolean = false,
    val bloodLike: Boolean = false,
    val mucus: Boolean = false,
    val feedingNotes: String = "",
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "stool_media",
    foreignKeys = [ForeignKey(entity = StoolLog::class, parentColumns = ["id"], childColumns = ["stoolLogId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("stoolLogId")],
)
data class StoolMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stoolLogId: Long,
    val fileName: String,
    val mimeType: String,
    val kind: String,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "growth_record",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("date")],
)
data class GrowthRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val date: Long,
    val weightKg: Double? = null,
    val lengthCm: Double? = null,          // recumbent length < 2y, standing height >= 2y
    val headCm: Double? = null,
    val measuredAt: String = "HOME",       // HOME, CLINIC
    val notes: String = "",
    val isSample: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "feeding_log",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class FeedingLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val dateTime: Long,
    val type: String,                      // BREAST, FORMULA, SOLID, WATER, OTHER
    val amountMl: Double? = null,
    val durationMin: Int? = null,
    val description: String = "",
    val poorFeeding: Boolean = false,
    val vomiting: Boolean = false,
    val notes: String = "",
    val isSample: Boolean = false,
)

@Entity(
    tableName = "sleep_log",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("startTime")],
)
data class SleepLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val notes: String = "",
    val isSample: Boolean = false,
)

@Entity(
    tableName = "milestone",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId")],
)
data class Milestone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val name: String,                      // e.g. "Holds head up", "Sits without support"
    val achievedDate: Long?,
    val notes: String = "",
    val isSample: Boolean = false,
)

@Entity(
    tableName = "symptom_log",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class SymptomLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val dateTime: Long,
    val symptom: String,                   // SymptomType name
    val temperatureC: Double? = null,
    val severity: String = "MILD",         // MILD, MODERATE, SEVERE (parent's judgement)
    val notes: String = "",
    val resolved: Boolean = false,
    val isSample: Boolean = false,
)

@Entity(
    tableName = "appointment",
    foreignKeys = [ForeignKey(entity = ChildProfile::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("childId"), Index("dateTime")],
)
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val dateTime: Long,
    val doctorName: String = "",
    val specialty: String = "",
    val hospital: String = "",
    val purpose: String = "",
    val testsToDo: String = "",            // e.g. "Serum creatinine, USG KUB before visit"
    val notes: String = "",
    val remindMinutesBefore: Int = 24 * 60,
    val completed: Boolean = false,
    val isSample: Boolean = false,
)

@Entity(
    tableName = "health_alert",
    indices = [Index("childId"), Index(value = ["sourceKey"], unique = true)],
)
data class HealthAlert(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val sourceKey: String,                 // stable key so the same rule doesn't duplicate alerts
    val severity: String,                  // INFO, REVIEW, URGENT
    val title: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
    val dismissed: Boolean = false,
)

@Entity(tableName = "backup_metadata")
data class BackupMetadata(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                      // BACKUP, RESTORE, EXPORT_PDF
    val createdAt: Long = System.currentTimeMillis(),
    val location: String = "",
    val sizeBytes: Long = 0,
    val recordCount: Int = 0,
    val status: String = "OK",
    val notes: String = "",
)
