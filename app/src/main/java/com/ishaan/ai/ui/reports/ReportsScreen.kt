package com.ishaan.ai.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.Table
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.ReviewAmber
import kotlinx.coroutines.launch

@Composable
fun ReportsScreen(nav: NavHostController) = WithProfile(nav) { p -> Reports(nav, p) }

@Composable
private fun Reports(nav: NavHostController, p: ChildProfile) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var upload by remember { mutableStateOf(false) }
    var addLab by remember { mutableStateOf(false) }
    ScreenScaffold("Reports", actions = {
        TextButton(onClick = { nav.navigate("kidney") }) { Icon(Icons.Default.Favorite, null); Spacer(Modifier.width(4.dp)); Text("Kidney") }
    }, fab = {
        ExtendedFloatingActionButton(onClick = { if (tab == 0) upload = true else addLab = true }, icon = { Icon(if (tab == 0) Icons.Default.UploadFile else Icons.Default.Add, null) }, text = { Text(if (tab == 0) "Upload" else "Add value") })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Documents") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Lab values") })
            }
            if (tab == 0) DocumentsTab(nav, p) else LabValuesTab(nav, p)
        }
    }
    if (upload) UploadDialog(p, onDismiss = { upload = false }, onDone = { id -> upload = false; nav.navigate("report/$id") })
    if (addLab) LabEditor(ExtractedLabResult(childId = p.id, testCode = LabTest.CREATININE.name, displayName = "", valueText = "", sampleDate = System.currentTimeMillis()), onDismiss = { addLab = false })
}

@Composable
private fun DocumentsTab(nav: NavHostController, p: ChildProfile) {
    val c = LocalContainer.current
    val reports = rememberFlow(p.id, emptyList()) { c.db.reportDao().observeReports(p.id) }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<ReportCategory?>(null) }
    val shown = reports.filter { (cat == null || it.category == cat!!.name) && (query.isBlank() || "${it.title} ${it.labName} ${it.originalName} ${it.ocrText ?: ""}".contains(query, true)) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Field("Search reports (including their text)", query, { query = it }) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(cat == null, { cat = null }, { Text("All") }) }
                items(ReportCategory.entries.filter { rc -> reports.any { it.category == rc.name } }) { rc -> FilterChip(cat == rc, { cat = rc }, { Text(rc.label) }) }
            }
        }
        if (shown.isEmpty()) item { EmptyState("No reports yet. Upload PDFs or photos of blood tests, urine cultures, ultrasound, DMSA, prescriptions and discharge summaries.") }
        items(shown, key = { it.id }) { r ->
            AppCard(onClick = { nav.navigate("report/${r.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.mimeType == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${ReportCategory.of(r.category).label} · ${Fmt.date(r.reportDate)}", style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        r.ocrStatus == "DONE" && !r.verified -> Pill("Needs review", ReviewAmber)
                        r.verified -> Pill("Reviewed", com.ishaan.ai.ui.theme.OkGreen)
                        r.ocrStatus == "FAILED" || r.ocrStatus == "NO_TEXT" -> Pill("Not readable", MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun LabValuesTab(nav: NavHostController, p: ChildProfile) {
    val c = LocalContainer.current
    val labs = rememberFlow(p.id, emptyList()) { c.db.reportDao().observeLabs(p.id) }
    val grouped = labs.groupBy { it.testCode }.toList().sortedBy { LabTest.of(it.first).ordinal }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (grouped.isEmpty()) item { EmptyState("No values yet. They appear here after you upload a report, or add them manually.") }
        items(grouped) { (code, list) ->
            val latest = list.maxBy { it.sampleDate }
            val t = LabTest.of(code)
            AppCard(onClick = { nav.navigate("lab/$code") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (t == LabTest.OTHER) "Other tests" else t.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Latest ${latest.valueText} ${latest.unit} · ${Fmt.date(latest.sampleDate)} · ${list.size} result(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    if (list.any { it.verification == Verification.UNVERIFIED }) Pill("Unverified", ReviewAmber)
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun UploadDialog(p: ChildProfile, onDismiss: () -> Unit, onDone: (Long) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(ReportCategory.OTHER) }
    var ocr by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickers = rememberMediaPickers(arrayOf("application/pdf", "image/jpeg", "image/png", "image/*")) { uris ->
        scope.launch {
            busy = true; error = null
            var lastId = 0L
            for ((u, _) in uris) {
                val out = runCatching { c.reports.importReport(p.id, u, if (uris.size > 1) "" else title, cat.takeIf { it != ReportCategory.OTHER }, ocr) }
                    .getOrElse { error = "Could not save the file: ${it.message}"; null } ?: continue
                lastId = out.reportId
                out.error?.let { error = it }
            }
            busy = false
            if (lastId != 0L && error == null) onDone(lastId) else if (lastId != 0L) { /* keep dialog to show message */ }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Upload medical report") },
        text = {
            Column {
                Field("Title (optional)", title, { title = it })
                Dropdown("Type (auto-detected if left as Other)", ReportCategory.entries, cat, { it.label }, { cat = it })
                CheckRow("Read text with on-device OCR", ocr) { ocr = it }
                Text("The original file is stored encrypted and never changed. Extracted values are marked unverified until you check them.", style = MaterialTheme.typography.bodySmall)
                if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Encrypting and reading…", style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = pickers.pick, enabled = !busy) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text("Choose file") }
                    OutlinedButton(onClick = pickers.photo, enabled = !busy) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(4.dp)); Text("Scan") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}

/** Add or edit a single lab value. OCR originals stay untouched; edits are recorded as CORRECTED. */
@Composable
fun LabEditor(initial: ExtractedLabResult, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf(LabTest.of(initial.testCode)) }
    var name by remember { mutableStateOf(initial.displayName) }
    var value by remember { mutableStateOf(initial.valueText) }
    var unit by remember { mutableStateOf(initial.unit.ifBlank { LabTest.of(initial.testCode).defaultUnit }) }
    var ref by remember { mutableStateOf(initial.refText) }
    var date by remember { mutableLongStateOf(initial.sampleDate) }
    var notes by remember { mutableStateOf(initial.notes) }
    EditorDialog(
        if (initial.id == 0L) "Add test value" else "Check / correct value", onDismiss,
        onSave = {
            scope.launch {
                if (initial.id == 0L) {
                    val r = com.ishaan.ai.domain.ReportParser.parseRange(ref)
                    c.db.reportDao().upsertLab(initial.copy(testCode = test.name, displayName = name.ifBlank { test.label }, valueText = value.trim(), valueNumeric = value.replace(',', '.').trim().toDoubleOrNull(), unit = unit.trim(), refText = ref.trim(), refLow = r.first, refHigh = r.second, specimen = test.specimen, sampleDate = date, notes = notes, source = Source.MANUAL, verification = Verification.PARENT_ENTERED))
                } else {
                    c.reports.correctLab(initial.copy(sampleDate = date, notes = notes, displayName = name), value, unit, ref, test.name)
                }
                onDismiss()
            }
        },
        saveEnabled = value.isNotBlank(),
        onDelete = if (initial.id != 0L) ({ scope.launch { c.db.reportDao().deleteLab(initial); onDismiss() } }) else null,
    ) {
        if (initial.originalOcrLine != null) {
            InfoBanner("Read by OCR from: \"${initial.originalOcrLine}\"\nOriginal value: ${initial.originalValueText}. Compare with the document and correct if needed — the original reading is kept.", ReviewAmber)
        }
        Dropdown("Test", LabTest.entries, test, { it.label }, { test = it; if (unit.isBlank()) unit = it.defaultUnit })
        if (test == LabTest.OTHER) Field("Test name", name, { name = it })
        Field("Result (number or text, e.g. 0.42 or 'No growth')", value, { value = it })
        Field("Unit", unit, { unit = it })
        Field("Reference range exactly as printed (optional)", ref, { ref = it })
        DateTimeField("Sample date", date, { date = it }, withTime = false)
        Field("Notes (e.g. organism, sensitivity, method)", notes, { notes = it }, singleLine = false, minLines = 2)
        Text("Don't enter ranges from the internet — only what is printed on Ishaan's report.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TableView(t: Table) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.padding(vertical = 4.dp)) { t.headers.forEach { h -> Text(h, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(118.dp).padding(end = 6.dp)) } }
        HorizontalDivider()
        t.rows.forEach { r ->
            Row(Modifier.padding(vertical = 4.dp)) { r.forEach { v -> Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(118.dp).padding(end = 6.dp)) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
