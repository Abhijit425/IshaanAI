# Offline operation and "what is AI here?"

## Offline guarantees
- The app's manifest **removes the INTERNET and network-state permissions**, including any that libraries try to add. Android therefore blocks every network connection from the app. CI checks the built APK and fails if `android.permission.INTERNET` appears.
- No API keys, no accounts, no Firebase, no ads, no analytics. ML Kit's optional telemetry cannot be sent because the app has no network access.
- Android's automatic cloud backup and device-transfer are disabled; only your own encrypted backups exist.
- Everything works in airplane mode: records, OCR, charts, reports, PDF, reminders, backup/restore, the assistant and the optional local model.

How to verify on the phone: Settings → Apps → Ishaan AI → Permissions shows no network permission; turn on airplane mode and use every feature.

## Which features use what

| Feature | Technique | Notes |
|---|---|---|
| Reading text from PDFs/photos | **OCR** — Google ML Kit Text Recognition, *bundled* Latin model shipped inside the APK; PDFs rendered with Android `PdfRenderer` | Rows are re-assembled by position so table columns stay together |
| Finding tests, values, units, ranges, dates, report type, ultrasound/DMSA measurements | **Rule-based** parser (`domain/ReportParser.kt`) | Never invents values; every value stored UNVERIFIED with the OCR line it came from |
| Trends, change vs previous, comparison tables, graphs | **Rule-based** | Only compares values with the same unit |
| Reference ranges | **None built in** | Only the range printed on Ishaan's own report is shown, labelled "as printed"; the app never applies its own (adult or paediatric) ranges |
| eGFR | **Formula**: Bedside CKiD Schwartz 2009, `0.413 × height(cm) / creatinine(mg/dL)` | Calculated only if: DOB known, age ≥ 1 year at the test, creatinine verified and in mg/dL or µmol/L, height measured within 30 days. Otherwise the app says why it did not calculate. Never used to classify kidney function |
| Growth percentiles | **WHO Child Growth Standards** LMS tables (0–60 months), standard WHO z-score method | Chronological age; prematurity note shown |
| Daily/weekly/monthly/complete/doctor reports, missing-information list, questions for the doctor | **Rule-based** analysis engine | Each statement carries its date and source (verified / OCR-unverified / parent observation) |
| Alerts | **Rule-based** | Urgent symptoms show emergency guidance immediately; nothing waits for AI |
| Assistant (default) | **Rule-based** intent matching + database queries | Says "missing" when data isn't stored; refuses dose/diagnosis questions |
| Assistant (optional) | **On-device language model** via MediaPipe LLM Inference, only if you import a model file | Gets only the records retrieved by the rule engine; answers labelled "On-device AI model — check against records" with the records shown underneath |
| Urine/stool video & photo "analysis" | **Not implemented** | Deliberately: video cannot reliably detect infection, obstruction or valve problems, and the app does not classify media as normal/abnormal. Media are stored for you and the doctor to view |

## Installing the optional local model
1. On a computer or the phone's browser, download a MediaPipe LLM model file (e.g. *Gemma 3 1B IT int4* `.task` from Kaggle or Hugging Face — accept the licence there).
2. In Ishaan AI: Settings → Optional on-device AI model → Import model file, choose the `.task` file.
3. In the AI Assistant tab, switch "on-device model" on. Turn it off any time to use the rule-based engine only.

The app is fully functional without a model.
