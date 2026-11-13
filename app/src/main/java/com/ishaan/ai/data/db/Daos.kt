package com.ishaan.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM child_profile ORDER BY isSample ASC, id ASC LIMIT 1")
    fun observeProfile(): Flow<ChildProfile?>

    @Query("SELECT * FROM child_profile ORDER BY isSample ASC, id ASC LIMIT 1")
    suspend fun getProfile(): ChildProfile?

    @Upsert suspend fun upsert(p: ChildProfile): Long
    @Query("SELECT COUNT(*) FROM child_profile") suspend fun count(): Int
    @Query("DELETE FROM child_profile WHERE isSample = 1") suspend fun deleteSampleProfiles()
    @Query("SELECT COUNT(*) FROM child_profile WHERE isSample = 1") fun observeSampleCount(): Flow<Int>

    @Query("SELECT * FROM doctor ORDER BY name") fun observeDoctors(): Flow<List<Doctor>>
    @Upsert suspend fun upsertDoctor(d: Doctor): Long
    @Delete suspend fun deleteDoctor(d: Doctor)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM medical_event WHERE childId = :childId ORDER BY dateTime DESC")
    fun observeEvents(childId: Long): Flow<List<MedicalEvent>>
    @Query("SELECT * FROM medical_event WHERE childId = :childId ORDER BY dateTime ASC")
    suspend fun events(childId: Long): List<MedicalEvent>
    @Query("SELECT * FROM medical_event WHERE id = :id") suspend fun event(id: Long): MedicalEvent?
    @Upsert suspend fun upsertEvent(e: MedicalEvent): Long
    @Delete suspend fun deleteEvent(e: MedicalEvent)

    @Query("SELECT * FROM attachment WHERE ownerType = :type AND ownerId = :id ORDER BY createdAt")
    fun observeAttachments(type: String, id: Long): Flow<List<Attachment>>
    @Query("SELECT * FROM attachment WHERE ownerType = :type AND ownerId = :id")
    suspend fun attachments(type: String, id: Long): List<Attachment>
    @Insert suspend fun insertAttachment(a: Attachment): Long
    @Delete suspend fun deleteAttachment(a: Attachment)
    @Query("SELECT * FROM attachment") suspend fun allAttachments(): List<Attachment>

    @Query("SELECT * FROM diagnosis WHERE childId = :childId ORDER BY dateDiagnosed")
    fun observeDiagnoses(childId: Long): Flow<List<Diagnosis>>
    @Query("SELECT * FROM diagnosis WHERE childId = :childId ORDER BY dateDiagnosed")
    suspend fun diagnoses(childId: Long): List<Diagnosis>
    @Upsert suspend fun upsertDiagnosis(d: Diagnosis): Long
    @Delete suspend fun deleteDiagnosis(d: Diagnosis)

    @Query("SELECT * FROM surgery WHERE childId = :childId ORDER BY dateTime")
    fun observeSurgeries(childId: Long): Flow<List<Surgery>>
    @Query("SELECT * FROM surgery WHERE childId = :childId ORDER BY dateTime")
    suspend fun surgeries(childId: Long): List<Surgery>
    @Upsert suspend fun upsertSurgery(s: Surgery): Long
    @Delete suspend fun deleteSurgery(s: Surgery)

    @Query("SELECT * FROM hospital_admission WHERE childId = :childId ORDER BY admitDate")
    fun observeAdmissions(childId: Long): Flow<List<HospitalAdmission>>
    @Query("SELECT * FROM hospital_admission WHERE childId = :childId ORDER BY admitDate")
    suspend fun admissions(childId: Long): List<HospitalAdmission>
    @Upsert suspend fun upsertAdmission(a: HospitalAdmission): Long
    @Delete suspend fun deleteAdmission(a: HospitalAdmission)
}

@Dao
interface ReportDao {
    @Query("SELECT * FROM medical_report WHERE childId = :childId ORDER BY COALESCE(reportDate, createdAt) DESC")
    fun observeReports(childId: Long): Flow<List<MedicalReport>>
    @Query("SELECT * FROM medical_report WHERE childId = :childId ORDER BY COALESCE(reportDate, createdAt) ASC")
    suspend fun reports(childId: Long): List<MedicalReport>
    @Query("SELECT * FROM medical_report WHERE id = :id") fun observeReport(id: Long): Flow<MedicalReport?>
    @Query("SELECT * FROM medical_report WHERE id = :id") suspend fun report(id: Long): MedicalReport?
    @Insert suspend fun insertReport(r: MedicalReport): Long
    @Update suspend fun updateReport(r: MedicalReport)
    @Delete suspend fun deleteReport(r: MedicalReport)

    @Query("SELECT * FROM lab_result WHERE childId = :childId ORDER BY sampleDate DESC")
    fun observeLabs(childId: Long): Flow<List<ExtractedLabResult>>
    @Query("SELECT * FROM lab_result WHERE childId = :childId ORDER BY sampleDate ASC")
    suspend fun labs(childId: Long): List<ExtractedLabResult>
    @Query("SELECT * FROM lab_result WHERE reportId = :reportId ORDER BY id")
    fun observeLabsForReport(reportId: Long): Flow<List<ExtractedLabResult>>
    @Query("SELECT * FROM lab_result WHERE childId = :childId AND testCode = :code ORDER BY sampleDate ASC")
    fun observeLabSeries(childId: Long, code: String): Flow<List<ExtractedLabResult>>
    @Upsert suspend fun upsertLab(l: ExtractedLabResult): Long
    @Insert suspend fun insertLabs(l: List<ExtractedLabResult>)
    @Delete suspend fun deleteLab(l: ExtractedLabResult)

    @Query("SELECT * FROM imaging_finding WHERE childId = :childId ORDER BY studyDate DESC, modality, side")
    fun observeImaging(childId: Long): Flow<List<ImagingFinding>>
    @Query("SELECT * FROM imaging_finding WHERE childId = :childId ORDER BY studyDate ASC")
    suspend fun imaging(childId: Long): List<ImagingFinding>
    @Query("SELECT * FROM imaging_finding WHERE reportId = :reportId ORDER BY side, parameter")
    fun observeImagingForReport(reportId: Long): Flow<List<ImagingFinding>>
    @Upsert suspend fun upsertImaging(f: ImagingFinding): Long
    @Insert suspend fun insertImaging(f: List<ImagingFinding>)
    @Delete suspend fun deleteImaging(f: ImagingFinding)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication WHERE childId = :childId ORDER BY active DESC, name")
    fun observeMedications(childId: Long): Flow<List<Medication>>
    @Query("SELECT * FROM medication WHERE childId = :childId ORDER BY COALESCE(startDate, createdAt)")
    suspend fun medications(childId: Long): List<Medication>
    @Query("SELECT * FROM medication WHERE id = :id") suspend fun medication(id: Long): Medication?
    @Query("SELECT * FROM medication WHERE id = :id") fun observeMedication(id: Long): Flow<Medication?>
    @Upsert suspend fun upsert(m: Medication): Long
    @Delete suspend fun delete(m: Medication)

    @Query("SELECT * FROM medication_schedule WHERE medicationId = :medId ORDER BY minuteOfDay")
    fun observeSchedules(medId: Long): Flow<List<MedicationSchedule>>
    @Query("SELECT * FROM medication_schedule WHERE medicationId = :medId ORDER BY minuteOfDay")
    suspend fun schedules(medId: Long): List<MedicationSchedule>
    @Query("SELECT s.* FROM medication_schedule s JOIN medication m ON m.id = s.medicationId WHERE m.active = 1 AND s.enabled = 1")
    suspend fun activeSchedules(): List<MedicationSchedule>
    @Insert suspend fun insertSchedule(s: MedicationSchedule): Long
    @Delete suspend fun deleteSchedule(s: MedicationSchedule)

    @Query("SELECT * FROM medication_log WHERE medicationId = :medId ORDER BY actionTime DESC")
    fun observeLogs(medId: Long): Flow<List<MedicationLog>>
    @Query("SELECT l.* FROM medication_log l JOIN medication m ON m.id = l.medicationId WHERE m.childId = :childId AND l.actionTime >= :since ORDER BY l.actionTime")
    suspend fun logsSince(childId: Long, since: Long): List<MedicationLog>
    @Query("SELECT l.* FROM medication_log l JOIN medication m ON m.id = l.medicationId WHERE m.childId = :childId AND l.actionTime >= :since ORDER BY l.actionTime")
    fun observeLogsSince(childId: Long, since: Long): Flow<List<MedicationLog>>
    @Insert suspend fun insertLog(l: MedicationLog): Long
    @Delete suspend fun deleteLog(l: MedicationLog)
}

@Dao
interface MonitoringDao {
    @Query("SELECT * FROM urine_log WHERE childId = :childId ORDER BY dateTime DESC")
    fun observeUrine(childId: Long): Flow<List<UrineLog>>
    @Query("SELECT * FROM urine_log WHERE childId = :childId AND dateTime >= :since ORDER BY dateTime")
    suspend fun urineSince(childId: Long, since: Long): List<UrineLog>
    @Query("SELECT * FROM urine_log WHERE id = :id") suspend fun urine(id: Long): UrineLog?
    @Upsert suspend fun upsertUrine(u: UrineLog): Long
    @Delete suspend fun deleteUrine(u: UrineLog)
    @Query("SELECT * FROM urine_media WHERE urineLogId = :logId") fun observeUrineMedia(logId: Long): Flow<List<UrineMedia>>
    @Query("SELECT * FROM urine_media WHERE urineLogId = :logId") suspend fun urineMedia(logId: Long): List<UrineMedia>
    @Query("SELECT * FROM urine_media") suspend fun allUrineMedia(): List<UrineMedia>
    @Insert suspend fun insertUrineMedia(m: UrineMedia): Long
    @Delete suspend fun deleteUrineMedia(m: UrineMedia)

    @Query("SELECT * FROM stool_log WHERE childId = :childId ORDER BY dateTime DESC")
    fun observeStool(childId: Long): Flow<List<StoolLog>>
    @Query("SELECT * FROM stool_log WHERE childId = :childId AND dateTime >= :since ORDER BY dateTime")
    suspend fun stoolSince(childId: Long, since: Long): List<StoolLog>
    @Query("SELECT * FROM stool_log WHERE id = :id") suspend fun stool(id: Long): StoolLog?
    @Upsert suspend fun upsertStool(s: StoolLog): Long
    @Delete suspend fun deleteStool(s: StoolLog)
    @Query("SELECT * FROM stool_media WHERE stoolLogId = :logId") fun observeStoolMedia(logId: Long): Flow<List<StoolMedia>>
    @Query("SELECT * FROM stool_media WHERE stoolLogId = :logId") suspend fun stoolMedia(logId: Long): List<StoolMedia>
    @Query("SELECT * FROM stool_media") suspend fun allStoolMedia(): List<StoolMedia>
    @Insert suspend fun insertStoolMedia(m: StoolMedia): Long
    @Delete suspend fun deleteStoolMedia(m: StoolMedia)

    @Query("SELECT * FROM growth_record WHERE childId = :childId ORDER BY date DESC")
    fun observeGrowth(childId: Long): Flow<List<GrowthRecord>>
    @Query("SELECT * FROM growth_record WHERE childId = :childId ORDER BY date ASC")
    suspend fun growth(childId: Long): List<GrowthRecord>
    @Upsert suspend fun upsertGrowth(g: GrowthRecord): Long
    @Delete suspend fun deleteGrowth(g: GrowthRecord)

    @Query("SELECT * FROM feeding_log WHERE childId = :childId ORDER BY dateTime DESC")
    fun observeFeeding(childId: Long): Flow<List<FeedingLog>>
    @Query("SELECT * FROM feeding_log WHERE childId = :childId AND dateTime >= :since ORDER BY dateTime")
    suspend fun feedingSince(childId: Long, since: Long): List<FeedingLog>
    @Upsert suspend fun upsertFeeding(f: FeedingLog): Long
    @Delete suspend fun deleteFeeding(f: FeedingLog)

    @Query("SELECT * FROM sleep_log WHERE childId = :childId ORDER BY startTime DESC")
    fun observeSleep(childId: Long): Flow<List<SleepLog>>
    @Upsert suspend fun upsertSleep(s: SleepLog): Long
    @Delete suspend fun deleteSleep(s: SleepLog)

    @Query("SELECT * FROM milestone WHERE childId = :childId ORDER BY achievedDate")
    fun observeMilestones(childId: Long): Flow<List<Milestone>>
    @Query("SELECT * FROM milestone WHERE childId = :childId ORDER BY achievedDate")
    suspend fun milestones(childId: Long): List<Milestone>
    @Upsert suspend fun upsertMilestone(m: Milestone): Long
    @Delete suspend fun deleteMilestone(m: Milestone)

    @Query("SELECT * FROM symptom_log WHERE childId = :childId ORDER BY dateTime DESC")
    fun observeSymptoms(childId: Long): Flow<List<SymptomLog>>
    @Query("SELECT * FROM symptom_log WHERE childId = :childId AND dateTime >= :since ORDER BY dateTime")
    suspend fun symptomsSince(childId: Long, since: Long): List<SymptomLog>
    @Upsert suspend fun upsertSymptom(s: SymptomLog): Long
    @Delete suspend fun deleteSymptom(s: SymptomLog)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM appointment WHERE childId = :childId ORDER BY dateTime ASC")
    fun observeAppointments(childId: Long): Flow<List<Appointment>>
    @Query("SELECT * FROM appointment WHERE childId = :childId ORDER BY dateTime ASC")
    suspend fun appointments(childId: Long): List<Appointment>
    @Query("SELECT * FROM appointment WHERE completed = 0 AND dateTime > :now")
    suspend fun upcomingAppointments(now: Long): List<Appointment>
    @Query("SELECT * FROM appointment WHERE id = :id") suspend fun appointment(id: Long): Appointment?
    @Upsert suspend fun upsertAppointment(a: Appointment): Long
    @Delete suspend fun deleteAppointment(a: Appointment)

    @Query("SELECT * FROM health_alert WHERE childId = :childId AND dismissed = 0 ORDER BY CASE severity WHEN 'URGENT' THEN 0 WHEN 'REVIEW' THEN 1 ELSE 2 END, createdAt DESC")
    fun observeActiveAlerts(childId: Long): Flow<List<HealthAlert>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlert(a: HealthAlert): Long
    @Query("UPDATE health_alert SET dismissed = 1 WHERE id = :id") suspend fun dismissAlert(id: Long)
    @Query("DELETE FROM health_alert WHERE childId = :childId AND dismissed = 0 AND sourceKey LIKE 'rule:%'")
    suspend fun clearActiveRuleAlerts(childId: Long)
    @Query("SELECT sourceKey FROM health_alert WHERE childId = :childId AND dismissed = 1")
    suspend fun dismissedKeys(childId: Long): List<String>

    @Insert suspend fun insertBackupMeta(b: BackupMetadata): Long
    @Query("SELECT * FROM backup_metadata ORDER BY createdAt DESC LIMIT 20") fun observeBackupMeta(): Flow<List<BackupMetadata>>
    @Query("SELECT * FROM backup_metadata WHERE type = 'BACKUP' AND status = 'OK' ORDER BY createdAt DESC LIMIT 1")
    fun observeLastBackup(): Flow<BackupMetadata?>
}
