package com.ishaan.ai.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.TimelineItem
import com.ishaan.ai.domain.buildTimeline
import com.ishaan.ai.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(nav: NavHostController) = WithProfile(nav) { p -> History(nav, p) }

@Composable
private fun History(nav: NavHostController, p: ChildProfile) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Timeline", "Events", "Surgeries", "Admissions", "Diagnoses")
    var add by remember { mutableStateOf(false) }
    ScreenScaffold("Medical history", fab = {
        if (tab > 0) FloatingActionButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Add") }
    }) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            when (tab) {
                0 -> TimelineTab(nav, p)
                1 -> EventsTab(p, add) { add = false }
                2 -> SurgeriesTab(p, add) { add = false }
                3 -> AdmissionsTab(p, add) { add = false }
                4 -> DiagnosesTab(p, add) { add = false }
            }
        }
    }
}

@Composable
private fun TimelineTab(nav: NavHostController, p: ChildProfile) {
    val c = LocalContainer.current
    val events = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeEvents(p.id) }
    val surg = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeSurgeries(p.id) }
    val adm = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeAdmissions(p.id) }
    val rep = rememberFlow(p.id, emptyList()) { c.db.reportDao().observeReports(p.id) }
    val meds = rememberFlow(p.id, emptyList()) { c.db.medicationDao().observeMedications(p.id) }
    val dx = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeDiagnoses(p.id) }
    var items by remember { mutableStateOf<List<TimelineItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(events, surg, adm, rep, meds, dx) { items = withContext(Dispatchers.Default) { buildTimeline(c.snapshot()) } }
    val shown = items.filter { query.isBlank() || it.text.contains(query, true) || it.label.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Field("Search the timeline", query, { query = it }) }
        if (shown.isEmpty()) item { EmptyState("Nothing recorded yet. Add birth details, surgeries, admissions and events — the timeline builds itself from them.") }
        items(shown.reversed()) { t ->
            Row(Modifier.fillMaxWidth().clickable(enabled = t.kind == "REPORT") { nav.navigate("report/${t.refId}") }.padding(vertical = 6.dp)) {
                Column(Modifier.width(92.dp)) { Text(Fmt.date(t.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                Box(Modifier.padding(horizontal = 8.dp).size(10.dp).padding(top = 4.dp)) { Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {} }
                Column(Modifier.weight(1f)) {
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EventsTab(p: ChildProfile, add: Boolean, onAddDone: () -> Unit) {
    val c = LocalContainer.current
    val events = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeEvents(p.id) }
    var filter by remember { mutableStateOf<EventCategory?>(null) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MedicalEvent?>(null) }
    if (add) { LaunchedEffect(Unit) { editing = MedicalEvent(childId = p.id, category = EventCategory.OTHER.name, title = "", dateTime = System.currentTimeMillis()); onAddDone() } }
    val shown = events.filter { (filter == null || it.category == filter!!.name) && (query.isBlank() || "${it.title} ${it.description} ${it.hospitalName} ${it.doctorName} ${it.notes}".contains(query, true)) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Field("Search", query, { query = it }) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(filter == null, { filter = null }, { Text("All") }) }
                items(EventCategory.entries.filter { cat -> events.any { it.category == cat.name } }) { cat -> FilterChip(filter == cat, { filter = cat }, { Text(cat.label) }) }
            }
        }
        if (shown.isEmpty()) item { EmptyState("No events. Tap + to add birth details, infections, catheter changes, follow-ups and doctor observations.") }
        items(shown, key = { it.id }) { e ->
            AppCard(onClick = { editing = e }) {
                Text(EventCategory.of(e.category).label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(Fmt.dateTime(e.dateTime) + listOf(e.hospitalName, e.doctorName).filter { it.isNotBlank() }.joinToString(" · ", prefix = " · ").takeIf { it != " · " }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
    }
    editing?.let { e -> EventEditor(e, onDismiss = { editing = null }) }
}

@Composable
fun EventEditor(initial: MedicalEvent, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var e by remember { mutableStateOf(initial) }
    var savedId by remember { mutableLongStateOf(initial.id) }
    val attachments = rememberFlow(savedId, emptyList()) { c.db.historyDao().observeAttachments("EVENT", savedId) }
    var busy by remember { mutableStateOf(false) }
    val pickers = rememberMediaPickers(arrayOf("application/pdf", "image/*")) { uris ->
        scope.launch {
            busy = true
            if (savedId == 0L) savedId = c.db.historyDao().upsertEvent(e).also { e = e.copy(id = it) }
            uris.forEach { (u, mime) ->
                runCatching {
                    val s = withContext(Dispatchers.IO) { c.vault.importUri(u, mime ?: "image/jpeg") }
                    c.db.historyDao().insertAttachment(Attachment(ownerType = "EVENT", ownerId = savedId, fileName = s.fileName, originalName = s.originalName, mimeType = mime ?: s.mimeType, sizeBytes = s.sizeBytes))
                }
            }
            busy = false
        }
    }
    EditorDialog(
        if (initial.id == 0L) "New medical event" else "Edit medical event", onDismiss,
        onSave = { scope.launch { c.db.historyDao().upsertEvent(e.copy(id = savedId, updatedAt = System.currentTimeMillis())); onDismiss() } },
        saveEnabled = e.title.isNotBlank(),
        onDelete = if (savedId != 0L) ({ scope.launch { attachments.forEach { c.vault.delete(it.fileName) }; c.db.historyDao().deleteEvent(e.copy(id = savedId)); onDismiss() } }) else null,
    ) {
        Dropdown("Category", EventCategory.entries, EventCategory.of(e.category), { it.label }, { e = e.copy(category = it.name) })
        Field("Title", e.title, { e = e.copy(title = it) })
        DateTimeField("Date and time", e.dateTime, { e = e.copy(dateTime = it) })
        Field("Description", e.description, { e = e.copy(description = it) }, singleLine = false, minLines = 3)
        Field("Hospital", e.hospitalName, { e = e.copy(hospitalName = it) })
        Field("Doctor", e.doctorName, { e = e.copy(doctorName = it) })
        Field("Additional notes", e.notes, { e = e.copy(notes = it) }, singleLine = false, minLines = 2)
        CheckRow("I have checked these details against the documents", e.parentVerified) { e = e.copy(parentVerified = it) }
        Spacer(Modifier.height(8.dp))
        Text("Documents and photographs", style = MaterialTheme.typography.titleSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MediaStrip(attachments.map { MediaItem(it.fileName, it.mimeType, it.originalName) }) { m -> scope.launch { attachments.firstOrNull { it.fileName == m.fileName }?.let { c.db.historyDao().deleteAttachment(it); c.vault.delete(it.fileName) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pickers.pick) { Icon(Icons.Default.AttachFile, null); Text("Attach") }
            OutlinedButton(onClick = pickers.photo) { Icon(Icons.Default.PhotoCamera, null); Text("Photo") }
        }
    }
}

@Composable
private fun SurgeriesTab(p: ChildProfile, add: Boolean, onAddDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeSurgeries(p.id) }
    var editing by remember { mutableStateOf<Surgery?>(null) }
    if (add) LaunchedEffect(Unit) { editing = Surgery(childId = p.id, procedureName = "", dateTime = System.currentTimeMillis()); onAddDone() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoBanner("Record each procedure exactly as written in the operation note or discharge summary (e.g. cystoscopy, valve fulguration/ablation, catheter placement).") }
        if (list.isEmpty()) item { EmptyState("No surgeries recorded.") }
        itemsIndexed(list) { i, s ->
            AppCard(onClick = { editing = s }) {
                Text("Procedure ${i + 1} · ${Fmt.date(s.dateTime)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(s.procedureName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(listOf(s.hospital, s.surgeon).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (s.outcome.isNotBlank()) Text("Outcome: ${s.outcome}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { init ->
        var s by remember(init) { mutableStateOf(init) }
        EditorDialog("Surgery / procedure", { editing = null }, onSave = { scope.launch { c.db.historyDao().upsertSurgery(s); editing = null } }, saveEnabled = s.procedureName.isNotBlank(),
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.historyDao().deleteSurgery(s); editing = null } }) else null) {
            Field("Procedure (as written by the surgeon)", s.procedureName, { s = s.copy(procedureName = it) })
            DateTimeField("Date", s.dateTime, { s = s.copy(dateTime = it) })
            Field("Hospital", s.hospital, { s = s.copy(hospital = it) })
            Field("Surgeon", s.surgeon, { s = s.copy(surgeon = it) })
            Field("Indication / reason", s.indication, { s = s.copy(indication = it) }, singleLine = false, minLines = 2)
            Field("Findings", s.findings, { s = s.copy(findings = it) }, singleLine = false, minLines = 2)
            Field("Outcome", s.outcome, { s = s.copy(outcome = it) }, singleLine = false, minLines = 2)
            Field("Notes", s.notes, { s = s.copy(notes = it) }, singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun AdmissionsTab(p: ChildProfile, add: Boolean, onAddDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeAdmissions(p.id) }
    var editing by remember { mutableStateOf<HospitalAdmission?>(null) }
    if (add) LaunchedEffect(Unit) { editing = HospitalAdmission(childId = p.id, admitDate = System.currentTimeMillis()); onAddDone() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (list.isEmpty()) item { EmptyState("No admissions recorded. Include NICU/ICU stays.") }
        items(list.reversed()) { a ->
            AppCard(onClick = { editing = a }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Fmt.date(a.admitDate)} – ${a.dischargeDate?.let { Fmt.date(it) } ?: "?"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (a.intensiveCare != "NONE") Pill(a.intensiveCare, MaterialTheme.colorScheme.error)
                }
                Text(a.reason.ifBlank { "Admission" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(a.hospital, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { init ->
        var a by remember(init) { mutableStateOf(init) }
        EditorDialog("Hospital admission", { editing = null }, onSave = { scope.launch { c.db.historyDao().upsertAdmission(a); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.historyDao().deleteAdmission(a); editing = null } }) else null) {
            DateTimeField("Admitted", a.admitDate, { a = a.copy(admitDate = it) })
            DateTimeField("Discharged", a.dischargeDate, { a = a.copy(dischargeDate = it) })
            Field("Hospital", a.hospital, { a = a.copy(hospital = it) })
            Field("Reason", a.reason, { a = a.copy(reason = it) })
            Dropdown("Intensive care", listOf("NONE", "NICU", "PICU"), a.intensiveCare, { if (it == "NONE") "None" else it }, { a = a.copy(intensiveCare = it) })
            Field("Treatment given (e.g. antibiotics, catheter)", a.treatment, { a = a.copy(treatment = it) }, singleLine = false, minLines = 2)
            Field("Notes", a.notes, { a = a.copy(notes = it) }, singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun DiagnosesTab(p: ChildProfile, add: Boolean, onAddDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.historyDao().observeDiagnoses(p.id) }
    var editing by remember { mutableStateOf<Diagnosis?>(null) }
    if (add) LaunchedEffect(Unit) { editing = Diagnosis(childId = p.id, name = ""); onAddDone() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { InfoBanner("Only enter diagnoses that a doctor has made. The app never adds or infers diagnoses.") }
        if (list.isEmpty()) item { EmptyState("No diagnoses recorded.") }
        items(list) { d ->
            AppCard(onClick = { editing = d }) {
                Row { Text(d.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Pill(d.status.lowercase(), MaterialTheme.colorScheme.primary) }
                Text("${Fmt.date(d.dateDiagnosed)}" + (if (d.diagnosedBy.isNotBlank()) " · ${d.diagnosedBy}" else ""), style = MaterialTheme.typography.bodySmall)
                if (d.details.isNotBlank()) Text(d.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { init ->
        var d by remember(init) { mutableStateOf(init) }
        EditorDialog("Diagnosis", { editing = null }, onSave = { scope.launch { c.db.historyDao().upsertDiagnosis(d); editing = null } }, saveEnabled = d.name.isNotBlank(),
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.historyDao().deleteDiagnosis(d); editing = null } }) else null) {
            Field("Diagnosis (as written by the doctor)", d.name, { d = d.copy(name = it) })
            DateTimeField("Date diagnosed", d.dateDiagnosed, { d = d.copy(dateDiagnosed = it) }, withTime = false)
            Field("Diagnosed by", d.diagnosedBy, { d = d.copy(diagnosedBy = it) })
            Field("Hospital", d.hospital, { d = d.copy(hospital = it) })
            Dropdown("Status", listOf("ACTIVE", "MONITORING", "RESOLVED"), d.status, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }, { d = d.copy(status = it) })
            Field("Details (e.g. initial kidney function, antenatal findings)", d.details, { d = d.copy(details = it) }, singleLine = false, minLines = 3)
        }
    }
}
