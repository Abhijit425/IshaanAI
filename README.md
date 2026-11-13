# Ishaan AI – Personal Pediatric Health Companion

An **offline-first Android app** (Kotlin, Jetpack Compose, Material 3) that keeps Ishaan's complete medical journey — history from birth, reports, kidney monitoring for Posterior Urethral Valves (PUV), medicines, urine/potty observations, growth — in one encrypted place on the phone.

> **It is a record-keeping and monitoring companion, not a doctor.** It never diagnoses, never says whether a result is "normal", never calculates or changes doses, and never advises starting or stopping medicines. In an emergency, go to emergency care — don't wait for the app.

## Install

1. Open the repository's **Releases → latest** page on the phone (or the Actions run → *IshaanAI-apk* artifact).
2. Download **IshaanAI-release.apk** and open it. Allow "Install unknown apps" for your browser/file manager when Android asks.
3. Open Ishaan AI, read the welcome notes, and set a PIN.
4. Create Ishaan's profile (name, date of birth, diagnosis). Nothing is pre-filled.

**Updates:** every build is signed with the same key (`keystore/ishaan-debug.jks`), so a new APK installs *over* the old one and keeps all records. **Never uninstall the app to update** — uninstalling deletes the data. Make a backup first anyway.

> The signing key lives in this repository for convenience. Keep the repository **private**. If you ever want a secret key instead, set the `ISHAAN_KEYSTORE`, `ISHAAN_STORE_PASSWORD`, `ISHAAN_KEY_ALIAS`, `ISHAAN_KEY_PASSWORD` environment variables in CI — but note a different key cannot update an app signed with the current one.

## Build it yourself

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```bash
./gradlew testDebugUnitTest      # unit, database (Robolectric) and UI tests
./gradlew assembleDebug assembleRelease
# APKs: app/build/outputs/apk/{debug,release}/
```

GitHub Actions (`.github/workflows/build.yml`) runs the tests, builds both APKs, **fails the build if the APK contains the INTERNET permission**, and publishes the APKs to the `latest` release.

## Features

| Area | What it does |
|---|---|
| Dashboard | Photo, name, DOB, automatic age, weight/height, diagnosis, surgery count, medicines; alerts; widgets for kidney overview, medicines, urine, potty, growth, reports, appointments, AI summary; emergency warning signs; backup reminder |
| Medical history | Events (birth, diagnosis, cystoscopy, fulguration, catheter in/out, admissions, NICU/ICU, UTIs, blood infection, antibiotics, complications, doctor observations, follow-ups) with date/time, hospital, doctor, description, notes, attached documents and photos; surgeries; admissions; diagnoses; searchable chronological timeline from birth |
| Reports | Upload PDF/JPG/PNG or scan with the camera; encrypted original kept unchanged; on-device OCR; auto category, date, test name, result, unit and printed reference range extraction; every extracted value **unverified until you confirm it**; corrections keep the original OCR reading; raw OCR text viewable |
| Comparisons | Per-test tables (Date / Result / Change vs previous / Reference as printed / Status) and graphs |
| Kidney (PUV) | Creatinine, urea, BUN, electrolytes, bicarbonate, phosphorus, urine tests, BP, urine output; ultrasound (kidney length, pelvis APD, hydronephrosis grade as reported, hydroureter, bladder wall, residual urine) and DMSA (split function, scarring) — right and left kidneys separately; side-by-side comparison tables; eGFR only when valid (see docs) |
| Urine | Record/upload videos and photos, colour, flow, volume, duration, straining, notes; 7-day view; videos preserved; clear message that video needs clinical interpretation |
| Potty | Photos/videos, consistency, colour, hard/loose, constipation, diarrhoea, blood-like, feeding notes, optional Bristol chart |
| Medicines | Name, generic, strength, dose exactly as prescribed, unit, frequency, dates, doctor, prescription photo, instructions; reminders; given/missed/skipped log; stop history; "prescription unclear" flag |
| Growth | Weight, length/height, head circumference with WHO Child Growth Standards percentile curves and z-scores; feeding, sleep, milestones |
| Health reports | Daily, weekly, monthly, complete summary, doctor consultation report — with dates, sources, verification status, missing information and questions for the doctor; export/share as PDF |
| Assistant | "Ishaan Health Assistant" answers from the local database only; says when information is missing; optional on-device language model |
| Alerts | Medicine and appointment reminders; parent-recorded fever, reduced urine, blood-like urine, vomiting/poor feeding; emergency guidance for urgent signs; follow-up gaps |
| Security | SQLCipher-encrypted database, AES-256-GCM encrypted files, PIN, fingerprint/face, auto-lock, screenshot blocking, encrypted backup/restore, PDF export, deletion confirmations, no ads/analytics/cloud |

More detail:
- [docs/DATABASE.md](docs/DATABASE.md) — schema
- [docs/OFFLINE_AND_AI.md](docs/OFFLINE_AND_AI.md) — offline guarantees; which features use OCR, rules and the optional local AI
- [docs/BACKUP_RESTORE.md](docs/BACKUP_RESTORE.md) — backing up and restoring Ishaan's records

## Trying it without real data

Settings → *Load sample data* creates a clearly labelled fictional child (only possible while the app is empty). Remove it with *Remove sample data* before entering Ishaan's records. The production dashboard never shows hard-coded values.

## Project layout

```
app/src/main/java/com/ishaan/ai/
  data/db/        Room entities, DAOs, database (SQLCipher)
  data/           OCR, report import, PDF builder, backup, sample data, settings
  domain/         Report parser, eGFR + WHO growth maths, analysis engine, alerts, assistant, local LLM wrapper
  security/       Keystore key manager, chunked AES-GCM, encrypted vault
  reminders/      AlarmManager reminders, boot receiver
  ui/             Compose screens (home, history, reports, monitoring, meds, assistant, settings)
app/src/main/assets/who_lms.csv   WHO LMS tables (0–60 months)
app/src/test/                      unit, Robolectric database and Compose UI tests
```
