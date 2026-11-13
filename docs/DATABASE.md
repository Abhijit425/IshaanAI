# Database schema

Room (SQLite) encrypted with **SQLCipher** (AES-256). The database passphrase is a random 256-bit value generated on first launch, stored only wrapped by a non-exportable Android Keystore key. Schema version: **1**. The exported schema JSON is generated at `app/schemas/` on every build.

Rules that apply to every table:
- Dates are epoch milliseconds (local wall time at entry); `dateOfBirthEpochDay` is a day number.
- `isSample = 1` marks fictional sample rows; removing sample data deletes them (cascading from the sample child).
- Foreign keys use `CASCADE` from the child profile, so nothing is orphaned; values extracted from a report use `SET NULL` so deleting a document never silently deletes results unless you choose to.
- Migrations are explicit (`AppDatabase.MIGRATIONS`). **Destructive migration is never enabled.**

| Table (entity) | Purpose | Key columns |
|---|---|---|
| `child_profile` (ChildProfile) | Name, DOB, sex (WHO charts), primary diagnosis, encrypted photo, birth weight, gestation, birth hospital, blood group | id |
| `doctor` (Doctor) | Doctors and specialities | id |
| `medical_event` (MedicalEvent) | Any history event: category (birth, diagnosis, cystoscopy, fulguration, catheter in/out, admission, NICU/ICU, UTI, blood infection, antibiotic course, complication, observation, follow-up…), title, description, date-time, hospital, doctor, notes, parentVerified | childId ↔ child_profile; idx dateTime, category |
| `attachment` (Attachment) | Encrypted documents/photos attached to events and other records | (ownerType, ownerId) |
| `diagnosis` (Diagnosis) | Doctor-made diagnoses with status | childId |
| `surgery` (Surgery) | Procedures as written by the surgeon, hospital, surgeon, indication, findings, outcome | childId; idx dateTime |
| `hospital_admission` (HospitalAdmission) | Admit/discharge, hospital, reason, NICU/PICU, treatment | childId |
| `medical_report` (MedicalReport) | Uploaded document: category, report date + whether OCR-detected, lab, encrypted original file, page count, **raw OCR text (never edited)**, OCR status, reviewed flag | childId; idx reportDate |
| `lab_result` (ExtractedLabResult) | One test value: test code, printed name, value text + number, unit, **reference range exactly as printed**, specimen, sample date, source (OCR/MANUAL/SAMPLE), verification (UNVERIFIED / VERIFIED / CORRECTED / PARENT_ENTERED), **originalOcrLine** and **originalValueText** (never changed) | reportId ↔ medical_report (SET NULL); idx (testCode, sampleDate) |
| `imaging_finding` (ImagingFinding) | Ultrasound / DMSA / MCU finding per side (RIGHT/LEFT/BLADDER): kidney length, pelvis APD, hydronephrosis grade (as reported), hydroureter, parenchyma, bladder wall, pre/post-void volume, split function, scarring, VUR grade, impression | reportId (SET NULL); idx (modality, studyDate) |
| `medication` (Medication) | Name, generic, strength, **prescribed dose as written**, unit, frequency, route, dates, doctor, instructions, active, needsConfirmation, discontinuation date + reason, prescription photo | childId |
| `medication_schedule` (MedicationSchedule) | Reminder times (minute of day) | medicationId (CASCADE) |
| `medication_log` (MedicationLog) | GIVEN / MISSED / SKIPPED with time | medicationId (CASCADE) |
| `urine_log` / `urine_media` | Observation + encrypted videos/photos | urineLogId (CASCADE) |
| `stool_log` / `stool_media` | Observation (optional Bristol type) + media | stoolLogId (CASCADE) |
| `growth_record` (GrowthRecord) | Weight, length/height, head circumference, home/clinic | childId; idx date |
| `feeding_log` (FeedingLog) | Breast/formula/solid/water, amount, duration, poor feeding, vomiting | childId |
| `sleep_log` (SleepLog) | Sleep start/end | childId |
| `milestone` (Milestone) | Developmental milestones | childId |
| `symptom_log` (SymptomLog) | Symptom type, temperature, parent's severity, resolved | childId |
| `appointment` (Appointment) | Date-time, doctor, speciality, hospital, purpose, tests to do, reminder offset, completed | childId |
| `health_alert` (HealthAlert) | Rule-generated alerts; unique `sourceKey` prevents duplicates; dismissed alerts stay dismissed | unique sourceKey |
| `backup_metadata` (BackupMetadata) | Log of backups, restores, PDF exports | — |

Original documents and extracted data stay linked through `reportId`; the Report screen shows both side by side.
