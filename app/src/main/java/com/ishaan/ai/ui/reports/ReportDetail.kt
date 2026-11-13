package com.ishaan.ai.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.BedsideSchwartz
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.ReviewAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportDetailScreen(nav: NavHostController, id: Long) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val report = rememberFlow(id, null) { c.db.reportDao().observeReport(id) }
    val labs = rememberFlow(id, emptyList()) { c.db.reportDao().observeLabsForReport(id) }
    val imaging = rememberFlow(id, emptyList()) { c.db.reportDao().observeImagingForReport(id) }
    var editLab by remember { mutableStateOf<ExtractedLabResult?>(null) }
    var editImg by remember { mutableStateOf<ImagingFinding?>(null) }
    var showText by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rerunning by remember { mutableStateOf(false) }
    var editMeta by remember { mutableStateOf(false) }
    val r = report ?: return ScreenScaffold("Report", onBack = { nav.back() }) { Box(Modifier.padding(it)) { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
    val isImaging = r.category in setOf(ReportCategory.ULTRASOUND.name, ReportCategory.DMSA.name, ReportCategory.MCU.name)

    ScreenScaffold(r.title, onBack = { nav.back() }, actions = {
        IconButton(onClick = { editMeta = true }) { Icon(Icons.Default.Edit, "Edit details") }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(title = "Original document", icon = Icons.Default.Description) {
                    KeyValue("Type", ReportCategory.of(r.category).label)
                    KeyValue("Report date", Fmt.date(r.reportDate) + if (r.reportDateSource == Source.OCR && r.reportDate != null) " (detected by OCR)" else "")
                    if (r.labName.isNotBlank()) KeyValue("Lab / centre", r.labName)
                    KeyValue("File", "${r.originalName} · ${r.pageCount} page(s)")
                    if (r.notes.isNotBlank()) InfoBanner(r.notes, ReviewAmber)
                    if (r.mimeType.startsWith("image/")) { Spacer(Modifier.height(6.dp)); MediaStrip(listOf(MediaItem(r.fileName, r.mimeType, r.originalName))) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { val f = withContext(Dispatchers.IO) { c.vault.decryptToTemp(r.fileName, r.originalName) }; openExternally(ctx, f, r.mimeType) } }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("Open original") }
                        if (r.ocrStatus != "DONE" && (r.mimeType == "application/pdf" || r.mimeType.startsWith("image/"))) OutlinedButton(enabled = !rerunning, onClick = { scope.launch { rerunning = true; c.reports.runExtraction(r); rerunning = false } }) { Text("Run OCR") }
                    }
                    if (rerunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item {
                InfoBanner("Check each value against the original document. Tap a value to confirm or correct it. Unverified values are clearly labelled in every report and are never used to calculate eGFR.")
            }
            if (!isImaging || labs.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Extracted test results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { editLab = ExtractedLabResult(childId = r.childId, reportId = r.id, testCode = LabTest.CREATININE.name, displayName = "", valueText = "", sampleDate = r.reportDate ?: r.createdAt) }) { Text("+ Add") } } }
                if (labs.isEmpty()) item { EmptyState(if (r.ocrStatus == "DONE") "No test values were recognised. Add them manually from the document." else "No values yet.") }
                items(labs, key = { it.id }) { l -> LabRow(l, onClick = { editLab = l }, onVerify = { scope.launch { c.reports.verifyLab(l) } }) }
            }
            if (isImaging || imaging.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Imaging findings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); TextButton(onClick = { editImg = ImagingFinding(childId = r.childId, reportId = r.id, modality = when (r.category) { ReportCategory.DMSA.name -> "DMSA"; ReportCategory.MCU.name -> "MCU"; else -> "ULTRASOUND" }, studyDate = r.reportDate ?: r.createdAt, side = "RIGHT", parameter = ImagingParam.KIDNEY_LENGTH.name) }) { Text("+ Add") } } }
                if (imaging.isEmpty()) item { EmptyState("No findings recognised. Add right/left kidney measurements, pelvis diameter, split function etc. from the report.") }
                items(imaging, key = { it.id }) { f ->
                    AppCard(onClick = { editImg = f }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${f.side.lowercase().replaceFirstChar { it.uppercase() }} · ${ImagingParam.of(f.parameter).label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(f.valueNumeric?.let { "${Fmt.num(it)} ${f.unit}" } ?: f.valueText, style = MaterialTheme.typography.bodyMedium)
                            }
                            VerificationPill(f.verification)
                            if (f.verification == Verification.UNVERIFIED) IconButton(onClick = { scope.launch { c.reports.verifyImaging(f) } }) { Icon(Icons.Default.CheckCircle, "Confirm") }
                        }
                    }
                }
            }
            item {
                val allChecked = labs.none { it.verification == Verification.UNVERIFIED } && imaging.none { it.verification == Verification.UNVERIFIED }
                Button(onClick = { scope.launch { c.db.reportDao().updateReport(r.copy(verified = true)) } }, enabled = allChecked && !r.verified, modifier = Modifier.fillMaxWidth()) {
                    Text(if (r.verified) "Report reviewed" else if (allChecked) "Mark report as reviewed" else "Check every value to finish review")
                }
            }
            if (r.ocrText != null) item {
                AppCard(title = "OCR text (raw, unedited)", icon = Icons.Default.TextSnippet, trailing = { TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide" else "Show") } }) {
                    if (showText) SelectionContainer { Text(r.ocrText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    editLab?.let { LabEditor(it, onDismiss = { editLab = null }) }
    editImg?.let { ImagingEditor(it, onDismiss = { editImg = null }) }
    if (editMeta) MetaEditor(r, onDismiss = { editMeta = false })
    if (confirmDelete) {
        var alsoValues by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this report?") },
            text = { Column { Text("The original document will be permanently deleted from the phone."); CheckRow("Also delete the values extracted from it", alsoValues) { alsoValues = it } } },
            confirmButton = { TextButton(onClick = { scope.launch { c.reports.deleteReport(r, alsoValues); confirmDelete = false; nav.back() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun LabRow(l: ExtractedLabResult, onClick: () -> Unit, onVerify: (() -> Unit)? = null) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LabTest.of(l.testCode).let { if (it == LabTest.OTHER) l.displayName else it.label }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${l.valueText} ${l.unit}".trim(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(Fmt.date(l.sampleDate) + (if (l.refText.isNotBlank()) " · printed ref ${l.refText}" else ""), style = MaterialTheme.typography.bodySmall)
                if (l.verification == Verification.CORRECTED && l.originalValueText != null) Text("OCR originally read: ${l.originalValueText}", style = MaterialTheme.typography.labelSmall)
            }
            VerificationPill(l.verification)
            if (onVerify != null && l.verification == Verification.UNVERIFIED) IconButton(onClick = onVerify) { Icon(Icons.Default.CheckCircle, "Matches the document") }
        }
    }
}

@Composable
fun ImagingEditor(initial: ImagingFinding, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf(initial) }
    var num by remember { mutableStateOf(initial.valueNumeric?.let { Fmt.num(it) } ?: "") }
    val param = ImagingParam.of(f.parameter)
    EditorDialog(if (initial.id == 0L) "Add imaging finding" else "Check / correct finding", onDismiss,
        onSave = {
            scope.launch {
                val changed = initial.id != 0L && (num != (initial.valueNumeric?.let { Fmt.num(it) } ?: "") || f.valueText != initial.valueText || f.side != initial.side || f.parameter != initial.parameter)
                c.db.reportDao().upsertImaging(f.copy(
                    valueNumeric = if (param.numeric) num.replace(',', '.').toDoubleOrNull() else null,
                    verification = when { initial.id == 0L -> Verification.PARENT_ENTERED; initial.source == Source.OCR -> if (changed) Verification.CORRECTED else Verification.VERIFIED; else -> Verification.PARENT_ENTERED },
                ))
                onDismiss()
            }
        },
        onDelete = if (initial.id != 0L) ({ scope.launch { c.db.reportDao().deleteImaging(initial); onDismiss() } }) else null,
    ) {
        initial.originalOcrLine?.let { InfoBanner("Read by OCR from: \"$it\"", ReviewAmber) }
        Dropdown("Study type", listOf("ULTRASOUND", "DMSA", "MCU", "OTHER"), f.modality, { it }, { f = f.copy(modality = it) })
        DateTimeField("Study date", f.studyDate, { f = f.copy(studyDate = it) }, withTime = false)
        Dropdown("Side", listOf("RIGHT", "LEFT", "BLADDER", "BOTH", "NA"), f.side, { mapOf("RIGHT" to "Right kidney", "LEFT" to "Left kidney", "BLADDER" to "Bladder", "BOTH" to "Both", "NA" to "Not side-specific")[it]!! }, { f = f.copy(side = it) })
        Dropdown("Finding", ImagingParam.forModality(f.modality), param, { it.label }, { f = f.copy(parameter = it.name, unit = it.defaultUnit) })
        if (param.numeric) { Field("Value", num, { num = it }, number = true); Field("Unit", f.unit, { f = f.copy(unit = it) }) }
        Field(if (param.numeric) "Text as written (optional)" else "Finding as written in the report", f.valueText, { f = f.copy(valueText = it) }, singleLine = false, minLines = 2)
        Field("Notes", f.notes, { f = f.copy(notes = it) })
        Text("Use the radiologist's words. The app keeps imaging findings separate from lab values and does not grade or interpret them.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MetaEditor(r: MedicalReport, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(r.title) }
    var cat by remember { mutableStateOf(ReportCategory.of(r.category)) }
    var lab by remember { mutableStateOf(r.labName) }
    var date by remember { mutableStateOf(r.reportDate) }
    var notes by remember { mutableStateOf(r.notes) }
    EditorDialog("Report details", onDismiss, onSave = {
        scope.launch {
            c.db.reportDao().updateReport(r.copy(title = title, category = cat.name, labName = lab, notes = notes))
            val d = date
            if (d != null && d != r.reportDate) c.reports.setReportDate(c.db.reportDao().report(r.id)!!, Fmt.toLocalDate(d))
            onDismiss()
        }
    }) {
        Field("Title", title, { title = it })
        Dropdown("Type", ReportCategory.entries, cat, { it.label }, { cat = it })
        DateTimeField("Report / sample date", date, { date = it }, withTime = false)
        Text("Changing the date also re-dates every value extracted from this report.", style = MaterialTheme.typography.bodySmall)
        Field("Lab / imaging centre", lab, { lab = it })
        Field("Notes", notes, { notes = it }, singleLine = false, minLines = 2)
    }
}

@Composable
fun LabTrendScreen(nav: NavHostController, code: String) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val test = LabTest.of(code)
    val series = rememberFlow(code, emptyList()) { c.db.reportDao().observeLabSeries(p.id, code) }
    var edit by remember { mutableStateOf<ExtractedLabResult?>(null) }
    ScreenScaffold(if (test == LabTest.OTHER) "Other tests" else test.label, onBack = { nav.back() }, fab = {
        FloatingActionButton(onClick = { edit = ExtractedLabResult(childId = p.id, testCode = code, displayName = "", valueText = "", unit = test.defaultUnit, sampleDate = System.currentTimeMillis()) }) { Icon(Icons.Default.Add, "Add") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val unit = series.lastOrNull { it.valueNumeric != null }?.unit
            val pts = series.filter { it.valueNumeric != null && it.unit.equals(unit, true) }
            if (test.isNumericTrendable && pts.isNotEmpty()) item {
                AppCard(title = "Trend ($unit)", icon = Icons.Default.ShowChart) {
                    val last = pts.last()
                    TrendChart(pts.map { ChartPoint(it.sampleDate, it.valueNumeric!!, it.verification == Verification.UNVERIFIED) }, refLow = last.refLow, refHigh = last.refHigh)
                    Text("Hollow points = OCR, not yet verified. Shaded band = range printed on the latest report (may not be age-specific).", style = MaterialTheme.typography.labelSmall)
                    if (series.size != pts.size) Text("${series.size - pts.size} result(s) in other units or non-numeric are listed below but not charted.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (test == LabTest.CREATININE) item {
                val sec = c.engine.egfrSection(com.ishaan.ai.domain.HealthSnapshot(profile = p, labs = series, growth = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeGrowth(p.id) }))
                AppCard(title = "Estimated GFR", icon = Icons.Default.Calculate) {
                    sec.findings.forEach { Text("• " + it.text, style = MaterialTheme.typography.bodySmall) }
                    Text(sec.note ?: "", style = MaterialTheme.typography.labelSmall)
                    Text("Equation: ${BedsideSchwartz.NAME}", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                AppCard(title = "Comparison") {
                    TableView(c.engine.labTrendSection(com.ishaan.ai.domain.HealthSnapshot(profile = p, labs = series), listOf(test), null, "").table
                        ?: com.ishaan.ai.domain.Table(listOf("Date", "Result"), emptyList()))
                }
            }
            items(series.reversed(), key = { it.id }) { l -> LabRow(l, onClick = { edit = l }) }
        }
    }
    edit?.let { LabEditor(it, onDismiss = { edit = null }) }
}
