package com.ishaan.ai.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.AlertEngine
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.ReportKind
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.MedBlue
import com.ishaan.ai.ui.theme.MedBlueDark
import com.ishaan.ai.ui.theme.UrgentRed
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun DashboardScreen(nav: NavHostController, onLock: () -> Unit) {
    WithProfile(nav) { p -> Dashboard(nav, p, onLock) }
}

@Composable
private fun Dashboard(nav: NavHostController, p: ChildProfile, onLock: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val id = p.id
    val alerts = rememberFlow(id, emptyList()) { c.db.planDao().observeActiveAlerts(id) }
    val labs = rememberFlow(id, emptyList()) { c.db.reportDao().observeLabs(id) }
    val imaging = rememberFlow(id, emptyList()) { c.db.reportDao().observeImaging(id) }
    val meds = rememberFlow(id, emptyList()) { c.db.medicationDao().observeMedications(id) }
    val startToday = remember { Fmt.startOfDay(LocalDate.now()) }
    val todayLogs = rememberFlow(id, emptyList()) { c.db.medicationDao().observeLogsSince(id, startToday) }
    val urine = rememberFlow(id, emptyList()) { c.db.monitoringDao().observeUrine(id) }
    val stool = rememberFlow(id, emptyList()) { c.db.monitoringDao().observeStool(id) }
    val growth = rememberFlow(id, emptyList()) { c.db.monitoringDao().observeGrowth(id) }
    val reports = rememberFlow(id, emptyList()) { c.db.reportDao().observeReports(id) }
    val appts = rememberFlow(id, emptyList()) { c.db.planDao().observeAppointments(id) }
    val surgeries = rememberFlow(id, emptyList()) { c.db.historyDao().observeSurgeries(id) }
    val events = rememberFlow(id, emptyList()) { c.db.historyDao().observeEvents(id) }
    val lastBackup = rememberFlow(Unit, null) { c.db.planDao().observeLastBackup() }
    var summary by remember { mutableStateOf<String?>(null) }
    var showEmergency by remember { mutableStateOf(false) }

    // Recompute alerts and the daily summary whenever the underlying records change.
    LaunchedEffect(labs, meds, urine, stool, appts, todayLogs) {
        runCatching { c.refreshAlerts() }
        summary = runCatching {
            val r = c.engine.generate(ReportKind.DAILY, c.snapshot())
            r.sections.drop(1).flatMap { it.findings }.take(4).joinToString("\n") { "• " + it.text }.ifBlank { "No new observations in the last 24 hours." }
        }.getOrNull()
    }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(p, growth, surgeries.size, meds.count { it.active }, onSettings = { nav.navigate("settings") }, onLock = onLock, onEdit = { nav.navigate("profile") }) }
        if (p.isSample) item { InfoBanner("Sample data is loaded — this is NOT Ishaan's data. Remove it in Settings before entering real records.", UrgentRed) }
        item {
            OutlinedButton(onClick = { showEmergency = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = UrgentRed)) {
                Icon(Icons.Default.LocalHospital, null); Spacer(Modifier.width(8.dp)); Text("Emergency warning signs")
            }
        }
        if (alerts.isNotEmpty()) item {
            AppCard(title = "Health alerts", icon = Icons.Default.NotificationsActive) {
                alerts.take(6).forEach { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { SeverityPill(a.severity); Spacer(Modifier.width(6.dp)); Text(a.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
                            Text(a.message, style = MaterialTheme.typography.bodySmall, maxLines = if (a.severity == "URGENT") 10 else 3, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { scope.launch { c.db.planDao().dismissAlert(a.id) } }) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }
        item { QuickLog(nav) }
        item { KidneyWidget(nav, labs, imaging) }
        item { MedsWidget(nav, meds, todayLogs) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val todayU = urine.filter { it.dateTime >= startToday }
                MiniWidget(Modifier.weight(1f), "Urine", Icons.Default.WaterDrop, "${todayU.size} today", urine.firstOrNull()?.let { "Last ${Fmt.time(it.dateTime)}" + if (it.flow != "NOT_OBSERVED") " · ${it.flow.lowercase().replace('_', ' ')}" else "" } ?: "No entries") { nav.navigate("urine") }
                val todayS = stool.filter { it.dateTime >= startToday }
                MiniWidget(Modifier.weight(1f), "Potty", Icons.Default.BabyChangingStation, "${todayS.size} today", stool.firstOrNull()?.let { "Last ${Fmt.time(it.dateTime)}" + (it.bristolType?.let { b -> " · type $b" } ?: "") } ?: "No entries") { nav.navigate("stool") }
            }
        }
        item {
            val w = growth.firstOrNull { it.weightKg != null }; val h = growth.firstOrNull { it.lengthCm != null }
            AppCard(title = "Growth tracker", icon = Icons.Default.Straighten, onClick = { nav.navigate("growth") }) {
                if (w == null && h == null) EmptyState("No measurements yet. Tap to add weight and length.")
                w?.let { KeyValue("Weight", "${Fmt.num(it.weightKg)} kg · ${Fmt.date(it.date)}") }
                h?.let { KeyValue("Length/height", "${Fmt.num(it.lengthCm)} cm · ${Fmt.date(it.date)}") }
            }
        }
        item {
            AppCard(title = "Recent medical reports", icon = Icons.Default.Description, onClick = { nav.navigate("reports") }) {
                if (reports.isEmpty()) EmptyState("No reports uploaded yet.")
                reports.take(3).forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("report/${r.id}") }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ReportCategory.of(r.category).label} · ${Fmt.date(r.reportDate)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!r.verified && r.ocrStatus == "DONE") Pill("Review", com.ishaan.ai.ui.theme.ReviewAmber)
                    }
                }
            }
        }
        item {
            val upcoming = appts.filter { !it.completed && it.dateTime >= System.currentTimeMillis() }
            AppCard(title = "Upcoming appointments", icon = Icons.Default.Event, onClick = { nav.navigate("appointments") }) {
                if (upcoming.isEmpty()) EmptyState("No upcoming appointments.")
                upcoming.take(2).forEach { a -> KeyValue(Fmt.dateTime(a.dateTime), listOf(a.specialty, a.doctorName).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Appointment" }) }
            }
        }
        item {
            AppCard(title = "AI health summary (today)", icon = Icons.Default.AutoAwesome, trailing = { Pill("Rule-based", MedBlue) }) {
                Text(summary ?: "Preparing…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("healthreport/DAILY") }) { Text("Daily") }
                    OutlinedButton(onClick = { nav.navigate("healthreport/WEEKLY") }) { Text("Weekly") }
                    Button(onClick = { nav.navigate("healthreport/DOCTOR") }) { Text("Doctor report") }
                }
            }
        }
        item {
            AppCard(title = "Recent medical activity", icon = Icons.Default.History, onClick = { nav.navigate("history") }) {
                if (events.isEmpty()) EmptyState("No history entries yet.")
                events.take(4).forEach { e -> KeyValue(Fmt.date(e.dateTime), e.title) }
            }
        }
        item {
            val days = lastBackup?.let { ((System.currentTimeMillis() - it.createdAt) / 86_400_000L).toInt() }
            if (days == null || days > 30) InfoBanner(
                if (days == null) "No backup yet. If this phone is lost or reset, all records are lost. Make an encrypted backup in Settings."
                else "Last backup was $days days ago. Consider making a new encrypted backup.", UrgentRed, Icons.Default.Backup,
            )
        }
    }
    if (showEmergency) AlertDialog(
        onDismissRequest = { showEmergency = false },
        icon = { Icon(Icons.Default.LocalHospital, null, tint = UrgentRed) },
        title = { Text("When to seek emergency care") },
        text = { Text(AlertEngine.EMERGENCY_GUIDANCE) },
        confirmButton = { TextButton(onClick = { showEmergency = false }) { Text("Close") } },
    )
}

@Composable
private fun Header(p: ChildProfile, growth: List<GrowthRecord>, surgeries: Int, meds: Int, onSettings: () -> Unit, onLock: () -> Unit, onEdit: () -> Unit) {
    val dob = p.dateOfBirthEpochDay?.let { LocalDate.ofEpochDay(it) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(MedBlue, MedBlueDark))).padding(18.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ishaan AI", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onLock) { Icon(Icons.Default.Lock, "Lock", tint = Color.White) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onEdit)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    if (p.photoFile != null) EncryptedImage(p.photoFile, Modifier.fillMaxSize(), 300)
                    else Icon(Icons.Default.ChildCare, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Born ${Fmt.date(dob)} · ${Fmt.age(dob)}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    if (p.primaryDiagnosis.isNotBlank()) Text(p.primaryDiagnosis, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("Weight", growth.firstOrNull { it.weightKg != null }?.let { "${Fmt.num(it.weightKg)} kg" } ?: "—")
                Stat("Height", growth.firstOrNull { it.lengthCm != null }?.let { "${Fmt.num(it.lengthCm)} cm" } ?: "—")
                Stat("Surgeries", surgeries.toString())
                Stat("Medicines", meds.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) = Column {
    Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun QuickLog(nav: NavHostController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuickButton(Modifier.weight(1f), "Urine", Icons.Default.WaterDrop) { nav.navigate("urine") }
        QuickButton(Modifier.weight(1f), "Potty", Icons.Default.BabyChangingStation) { nav.navigate("stool") }
        QuickButton(Modifier.weight(1f), "Symptom", Icons.Default.Thermostat) { nav.navigate("symptoms") }
        QuickButton(Modifier.weight(1f), "Report", Icons.Default.UploadFile) { nav.navigate("reports") }
    }
}

@Composable
private fun QuickButton(modifier: Modifier, label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MiniWidget(modifier: Modifier, title: String, icon: ImageVector, big: String, small: String, onClick: () -> Unit) {
    AppCard(modifier, title = title, icon = icon, onClick = onClick) {
        Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(small, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@Composable
private fun KidneyWidget(nav: NavHostController, labs: List<ExtractedLabResult>, imaging: List<ImagingFinding>) {
    val cr = labs.filter { it.testCode == LabTest.CREATININE.name }.sortedBy { it.sampleDate }
    AppCard(title = "Kidney health overview", icon = Icons.Default.Favorite, onClick = { nav.navigate("kidney") }) {
        val last = cr.lastOrNull()
        if (last == null) EmptyState("No creatinine results yet. Upload a kidney function report.")
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Creatinine ${last.valueText} ${last.unit}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                VerificationPill(last.verification)
            }
            Text("${Fmt.date(last.sampleDate)}" + (if (last.refText.isNotBlank()) " · printed ref ${last.refText}" else ""), style = MaterialTheme.typography.bodySmall)
            val pts = cr.filter { it.valueNumeric != null && it.unit.equals(last.unit, true) }
            if (pts.size >= 2) TrendChart(pts.map { ChartPoint(it.sampleDate, it.valueNumeric!!, it.verification == Verification.UNVERIFIED) }, Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        val us = imaging.filter { it.modality == "ULTRASOUND" }.maxOfOrNull { it.studyDate }
        val dm = imaging.filter { it.modality == "DMSA" }.maxOfOrNull { it.studyDate }
        KeyValue("Latest ultrasound", us?.let { Fmt.date(it) } ?: "none recorded")
        KeyValue("Latest DMSA", dm?.let { Fmt.date(it) } ?: "none recorded")
        imaging.filter { it.modality == "DMSA" && it.studyDate == dm && it.param == ImagingParam.SPLIT_FUNCTION.name }.takeIf { it.isNotEmpty() }?.let { s ->
            KeyValue("Split function", s.sortedBy { it.side }.joinToString(" · ") { "${it.side.lowercase().replaceFirstChar { c -> c.uppercase() }} ${Fmt.num(it.valueNumeric)}%" })
        }
        Text("Trends only — interpretation belongs to Ishaan's nephrologist.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MedsWidget(nav: NavHostController, meds: List<Medication>, todayLogs: List<MedicationLog>) {
    val active = meds.filter { it.active }
    AppCard(title = "Medication tracker", icon = Icons.Default.Medication, onClick = { nav.navigate("meds") }) {
        if (active.isEmpty()) EmptyState("No current medicines recorded.")
        active.take(5).forEach { m ->
            val given = todayLogs.count { it.medicationId == m.id && it.status == "GIVEN" }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${m.prescribedDose} ${m.doseUnit} · ${m.frequency}".trim(), style = MaterialTheme.typography.bodySmall)
                }
                Text("$given given today", style = MaterialTheme.typography.labelSmall)
                if (m.needsConfirmation) { Spacer(Modifier.width(4.dp)); Pill("Unclear Rx", com.ishaan.ai.ui.theme.ReviewAmber) }
            }
        }
    }
}
