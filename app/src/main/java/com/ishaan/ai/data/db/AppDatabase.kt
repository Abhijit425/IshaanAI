package com.ishaan.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ChildProfile::class, Doctor::class, MedicalEvent::class, Attachment::class, Diagnosis::class,
        Surgery::class, HospitalAdmission::class, MedicalReport::class, ExtractedLabResult::class,
        ImagingFinding::class, Medication::class, MedicationSchedule::class, MedicationLog::class,
        UrineLog::class, UrineMedia::class, StoolLog::class, StoolMedia::class, GrowthRecord::class,
        FeedingLog::class, SleepLog::class, Milestone::class, SymptomLog::class, Appointment::class,
        HealthAlert::class, BackupMetadata::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun historyDao(): HistoryDao
    abstract fun reportDao(): ReportDao
    abstract fun medicationDao(): MedicationDao
    abstract fun monitoringDao(): MonitoringDao
    abstract fun planDao(): PlanDao

    companion object {
        const val VERSION = 1
        const val NAME = "ishaan_health.db"

        /**
         * Tables in parent-before-child order. Used by backup/restore so foreign keys are satisfied.
         */
        val TABLES_IN_ORDER = listOf(
            "child_profile", "doctor", "medical_event", "attachment", "diagnosis", "surgery",
            "hospital_admission", "medical_report", "lab_result", "imaging_finding", "medication",
            "medication_schedule", "medication_log", "urine_log", "urine_media", "stool_log", "stool_media",
            "growth_record", "feeding_log", "sleep_log", "milestone", "symptom_log", "appointment",
            "health_alert", "backup_metadata",
        )

        /**
         * Schema changes must be added here as explicit migrations. Destructive migration is never
         * enabled: losing a child's medical history on an app update is not acceptable.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addMigrations(*MIGRATIONS)
                // Rollback journal instead of WAL: keeps backup/restore (ATTACH + sqlcipher_export) simple and safe.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
        }
    }
}
