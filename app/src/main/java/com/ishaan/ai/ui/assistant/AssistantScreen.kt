package com.ishaan.ai.ui.assistant

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ishaan.ai.data.AppSettings
import com.ishaan.ai.domain.*
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.reports.TableView
import com.ishaan.ai.ui.theme.ReviewAmber
import com.ishaan.ai.ui.theme.UrgentRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Msg(val fromUser: Boolean, val text: String, val mode: String = "RULES", val sources: List<String> = emptyList(), val records: String? = null, val report: ReportKind? = null)

@Composable
fun AssistantScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val model = c.llm.installedModel(settings.llmModelFile)
    var useModel by remember { mutableStateOf(true) }
    val msgs = remember { mutableStateListOf(Msg(false, "Hello! I'm the Ishaan Health Assistant. I answer only from the records stored on this phone, with dates and sources. I don't diagnose or give medicine advice.")) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val suggestions = listOf("What is Ishaan's latest creatinine?", "Show creatinine trend", "Compare his first and latest ultrasound", "What changed between the DMSA reports?", "Show his surgery history",
        "Which medicines were prescribed last month?", "Summarise his last three urine cultures", "Show his weight progression", "What questions should I ask the nephrologist?", "Prepare a summary for tomorrow's doctor visit", "What information is missing?")

    fun ask(q: String) {
        if (q.isBlank() || busy) return
        msgs += Msg(true, q); input = ""; busy = true
        scope.launch {
            val snap = withContext(Dispatchers.Default) { c.snapshot() }
            val rule = withContext(Dispatchers.Default) { c.assistant.answer(q, snap) }
            if (model != null && useModel && rule.reportKind == null) {
                val retrieved = buildString {
                    appendLine("Profile: ${snap.childName}, born ${Fmt.date(snap.dob)}, diagnosis: ${snap.profile?.primaryDiagnosis ?: ""}")
                    appendLine(rule.text)
                    if (rule.text.startsWith("I couldn't match")) appendLine(c.engine.generate(ReportKind.COMPLETE, snap).asPlainText())
                }
                val out = runCatching { c.llm.generate(model, LocalLlm.buildPrompt(q, snap.childName, retrieved)) }
                out.onSuccess { msgs += Msg(false, it.trim(), "LOCAL_MODEL", rule.sources, records = rule.text) }
                    .onFailure { msgs += Msg(false, rule.text + "\n\n(The local model could not run: ${it.message ?: it.javaClass.simpleName}. Showing the rule-based answer.)", "RULES", rule.sources, report = rule.reportKind) }
            } else msgs += Msg(false, rule.text, "RULES", rule.sources, report = rule.reportKind)
            busy = false
            list.animateScrollToItem(msgs.size)
        }
    }

    ScreenScaffold("Ishaan Health Assistant") { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model != null) {
                    Text(if (useModel) "Mode: on-device model + records" else "Mode: rule-based", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Switch(useModel, { useModel = it })
                } else Text("Mode: transparent rule-based engine (no AI model installed)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ReportKind.entries) { k -> AssistChip(onClick = { nav.navigate("healthreport/${k.name}") }, label = { Text(k.title) }, leadingIcon = { Icon(Icons.Default.Summarize, null, Modifier.size(16.dp)) }) }
            }
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs) { m -> Bubble(m, onOpen = { k -> nav.navigate("healthreport/${k.name}") }) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(suggestions) { s -> SuggestionChip(onClick = { ask(s) }, label = { Text(s) }) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("Ask about Ishaan's records…") }, modifier = Modifier.weight(1f), maxLines = 3)
                IconButton(onClick = { ask(input) }, enabled = !busy && input.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, onOpen: (ReportKind) -> Unit) {
    var showRecords by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (!m.fromUser) Pill(if (m.mode == "LOCAL_MODEL") "On-device AI model — check against records" else "Rule-based, from stored records", if (m.mode == "LOCAL_MODEL") ReviewAmber else MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(m.text, color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
                if (m.sources.isNotEmpty()) Text("Sources: " + m.sources.distinct().take(6).joinToString("; "), style = MaterialTheme.typography.labelSmall)
                m.records?.let { r ->
                    TextButton(onClick = { showRecords = !showRecords }) { Text(if (showRecords) "Hide records used" else "Show records used") }
                    if (showRecords) Text(r, style = MaterialTheme.typography.bodySmall)
                }
                m.report?.let { k -> Button(onClick = { onOpen(k) }) { Text("Open report") } }
            }
        }
    }
}

/** Full health report viewer with PDF export (share or save to a chosen location). */
@Composable
fun HealthReportScreen(nav: NavHostController, kindName: String) = WithProfile(nav) { _ ->
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = ReportKind.entries.firstOrNull { it.name == kindName } ?: ReportKind.COMPLETE
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var snap by remember { mutableStateOf<HealthSnapshot?>(null) }
    var extraQ by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, extraQ) {
        val s = withContext(Dispatchers.Default) { c.snapshot() }
        snap = s
        report = withContext(Dispatchers.Default) { c.engine.generate(kind, s, extraQ.lines()) }
    }
    suspend fun buildPdf(): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "share/Ishaan_${kind.name.lowercase()}_${java.time.LocalDate.now()}.pdf")
        c.pdf.build(report!!, snap!!, f); f
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val f = buildPdf()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }; f.delete() }
                c.db.planDao().insertBackupMeta(com.ishaan.ai.data.db.BackupMetadata(type = "EXPORT_PDF", location = uri.lastPathSegment ?: "", notes = kind.title))
            }.onSuccess { message = "PDF saved." }.onFailure { message = "Could not save: ${it.message}" }
        }
    }
    ScreenScaffold(kind.title, onBack = { nav.back() }, actions = {
        IconButton(onClick = { scope.launch { runCatching { shareFile(ctx, buildPdf(), "application/pdf", "Share report") }.onFailure { message = it.message } } }, enabled = report != null) { Icon(Icons.Default.Share, "Share PDF") }
        IconButton(onClick = { saver.launch("Ishaan_${kind.name.lowercase()}_${java.time.LocalDate.now()}.pdf") }, enabled = report != null) { Icon(Icons.Default.Download, "Save PDF") }
    }) { pad ->
        val r = report
        if (r == null) { Box(Modifier.padding(pad)) { LinearProgressIndicator(Modifier.fillMaxWidth()) }; return@ScreenScaffold }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Generated ${Fmt.dateTime(r.generatedAt)}" + (r.periodStart?.let { " · from ${Fmt.date(it)}" } ?: ""), style = MaterialTheme.typography.labelMedium)
                InfoBanner("Rule-based analysis of stored records. Each statement shows its date and source. Sharing the PDF is your choice — the app never sends it anywhere by itself.")
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            items(r.sections) { s ->
                AppCard(title = s.title) {
                    s.findings.forEach { f ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•", modifier = Modifier.width(14.dp))
                            Column {
                                Text(f.text, style = MaterialTheme.typography.bodySmall, color = when (f.flag) { Flag.URGENT -> UrgentRed; Flag.REVIEW -> ReviewAmber; else -> MaterialTheme.colorScheme.onSurface }, fontWeight = if (f.flag == Flag.URGENT) FontWeight.Bold else FontWeight.Normal)
                                val meta = listOfNotNull(f.date?.let { Fmt.date(it) }, f.basis.ifBlank { null }).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    s.table?.let { TableView(it) }
                    s.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (r.missing.isNotEmpty()) item { AppCard(title = "Missing or incomplete information", icon = Icons.Default.HelpOutline) { r.missing.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } } }
            if (kind == ReportKind.DOCTOR || r.questions.isNotEmpty()) item {
                AppCard(title = "Questions for the doctor", icon = Icons.Default.QuestionAnswer) {
                    r.questions.forEachIndexed { i, q -> Text("${i + 1}. $q", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                    var draft by remember { mutableStateOf(extraQ) }
                    Field("Your own questions (one per line)", draft, { draft = it }, singleLine = false, minLines = 3)
                    TextButton(onClick = { extraQ = draft }) { Text("Add to report") }
                }
            }
            item { Text(AnalysisEngine.DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp)) }
        }
    }
}
