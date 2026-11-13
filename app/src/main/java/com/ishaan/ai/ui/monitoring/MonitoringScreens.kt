package com.ishaan.ai.ui.monitoring

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.AlertEngine
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.WhoGrowth
import com.ishaan.ai.reminders.ReminderScheduler
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.UrgentRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun MonitoringHub(nav: NavHostController) {
    data class Item(val label: String, val sub: String, val icon: ImageVector, val route: String)
    val items = listOf(
        Item("Urine", "Videos, colour, flow", Icons.Default.WaterDrop, "urine"),
        Item("Potty / stool", "Photos, Bristol chart", Icons.Default.BabyChangingStation, "stool"),
        Item("Kidney", "Labs, USG, DMSA", Icons.Default.Favorite, "kidney"),
        Item("Medicines", "Doses, reminders", Icons.Default.Medication, "meds"),
        Item("Growth", "Weight, length, WHO charts", Icons.Default.Straighten, "growth"),
        Item("Symptoms", "Fever, vomiting…", Icons.Default.Thermostat, "symptoms"),
        Item("Feeding", "Milk, formula, solids", Icons.Default.LocalDrink, "feeding"),
        Item("Sleep", "Sleep periods", Icons.Default.Bedtime, "sleep"),
        Item("Milestones", "Development", Icons.Default.EmojiEvents, "milestones"),
        Item("Appointments", "Visits & tests", Icons.Default.Event, "appointments"),
    )
    ScreenScaffold("Monitoring") { pad ->
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(pad), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { i ->
                AppCard(onClick = { nav.navigate(i.route) }) {
                    Icon(i.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(i.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(i.sub, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val FLOW_OPTIONS = listOf("NOT_OBSERVED" to "Not observed", "GOOD_STREAM" to "Good continuous stream (as seen)", "WEAK" to "Weak stream", "INTERRUPTED" to "Interrupted stream", "DRIBBLING" to "Dribbling")

// ------------------------------------------------------------------ urine

@Composable
fun UrineScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val logs = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeUrine(p.id) }
    var editing by remember { mutableStateOf<UrineLog?>(null) }
    ScreenScaffold("Urine monitoring", onBack = { nav.back() }, fab = {
        ExtendedFloatingActionButton(onClick = { editing = UrineLog(childId = p.id, dateTime = System.currentTimeMillis()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Log urine") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoBanner("Video and photo observations need clinical interpretation. They cannot show whether there is an infection, obstruction, valve recurrence or how the kidneys are working. The app does not classify videos as normal or abnormal.") }
            item {
                val today = Fmt.startOfDay(LocalDate.now())
                val days = (0..6).map { d -> val s = today - d * 86_400_000L; logs.count { it.dateTime in s until s + 86_400_000L } }
                AppCard(title = "Last 7 days (entries per day)", icon = Icons.Default.BarChart) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        days.reversed().forEachIndexed { i, n -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$n", fontWeight = FontWeight.Bold); Text(LocalDate.now().minusDays((6 - i).toLong()).dayOfWeek.name.take(2), style = MaterialTheme.typography.labelSmall) } }
                    }
                    Text("Counts show logged entries, not necessarily every time he passed urine.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (logs.isEmpty()) item { EmptyState("No urine observations yet.") }
            items(logs, key = { it.id }) { u ->
                val media = rememberFlow(u.id, emptyList()) { c.db.monitoringDao().observeUrineMedia(u.id) }
                AppCard(onClick = { editing = u }) {
                    Text(Fmt.dateTime(u.dateTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(listOfNotNull(u.colour.ifBlank { null }, FLOW_OPTIONS.first { it.first == u.flow }.second.takeIf { u.flow != "NOT_OBSERVED" },
                        u.volumeMl?.let { "${Fmt.num(it)} ml" }, u.volumeEstimate.ifBlank { null }, u.durationSec?.let { "$it s" }).joinToString(" · ").ifBlank { "Observation" }, style = MaterialTheme.typography.bodyMedium)
                    val flags = listOfNotNull("straining".takeIf { u.straining }, "crying/pain".takeIf { u.cryingOrPain }, "cloudy".takeIf { u.cloudy }, "blood-like colour".takeIf { u.bloodLike }, "foul smell".takeIf { u.foulSmell })
                    if (flags.isNotEmpty()) Text("Noted: ${flags.joinToString()}", style = MaterialTheme.typography.bodySmall, color = if (u.bloodLike) UrgentRed else MaterialTheme.colorScheme.onSurface)
                    if (u.notes.isNotBlank()) Text(u.notes, style = MaterialTheme.typography.bodySmall)
                    if (media.isNotEmpty()) { Spacer(Modifier.height(6.dp)); MediaStrip(media.map { MediaItem(it.fileName, it.mimeType, Fmt.dateTime(u.dateTime)) }) }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    editing?.let { UrineEditor(it) { editing = null } }
}

@Composable
private fun UrineEditor(initial: UrineLog, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf(initial) }
    var id by remember { mutableLongStateOf(initial.id) }
    var vol by remember { mutableStateOf(initial.volumeMl?.let { Fmt.num(it) } ?: "") }
    var dur by remember { mutableStateOf(initial.durationSec?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }
    val media = rememberFlow(id, emptyList()) { c.db.monitoringDao().observeUrineMedia(id) }
    fun current() = u.copy(id = id, volumeMl = vol.replace(',', '.').toDoubleOrNull(), durationSec = dur.toIntOrNull())
    val pickers = rememberMediaPickers(arrayOf("video/*", "image/*")) { uris ->
        scope.launch {
            busy = true
            if (id == 0L) id = c.db.monitoringDao().upsertUrine(current())
            uris.forEach { (uri, mime) ->
                runCatching {
                    val s = withContext(Dispatchers.IO) { c.vault.importUri(uri, mime ?: "video/mp4") }
                    val m = mime ?: s.mimeType
                    c.db.monitoringDao().insertUrineMedia(UrineMedia(urineLogId = id, fileName = s.fileName, mimeType = m, kind = if (m.startsWith("video")) "VIDEO" else "PHOTO", sizeBytes = s.sizeBytes))
                }
            }
            busy = false
        }
    }
    EditorDialog("Urine observation", onDismiss,
        onSave = { scope.launch { c.db.monitoringDao().upsertUrine(current()); if (u.bloodLike) c.refreshAlerts(); onDismiss() } },
        onDelete = if (id != 0L) ({ scope.launch { media.forEach { c.vault.delete(it.fileName) }; c.db.monitoringDao().deleteUrine(current()); onDismiss() } }) else null,
    ) {
        DateTimeField("Date and time", u.dateTime, { u = u.copy(dateTime = it) })
        Text("Video / photo", style = MaterialTheme.typography.titleSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MediaStrip(media.map { MediaItem(it.fileName, it.mimeType) }) { m -> scope.launch { media.firstOrNull { it.fileName == m.fileName }?.let { c.db.monitoringDao().deleteUrineMedia(it); c.vault.delete(it.fileName) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = pickers.video) { Icon(Icons.Default.Videocam, null); Text("Record") }
            OutlinedButton(onClick = pickers.photo) { Icon(Icons.Default.PhotoCamera, null); Text("Photo") }
            OutlinedButton(onClick = pickers.pick) { Icon(Icons.Default.Upload, null); Text("Upload") }
        }
        Field("Colour (your description)", u.colour, { u = u.copy(colour = it) })
        Dropdown("Stream / flow (as you saw it)", FLOW_OPTIONS, FLOW_OPTIONS.first { it.first == u.flow }, { it.second }, { u = u.copy(flow = it.first) })
        Field("Measured volume (ml) — only if measured", vol, { vol = it }, number = true)
        Field("Approximate amount (e.g. small, heavy nappy)", u.volumeEstimate, { u = u.copy(volumeEstimate = it) })
        Field("Duration (seconds)", dur, { dur = it.filter(Char::isDigit) }, number = true)
        CheckRow("Straining", u.straining) { u = u.copy(straining = it) }
        CheckRow("Crying / seemed painful", u.cryingOrPain) { u = u.copy(cryingOrPain = it) }
        CheckRow("Looked cloudy", u.cloudy) { u = u.copy(cloudy = it) }
        CheckRow("Blood-like / red-brown colour", u.bloodLike) { u = u.copy(bloodLike = it) }
        CheckRow("Foul smell", u.foulSmell) { u = u.copy(foulSmell = it) }
        Field("Notes (weak flow, interruptions, other observations)", u.notes, { u = u.copy(notes = it) }, singleLine = false, minLines = 2)
        if (u.bloodLike) InfoBanner("Please contact Ishaan's doctor and show them this record.", UrgentRed)
    }
}

// ------------------------------------------------------------------ stool

private val BRISTOL = listOf(
    1 to "Type 1 – separate hard lumps", 2 to "Type 2 – lumpy, sausage-shaped", 3 to "Type 3 – sausage with cracks",
    4 to "Type 4 – smooth, soft sausage", 5 to "Type 5 – soft blobs, clear edges", 6 to "Type 6 – mushy, ragged edges", 7 to "Type 7 – watery, no solid pieces",
)

@Composable
fun StoolScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val logs = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeStool(p.id) }
    var editing by remember { mutableStateOf<StoolLog?>(null) }
    ScreenScaffold("Potty monitoring", onBack = { nav.back() }, fab = {
        ExtendedFloatingActionButton(onClick = { editing = StoolLog(childId = p.id, dateTime = System.currentTimeMillis()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Log potty") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoBanner("Photos are stored encrypted for you and the doctor to look at. The app does not analyse images or diagnose bowel problems. The Bristol chart is an optional reference (designed for older children/adults; baby stools are often softer).") }
            if (logs.isEmpty()) item { EmptyState("No stool observations yet.") }
            items(logs, key = { it.id }) { s ->
                val media = rememberFlow(s.id, emptyList()) { c.db.monitoringDao().observeStoolMedia(s.id) }
                AppCard(onClick = { editing = s }) {
                    Text(Fmt.dateTime(s.dateTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(listOfNotNull(s.bristolType?.let { "Bristol type $it" }, s.consistency.ifBlank { null }, s.colour.ifBlank { null }).joinToString(" · ").ifBlank { "Observation" })
                    val flags = listOfNotNull("hard".takeIf { s.hard }, "loose".takeIf { s.loose }, "constipation".takeIf { s.constipation }, "diarrhoea".takeIf { s.diarrhoea }, "blood-like".takeIf { s.bloodLike }, "mucus".takeIf { s.mucus })
                    if (flags.isNotEmpty()) Text("Noted: ${flags.joinToString()}", style = MaterialTheme.typography.bodySmall, color = if (s.bloodLike) UrgentRed else MaterialTheme.colorScheme.onSurface)
                    if (media.isNotEmpty()) MediaStrip(media.map { MediaItem(it.fileName, it.mimeType) })
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    editing?.let { StoolEditor(it) { editing = null } }
}

@Composable
private fun StoolEditor(initial: StoolLog, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(initial) }
    var id by remember { mutableLongStateOf(initial.id) }
    val media = rememberFlow(id, emptyList()) { c.db.monitoringDao().observeStoolMedia(id) }
    val pickers = rememberMediaPickers(arrayOf("image/*", "video/*")) { uris ->
        scope.launch {
            if (id == 0L) id = c.db.monitoringDao().upsertStool(s)
            uris.forEach { (uri, mime) -> runCatching {
                val st = withContext(Dispatchers.IO) { c.vault.importUri(uri, mime ?: "image/jpeg") }
                val m = mime ?: st.mimeType
                c.db.monitoringDao().insertStoolMedia(StoolMedia(stoolLogId = id, fileName = st.fileName, mimeType = m, kind = if (m.startsWith("video")) "VIDEO" else "PHOTO", sizeBytes = st.sizeBytes))
            } }
        }
    }
    EditorDialog("Potty observation", onDismiss, onSave = { scope.launch { c.db.monitoringDao().upsertStool(s.copy(id = id)); onDismiss() } },
        onDelete = if (id != 0L) ({ scope.launch { media.forEach { c.vault.delete(it.fileName) }; c.db.monitoringDao().deleteStool(s.copy(id = id)); onDismiss() } }) else null) {
        DateTimeField("Date and time", s.dateTime, { s = s.copy(dateTime = it) })
        MediaStrip(media.map { MediaItem(it.fileName, it.mimeType) }) { m -> scope.launch { media.firstOrNull { it.fileName == m.fileName }?.let { c.db.monitoringDao().deleteStoolMedia(it); c.vault.delete(it.fileName) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = pickers.photo) { Icon(Icons.Default.PhotoCamera, null); Text("Photo") }
            OutlinedButton(onClick = pickers.video) { Icon(Icons.Default.Videocam, null); Text("Video") }
            OutlinedButton(onClick = pickers.pick) { Icon(Icons.Default.Upload, null); Text("Upload") }
        }
        Dropdown("Bristol chart (optional)", listOf<Pair<Int?, String>>(null to "Not chosen") + BRISTOL.map { it.first to it.second }, (s.bristolType to (BRISTOL.firstOrNull { it.first == s.bristolType }?.second ?: "Not chosen")), { it.second }, { s = s.copy(bristolType = it.first) })
        Field("Consistency (your words)", s.consistency, { s = s.copy(consistency = it) })
        Field("Colour", s.colour, { s = s.copy(colour = it) })
        CheckRow("Hard stool", s.hard) { s = s.copy(hard = it) }
        CheckRow("Loose stool", s.loose) { s = s.copy(loose = it) }
        CheckRow("Constipation / straining", s.constipation) { s = s.copy(constipation = it) }
        CheckRow("Diarrhoea", s.diarrhoea) { s = s.copy(diarrhoea = it) }
        CheckRow("Blood-like appearance", s.bloodLike) { s = s.copy(bloodLike = it) }
        CheckRow("Mucus", s.mucus) { s = s.copy(mucus = it) }
        Field("Feeding-related notes", s.feedingNotes, { s = s.copy(feedingNotes = it) })
        Field("Notes", s.notes, { s = s.copy(notes = it) }, singleLine = false, minLines = 2)
        if (s.bloodLike) InfoBanner("Blood in stool should be discussed with Ishaan's doctor.", UrgentRed)
    }
}

// ------------------------------------------------------------------ growth

@Composable
fun GrowthScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeGrowth(p.id) }
    var editing by remember { mutableStateOf<GrowthRecord?>(null) }
    var indicator by remember { mutableStateOf("wfa") }
    ScreenScaffold("Growth", onBack = { nav.back() }, fab = {
        ExtendedFloatingActionButton(onClick = { editing = GrowthRecord(childId = p.id, date = System.currentTimeMillis()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add measurement") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AppCard(title = "WHO growth chart", icon = Icons.Default.ShowChart) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("wfa" to "Weight", "lhfa" to "Length/height", "hcfa" to "Head").forEach { (k, l) -> FilterChip(indicator == k, { indicator = k }, { Text(l) }) }
                    }
                    val dob = p.dateOfBirthEpochDay?.let { LocalDate.ofEpochDay(it) }
                    val who = c.who
                    val sel: (GrowthRecord) -> Double? = when (indicator) { "lhfa" -> { r -> r.lengthCm }; "hcfa" -> { r -> r.headCm }; else -> { r -> r.weightKg } }
                    val pts = list.filter { sel(it) != null }.sortedBy { it.date }
                    if (dob == null || who == null) EmptyState("Add date of birth in the profile to see WHO charts.")
                    else if (pts.isEmpty()) EmptyState("No measurements of this type yet.")
                    else {
                        val start = pts.first().date; val end = maxOf(pts.last().date, start + 30L * 86_400_000L)
                        val curves = WhoGrowth.PERCENTILE_LINES.map { (_, z) ->
                            (0..20).mapNotNull { k ->
                                val t = start + (end - start) * k / 20
                                who.valueAtZ(indicator, p.sex, WhoGrowth.ageMonths(dob, Fmt.toLocalDate(t)), z)?.let { ChartPoint(t, it) }
                            }
                        }
                        TrendChart(pts.map { ChartPoint(it.date, sel(it)!!) }, extraLines = curves)
                        Text("Grey lines: WHO 3rd, 15th, 50th, 85th and 97th percentiles for ${if (p.sex == "F") "girls" else "boys"} (chronological age, 0–5 years). Blue: Ishaan's measured values.", style = MaterialTheme.typography.labelSmall)
                        val last = pts.last()
                        who.zScore(indicator, p.sex, WhoGrowth.ageMonths(dob, Fmt.toLocalDate(last.date)), sel(last)!!)?.let { z ->
                            Text(String.format("Latest: z = %.2f (about the %.0fth percentile). Only Ishaan's doctor can say what this means for him.", z, WhoGrowth.percentile(z)), style = MaterialTheme.typography.bodySmall)
                        }
                        if ((p.gestationWeeks ?: 40) < 37) Text("Born before 37 weeks — doctors often use corrected age until 2 years; this chart uses chronological age.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState("No measurements yet.") }
            items(list, key = { it.id }) { g ->
                AppCard(onClick = { editing = g }) {
                    Text("${Fmt.date(g.date)} · ${if (g.measuredAt == "CLINIC") "clinic" else "home"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(listOfNotNull(g.weightKg?.let { "${Fmt.num(it)} kg" }, g.lengthCm?.let { "${Fmt.num(it)} cm" }, g.headCm?.let { "head ${Fmt.num(it)} cm" }).joinToString(" · "))
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    editing?.let { init ->
        var g by remember(init) { mutableStateOf(init) }
        var w by remember(init) { mutableStateOf(init.weightKg?.let { Fmt.num(it) } ?: "") }
        var l by remember(init) { mutableStateOf(init.lengthCm?.let { Fmt.num(it) } ?: "") }
        var h by remember(init) { mutableStateOf(init.headCm?.let { Fmt.num(it) } ?: "") }
        fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
        EditorDialog("Growth measurement", { editing = null }, saveEnabled = d(w) != null || d(l) != null || d(h) != null,
            onSave = { scope.launch { c.db.monitoringDao().upsertGrowth(g.copy(weightKg = d(w), lengthCm = d(l), headCm = d(h))); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.monitoringDao().deleteGrowth(init); editing = null } }) else null) {
            DateTimeField("Date", g.date, { g = g.copy(date = it) }, withTime = false)
            Field("Weight (kg)", w, { w = it }, number = true)
            Field("Length / height (cm)", l, { l = it }, number = true)
            Field("Head circumference (cm)", h, { h = it }, number = true)
            Dropdown("Measured at", listOf("HOME", "CLINIC"), g.measuredAt, { if (it == "HOME") "Home" else "Clinic / hospital" }, { g = g.copy(measuredAt = it) })
            Field("Notes", g.notes, { g = g.copy(notes = it) })
        }
    }
}

// ------------------------------------------------------------------ symptoms

@Composable
fun SymptomScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeSymptoms(p.id) }
    var editing by remember { mutableStateOf<SymptomLog?>(null) }
    var urgentShown by remember { mutableStateOf(false) }
    ScreenScaffold("Symptoms", onBack = { nav.back() }, fab = {
        ExtendedFloatingActionButton(onClick = { editing = SymptomLog(childId = p.id, dateTime = System.currentTimeMillis(), symptom = SymptomType.FEVER.name) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Record symptom") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoBanner(AlertEngine.EMERGENCY_GUIDANCE, UrgentRed, Icons.Default.LocalHospital) }
            if (list.isEmpty()) item { EmptyState("No symptoms recorded.") }
            items(list, key = { it.id }) { s ->
                val t = SymptomType.of(s.symptom)
                AppCard(onClick = { editing = s }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label + (s.temperatureC?.let { " · ${Fmt.num(it)} °C" } ?: ""), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (t.urgent) Pill("Emergency sign", UrgentRed) else if (s.resolved) Pill("Resolved", com.ishaan.ai.ui.theme.OkGreen)
                    }
                    Text("${Fmt.dateTime(s.dateTime)} · ${s.severity.lowercase()}", style = MaterialTheme.typography.bodySmall)
                    if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    editing?.let { init ->
        var s by remember(init) { mutableStateOf(init) }
        var temp by remember(init) { mutableStateOf(init.temperatureC?.let { Fmt.num(it) } ?: "") }
        val t = SymptomType.of(s.symptom)
        EditorDialog("Symptom", { editing = null }, onSave = {
            scope.launch {
                c.db.monitoringDao().upsertSymptom(s.copy(temperatureC = temp.replace(',', '.').toDoubleOrNull()))
                c.refreshAlerts(); editing = null
                if (t.urgent) urgentShown = true
            }
        }, onDelete = if (init.id != 0L) ({ scope.launch { c.db.monitoringDao().deleteSymptom(init); editing = null } }) else null) {
            if (t.urgent) InfoBanner("This is an emergency warning sign. Seek emergency care now — don't wait for this app.", UrgentRed, Icons.Default.LocalHospital)
            Dropdown("Symptom", SymptomType.entries, t, { it.label }, { s = s.copy(symptom = it.name) })
            DateTimeField("When", s.dateTime, { s = s.copy(dateTime = it) })
            if (t == SymptomType.FEVER) Field("Temperature (°C)", temp, { temp = it }, number = true)
            Dropdown("How bad (your judgement)", listOf("MILD", "MODERATE", "SEVERE"), s.severity, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }, { s = s.copy(severity = it) })
            Field("Notes", s.notes, { s = s.copy(notes = it) }, singleLine = false, minLines = 2)
            CheckRow("Resolved", s.resolved) { s = s.copy(resolved = it) }
        }
    }
    if (urgentShown) AlertDialog(onDismissRequest = { urgentShown = false }, icon = { Icon(Icons.Default.LocalHospital, null, tint = UrgentRed) },
        title = { Text("Seek emergency care now") }, text = { Text(AlertEngine.EMERGENCY_GUIDANCE) }, confirmButton = { TextButton(onClick = { urgentShown = false }) { Text("OK") } })
}

// ------------------------------------------------------------------ feeding / sleep / milestones

@Composable
fun FeedingScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeFeeding(p.id) }
    var editing by remember { mutableStateOf<FeedingLog?>(null) }
    ScreenScaffold("Feeding", onBack = { nav.back() }, fab = { FloatingActionButton(onClick = { editing = FeedingLog(childId = p.id, dateTime = System.currentTimeMillis(), type = "FORMULA") }) { Icon(Icons.Default.Add, "Add") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val today = Fmt.startOfDay(LocalDate.now())
            item { AppCard(title = "Today") { Text("${list.count { it.dateTime >= today }} feeds · ${Fmt.num(list.filter { it.dateTime >= today }.sumOf { it.amountMl ?: 0.0 })} ml recorded") } }
            if (list.isEmpty()) item { EmptyState("No feeds recorded.") }
            items(list, key = { it.id }) { f ->
                AppCard(onClick = { editing = f }) {
                    Text(Fmt.dateTime(f.dateTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(listOfNotNull(f.type.lowercase().replaceFirstChar { it.uppercase() }, f.amountMl?.let { "${Fmt.num(it)} ml" }, f.durationMin?.let { "$it min" }, f.description.ifBlank { null }).joinToString(" · "))
                    if (f.poorFeeding || f.vomiting) Text(listOfNotNull("poor feeding".takeIf { f.poorFeeding }, "vomiting".takeIf { f.vomiting }).joinToString(), color = UrgentRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    editing?.let { init ->
        var f by remember(init) { mutableStateOf(init) }
        var amt by remember(init) { mutableStateOf(init.amountMl?.let { Fmt.num(it) } ?: "") }
        var dur by remember(init) { mutableStateOf(init.durationMin?.toString() ?: "") }
        EditorDialog("Feed", { editing = null }, onSave = { scope.launch { c.db.monitoringDao().upsertFeeding(f.copy(amountMl = amt.replace(',', '.').toDoubleOrNull(), durationMin = dur.toIntOrNull())); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.monitoringDao().deleteFeeding(init); editing = null } }) else null) {
            DateTimeField("When", f.dateTime, { f = f.copy(dateTime = it) })
            Dropdown("Type", listOf("BREAST", "FORMULA", "SOLID", "WATER", "OTHER"), f.type, { it.lowercase().replaceFirstChar { c -> c.uppercase() } }, { f = f.copy(type = it) })
            Field("Amount (ml)", amt, { amt = it }, number = true)
            Field("Duration (minutes)", dur, { dur = it.filter(Char::isDigit) }, number = true)
            Field("What (e.g. formula name, solid food)", f.description, { f = f.copy(description = it) })
            CheckRow("Poor feeding", f.poorFeeding) { f = f.copy(poorFeeding = it) }
            CheckRow("Vomiting", f.vomiting) { f = f.copy(vomiting = it) }
            Field("Notes", f.notes, { f = f.copy(notes = it) })
        }
    }
}

@Composable
fun SleepScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeSleep(p.id) }
    var editing by remember { mutableStateOf<SleepLog?>(null) }
    ScreenScaffold("Sleep", onBack = { nav.back() }, fab = { FloatingActionButton(onClick = { editing = SleepLog(childId = p.id, startTime = System.currentTimeMillis()) }) { Icon(Icons.Default.Add, "Add") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item { EmptyState("No sleep recorded.") }
            items(list, key = { it.id }) { s ->
                AppCard(onClick = { editing = s }) {
                    Text("${Fmt.dateTime(s.startTime)} → ${s.endTime?.let { Fmt.time(it) } ?: "ongoing"}")
                    s.endTime?.let { e -> Text("${(e - s.startTime) / 60000 / 60} h ${(e - s.startTime) / 60000 % 60} min", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    editing?.let { init ->
        var s by remember(init) { mutableStateOf(init) }
        EditorDialog("Sleep", { editing = null }, saveEnabled = s.endTime == null || s.endTime!! > s.startTime,
            onSave = { scope.launch { c.db.monitoringDao().upsertSleep(s); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.monitoringDao().deleteSleep(init); editing = null } }) else null) {
            DateTimeField("Fell asleep", s.startTime, { s = s.copy(startTime = it) })
            DateTimeField("Woke up", s.endTime, { s = s.copy(endTime = it) })
            Field("Notes", s.notes, { s = s.copy(notes = it) })
        }
    }
}

private val COMMON_MILESTONES = listOf("Social smile", "Holds head up", "Rolls over", "Sits without support", "Crawls", "Stands with support", "First words", "Walks independently", "Toilet training started", "Dry by day")

@Composable
fun MilestoneScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeMilestones(p.id) }
    var editing by remember { mutableStateOf<Milestone?>(null) }
    ScreenScaffold("Milestones", onBack = { nav.back() }, fab = { FloatingActionButton(onClick = { editing = Milestone(childId = p.id, name = "", achievedDate = System.currentTimeMillis()) }) { Icon(Icons.Default.Add, "Add") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list.isEmpty()) item { EmptyState("No milestones recorded. Tap + to add one.") }
            items(list, key = { it.id }) { m ->
                AppCard(onClick = { editing = m }) { Text(m.name, fontWeight = FontWeight.SemiBold); Text(Fmt.date(m.achievedDate) + " · age " + Fmt.age(p.dateOfBirthEpochDay?.let { LocalDate.ofEpochDay(it) }, m.achievedDate?.let { Fmt.toLocalDate(it) } ?: LocalDate.now()), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    editing?.let { init ->
        var m by remember(init) { mutableStateOf(init) }
        EditorDialog("Milestone", { editing = null }, saveEnabled = m.name.isNotBlank(), onSave = { scope.launch { c.db.monitoringDao().upsertMilestone(m); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { c.db.monitoringDao().deleteMilestone(init); editing = null } }) else null) {
            Field("Milestone", m.name, { m = m.copy(name = it) })
            Row(Modifier.horizontalScrollCompat2()) { COMMON_MILESTONES.forEach { s -> AssistChip(onClick = { m = m.copy(name = s) }, label = { Text(s) }, modifier = Modifier.padding(end = 4.dp)) } }
            DateTimeField("Date achieved", m.achievedDate, { m = m.copy(achievedDate = it) }, withTime = false)
            Field("Notes", m.notes, { m = m.copy(notes = it) })
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat2() = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))

// ------------------------------------------------------------------ appointments

@Composable
fun AppointmentScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberFlow(p.id, emptyList()) { c.db.planDao().observeAppointments(p.id) }
    var editing by remember { mutableStateOf<Appointment?>(null) }
    val now = System.currentTimeMillis()
    ScreenScaffold("Appointments", onBack = { nav.back() }, fab = { FloatingActionButton(onClick = { editing = Appointment(childId = p.id, dateTime = now + 86_400_000L) }) { Icon(Icons.Default.Add, "Add") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val up = list.filter { !it.completed && it.dateTime >= now }; val past = list.filter { it.completed || it.dateTime < now }.reversed()
            item { Text("Upcoming", style = MaterialTheme.typography.titleMedium) }
            if (up.isEmpty()) item { EmptyState("No upcoming appointments.") }
            items(up, key = { it.id }) { a -> ApptCard(a) { editing = a } }
            if (past.isNotEmpty()) { item { Text("Past", style = MaterialTheme.typography.titleMedium) }; items(past, key = { it.id }) { a -> ApptCard(a) { editing = a } } }
        }
    }
    editing?.let { init ->
        var a by remember(init) { mutableStateOf(init) }
        EditorDialog("Appointment", { editing = null },
            onSave = { scope.launch { c.db.planDao().upsertAppointment(a); ReminderScheduler.rescheduleAll(ctx); editing = null } },
            onDelete = if (init.id != 0L) ({ scope.launch { ReminderScheduler.cancelAppointment(ctx, init.id); c.db.planDao().deleteAppointment(init); editing = null } }) else null) {
            DateTimeField("Date and time", a.dateTime, { a = a.copy(dateTime = it) })
            Field("Doctor", a.doctorName, { a = a.copy(doctorName = it) })
            Field("Speciality (e.g. paediatric nephrology / urology)", a.specialty, { a = a.copy(specialty = it) })
            Field("Hospital / clinic", a.hospital, { a = a.copy(hospital = it) })
            Field("Purpose", a.purpose, { a = a.copy(purpose = it) })
            Field("Tests to do before the visit", a.testsToDo, { a = a.copy(testsToDo = it) })
            Dropdown("Reminder", listOf(0, 60, 180, 1440, 2880), a.remindMinutesBefore, { when (it) { 0 -> "At the time"; 60 -> "1 hour before"; 180 -> "3 hours before"; 1440 -> "1 day before"; else -> "2 days before" } }, { a = a.copy(remindMinutesBefore = it) })
            Field("Notes / what the doctor said", a.notes, { a = a.copy(notes = it) }, singleLine = false, minLines = 3)
            CheckRow("Completed", a.completed) { a = a.copy(completed = it) }
        }
    }
}

@Composable
private fun ApptCard(a: Appointment, onClick: () -> Unit) = AppCard(onClick = onClick) {
    Text(Fmt.dateTime(a.dateTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(listOf(a.specialty, a.doctorName).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Appointment" }, fontWeight = FontWeight.SemiBold)
    if (a.hospital.isNotBlank()) Text(a.hospital, style = MaterialTheme.typography.bodySmall)
    if (a.testsToDo.isNotBlank()) Text("Tests: ${a.testsToDo}", style = MaterialTheme.typography.bodySmall)
}
