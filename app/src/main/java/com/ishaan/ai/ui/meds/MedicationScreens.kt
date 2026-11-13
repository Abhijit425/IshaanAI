package com.ishaan.ai.ui.meds

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ishaan.ai.data.db.*
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.reminders.ReminderScheduler
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.ReviewAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NO_DOSE_ADVICE = "The app stores the dose exactly as the doctor prescribed. It never calculates, suggests or changes doses, and never advises starting or stopping a medicine."

@Composable
fun MedicationsScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val meds = rememberFlow(p.id, emptyList()) { c.db.medicationDao().observeMedications(p.id) }
    var editing by remember { mutableStateOf<Medication?>(null) }
    ScreenScaffold("Medicines", onBack = { nav.back() }, fab = {
        ExtendedFloatingActionButton(onClick = { editing = Medication(childId = p.id, name = "", prescribedDose = "", startDate = System.currentTimeMillis()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add medicine") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoBanner(NO_DOSE_ADVICE) }
            if (!ReminderScheduler.canScheduleExact(ctx)) item {
                AppCard {
                    Text("Reminders may arrive a few minutes late. Allow \"Alarms & reminders\" for exact times.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (Build.VERSION.SDK_INT >= 31) runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) } }) { Text("Allow exact reminders") }
                }
            }
            val active = meds.filter { it.active }; val stopped = meds.filter { !it.active }
            item { Text("Current", style = MaterialTheme.typography.titleMedium) }
            if (active.isEmpty()) item { EmptyState("No current medicines.") }
            items(active, key = { it.id }) { m -> MedCard(m) { nav.navigate("med/${m.id}") } }
            if (stopped.isNotEmpty()) { item { Text("Stopped / past", style = MaterialTheme.typography.titleMedium) }; items(stopped, key = { it.id }) { m -> MedCard(m) { nav.navigate("med/${m.id}") } } }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }
    editing?.let { MedEditor(it, onDismiss = { editing = null }, onSaved = { id -> editing = null; nav.navigate("med/$id") }) }
}

@Composable
private fun MedCard(m: Medication, onClick: () -> Unit) = AppCard(onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${m.name} ${m.strength}".trim(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("${m.prescribedDose} ${m.doseUnit} · ${m.frequency}".trim(), style = MaterialTheme.typography.bodySmall)
            Text(if (m.active) "Since ${Fmt.date(m.startDate)}" else "Stopped ${Fmt.date(m.discontinuedDate ?: m.endDate)}", style = MaterialTheme.typography.labelSmall)
        }
        if (m.needsConfirmation) Pill("Unclear Rx", ReviewAmber)
    }
}

@Composable
fun MedEditor(initial: Medication, onDismiss: () -> Unit, onSaved: (Long) -> Unit = { onDismiss() }) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var m by remember { mutableStateOf(initial) }
    val pickers = rememberMediaPickers(arrayOf("image/*", "application/pdf")) { uris ->
        scope.launch { uris.firstOrNull()?.let { (u, mime) -> val s = withContext(Dispatchers.IO) { c.vault.importUri(u, mime ?: "image/jpeg") }; c.vault.delete(m.prescriptionFile); m = m.copy(prescriptionFile = s.fileName) } }
    }
    EditorDialog(if (initial.id == 0L) "Add medicine" else "Edit medicine", onDismiss, saveEnabled = m.name.isNotBlank() && m.prescribedDose.isNotBlank(),
        onSave = { scope.launch { val id = c.db.medicationDao().upsert(m.copy(updatedAt = System.currentTimeMillis())); c.refreshAlerts(); onSaved(if (initial.id == 0L) id else initial.id) } },
        onDelete = if (initial.id != 0L) ({ scope.launch { c.vault.delete(m.prescriptionFile); c.db.medicationDao().delete(initial); onDismiss() } }) else null) {
        InfoBanner("Copy everything exactly from the prescription. If anything is unclear, tick \"Prescription unclear\" and confirm with the doctor or pharmacist.")
        Field("Medicine name (brand)", m.name, { m = m.copy(name = it) })
        Field("Generic name", m.genericName, { m = m.copy(genericName = it) })
        Field("Strength (e.g. 125 mg/5 ml)", m.strength, { m = m.copy(strength = it) })
        Field("Prescribed dose (exactly as written)", m.prescribedDose, { m = m.copy(prescribedDose = it) })
        Field("Dose unit (ml, mg, drops, tablet)", m.doseUnit, { m = m.copy(doseUnit = it) })
        Field("Frequency (as written, e.g. once at night)", m.frequency, { m = m.copy(frequency = it) })
        Field("Route (oral, etc.)", m.route, { m = m.copy(route = it) })
        DateTimeField("Start date", m.startDate, { m = m.copy(startDate = it) }, withTime = false)
        DateTimeField("Planned end date (if prescribed)", m.endDate, { m = m.copy(endDate = it) }, withTime = false)
        Field("Prescribing doctor", m.prescribingDoctor, { m = m.copy(prescribingDoctor = it) })
        Field("Special instructions", m.specialInstructions, { m = m.copy(specialInstructions = it) }, singleLine = false, minLines = 2)
        CheckRow("Prescription unclear — needs confirmation", m.needsConfirmation) { m = m.copy(needsConfirmation = it) }
        Text("Prescription photo", style = MaterialTheme.typography.titleSmall)
        m.prescriptionFile?.let { MediaStrip(listOf(MediaItem(it, "image/jpeg", "Prescription"))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pickers.photo) { Icon(Icons.Default.PhotoCamera, null); Text("Photo") }
            OutlinedButton(onClick = pickers.pick) { Icon(Icons.Default.Upload, null); Text("Upload") }
        }
    }
}

@Composable
fun MedicationDetailScreen(nav: NavHostController, id: Long) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val med = rememberFlow(id, null) { c.db.medicationDao().observeMedication(id) }
    val schedules = rememberFlow(id, emptyList()) { c.db.medicationDao().observeSchedules(id) }
    val logs = rememberFlow(id, emptyList()) { c.db.medicationDao().observeLogs(id) }
    var edit by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    val m = med ?: return ScreenScaffold("Medicine", onBack = { nav.back() }) { Box(Modifier.padding(it)) }
    fun log(status: String) = scope.launch { c.db.medicationDao().insertLog(MedicationLog(medicationId = id, actionTime = System.currentTimeMillis(), status = status)); c.refreshAlerts() }
    ScreenScaffold(m.name, onBack = { nav.back() }, actions = { IconButton(onClick = { edit = true }) { Icon(Icons.Default.Edit, "Edit") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard(title = "Prescription", icon = Icons.Default.Medication) {
                    if (m.needsConfirmation) InfoBanner("Marked unclear — confirm the dose and timing with the doctor or pharmacist.", ReviewAmber)
                    KeyValue("Medicine", "${m.name} ${m.strength}".trim())
                    if (m.genericName.isNotBlank()) KeyValue("Generic", m.genericName)
                    KeyValue("Dose (as prescribed)", "${m.prescribedDose} ${m.doseUnit}".trim())
                    KeyValue("Frequency", m.frequency)
                    if (m.route.isNotBlank()) KeyValue("Route", m.route)
                    KeyValue("Started", Fmt.date(m.startDate))
                    m.endDate?.let { KeyValue("Planned end", Fmt.date(it)) }
                    if (m.prescribingDoctor.isNotBlank()) KeyValue("Prescribed by", m.prescribingDoctor)
                    if (m.specialInstructions.isNotBlank()) KeyValue("Instructions", m.specialInstructions)
                    if (!m.active) KeyValue("Stopped", "${Fmt.date(m.discontinuedDate)} ${m.discontinuedReason}")
                    m.prescriptionFile?.let { MediaStrip(listOf(MediaItem(it, "image/jpeg", "Prescription"))) }
                    Text(NO_DOSE_ADVICE, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (m.active) item {
                AppCard(title = "Log a dose", icon = Icons.Default.CheckCircle) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { log("GIVEN") }) { Text("Given now") }
                        OutlinedButton(onClick = { log("MISSED") }) { Text("Missed") }
                        OutlinedButton(onClick = { log("SKIPPED") }) { Text("Skipped") }
                    }
                    Text("If a dose is missed, follow the doctor's or pharmacist's instructions — don't double up unless told to.", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (m.active) item {
                AppCard(title = "Reminder times", icon = Icons.Default.Alarm, trailing = {
                    TextButton(onClick = {
                        TimePickerDialog(ctx, { _, h, min -> scope.launch { c.db.medicationDao().insertSchedule(MedicationSchedule(medicationId = id, minuteOfDay = h * 60 + min)); ReminderScheduler.rescheduleAll(ctx) } }, 9, 0, false).show()
                    }) { Text("+ Add time") }
                }) {
                    if (schedules.isEmpty()) EmptyState("No reminders. Add the times written on the prescription.")
                    schedules.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.minuteOfDay(s.minuteOfDay), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { scope.launch { ReminderScheduler.cancelSchedule(ctx, s.id); c.db.medicationDao().deleteSchedule(s) } }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    }
                }
            }
            item {
                if (m.active) OutlinedButton(onClick = { stop = true }, modifier = Modifier.fillMaxWidth()) { Text("Record that the doctor stopped this medicine") }
                else OutlinedButton(onClick = { scope.launch { c.db.medicationDao().upsert(m.copy(active = true, discontinuedDate = null, discontinuedReason = "")); ReminderScheduler.rescheduleAll(ctx) } }, modifier = Modifier.fillMaxWidth()) { Text("Mark as current again") }
            }
            item { Text("Dose history", style = MaterialTheme.typography.titleMedium) }
            if (logs.isEmpty()) item { EmptyState("No doses logged.") }
            items(logs, key = { it.id }) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.dateTime(l.actionTime), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Pill(l.status.lowercase(), if (l.status == "GIVEN") com.ishaan.ai.ui.theme.OkGreen else ReviewAmber)
                    IconButton(onClick = { scope.launch { c.db.medicationDao().deleteLog(l) } }) { Icon(Icons.Default.Close, "Delete log") }
                }
            }
        }
    }
    if (edit) MedEditor(m, onDismiss = { edit = false })
    if (stop) {
        var reason by remember { mutableStateOf("") }
        var date by remember { mutableLongStateOf(System.currentTimeMillis()) }
        AlertDialog(onDismissRequest = { stop = false }, title = { Text("Medicine stopped") },
            text = { Column { Text("Only record this when Ishaan's doctor has stopped the medicine."); DateTimeField("Stopped on", date, { date = it }, withTime = false); Field("Reason / doctor's instruction", reason, { reason = it }) } },
            confirmButton = { TextButton(onClick = { scope.launch { schedules.forEach { ReminderScheduler.cancelSchedule(ctx, it.id) }; c.db.medicationDao().upsert(m.copy(active = false, discontinuedDate = date, discontinuedReason = reason)); stop = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { stop = false }) { Text("Cancel") } })
    }
}
