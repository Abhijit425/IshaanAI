package com.ishaan.ai.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.ishaan.ai.BuildConfig
import com.ishaan.ai.MainActivity
import com.ishaan.ai.data.AppSettings
import com.ishaan.ai.data.db.ChildProfile
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.domain.ReportKind
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*
import com.ishaan.ai.ui.theme.UrgentRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(nav: NavHostController, onLock: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val profile = profileState()
    val sampleCount = rememberFlow(Unit, 0) { c.db.profileDao().observeSampleCount() }
    val history = rememberFlow(Unit, emptyList()) { c.db.planDao().observeBackupMeta() }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pwdFor by remember { mutableStateOf<String?>(null) } // BACKUP or RESTORE
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var changePin by remember { mutableStateOf(false) }
    var confirmSample by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableStateOf<Long?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) { pendingUri = uri; pwdFor = "BACKUP" } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { pendingUri = uri; pwdFor = "RESTORE" } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            msg = runCatching {
                withContext(Dispatchers.IO) {
                    val snap = c.snapshot()
                    val f = File(ctx.cacheDir, "share/complete.pdf")
                    c.pdf.build(c.engine.generate(ReportKind.COMPLETE, snap), snap, f)
                    ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }; f.delete()
                }
                "Complete medical history exported as PDF."
            }.getOrElse { "Export failed: ${it.message}" }
            busy = false
        }
    }
    val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            modelProgress = 0
            msg = runCatching { val name = c.llm.importModel(uri) { modelProgress = it }; c.settings.setLlmModel(name); "Model installed. The assistant can now use it." }.getOrElse { "Could not import model: ${it.message}" }
            modelProgress = null
        }
    }

    ScreenScaffold("Settings", onBack = { nav.back() }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            msg?.let { item { InfoBanner(it) } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                AppCard(title = "Child profile", icon = Icons.Default.ChildCare, onClick = { nav.navigate("profile") }) {
                    Text(if (profile is ProfileState.Ready) "${profile.p.name} · born ${Fmt.date(profile.p.dateOfBirthEpochDay?.let { LocalDate.ofEpochDay(it) })}" else "Not created yet — tap to create")
                }
            }
            item {
                AppCard(title = "Backup and restore", icon = Icons.Default.Backup) {
                    InfoBanner("Records live only on this phone. If it is lost, damaged, reset, or the app is uninstalled, they are gone unless you have a backup. Save backups somewhere off the phone (e.g. a USB drive, or a cloud folder you choose). The file is encrypted with your backup password.", UrgentRed)
                    val last = history.firstOrNull { it.type == "BACKUP" && it.status == "OK" }
                    Text("Last backup: ${last?.let { Fmt.dateTime(it.createdAt) + " · ${it.recordCount} records · ${it.notes}" } ?: "never"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { backupLauncher.launch("Ishaan_backup_${LocalDate.now()}.ishaanbackup") }, enabled = !busy) { Text("Create backup") }
                        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Restore") }
                    }
                    Text("Restoring replaces all records on this phone with the backup's contents.", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                AppCard(title = "Export", icon = Icons.Default.PictureAsPdf) {
                    Text("Export the complete medical history (timeline, surgeries, labs, imaging, medicines, growth) as a PDF.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { exportLauncher.launch("Ishaan_complete_history_${LocalDate.now()}.pdf") }, enabled = !busy) { Text("Export complete history") }
                }
            }
            item {
                AppCard(title = "Security", icon = Icons.Default.Lock) {
                    OutlinedButton(onClick = { changePin = true }) { Text("Change PIN") }
                    SwitchRow("Fingerprint / face unlock", s.biometricEnabled) { v -> scope.launch { c.settings.setBiometric(v) } }
                    SwitchRow("Hide content in recent apps & block screenshots", s.secureScreen) { v -> scope.launch { c.settings.setSecureScreen(v) } }
                    Dropdown("Auto-lock after leaving the app", listOf(0, 30, 60, 300, 900), s.autoLockSeconds, { when (it) { 0 -> "Immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; 300 -> "5 minutes"; else -> "15 minutes" } }, { v -> scope.launch { c.settings.setAutoLock(v) } })
                    TextButton(onClick = onLock) { Text("Lock now") }
                }
            }
            item {
                AppCard(title = "Appearance", icon = Icons.Default.Palette) {
                    Dropdown("Theme", listOf("SYSTEM", "LIGHT", "DARK"), s.darkMode, { mapOf("SYSTEM" to "Follow phone", "LIGHT" to "Light", "DARK" to "Dark")[it]!! }, { v -> scope.launch { c.settings.setDarkMode(v) } })
                    SwitchRow("Large text", s.largeText) { v -> scope.launch { c.settings.setLargeText(v) } }
                }
            }
            item {
                AppCard(title = "Optional on-device AI model", icon = Icons.Default.Memory) {
                    val model = c.llm.installedModel(s.llmModelFile)
                    Text(
                        "The app works fully without a model, using its transparent rule-based engine. Optionally, you can download a MediaPipe-compatible model file " +
                            "(for example a Gemma .task file from Kaggle or Hugging Face, on a computer or in the browser) and import it here. It then runs entirely on the phone — no API key, no internet. " +
                            "Model answers are always shown with the records they were based on. Needs a recent phone with enough memory.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(if (model != null) "Installed: ${model.name} (${model.length() / (1024 * 1024)} MB)" else "No model installed", style = MaterialTheme.typography.labelMedium)
                    modelProgress?.let { Text("Copying… ${it / (1024 * 1024)} MB"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { modelLauncher.launch(arrayOf("*/*")) }, enabled = modelProgress == null) { Text(if (model == null) "Import model file" else "Replace") }
                        if (model != null) TextButton(onClick = { c.llm.remove(); scope.launch { c.settings.setLlmModel(null) } }) { Text("Remove") }
                    }
                }
            }
            item {
                AppCard(title = "Sample data (for testing)", icon = Icons.Default.Science) {
                    if (sampleCount > 0) {
                        Text("Sample data is loaded. It is fictional and not about Ishaan.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { scope.launch { c.sample.remove(); msg = "Sample data removed." } }) { Text("Remove sample data") }
                    } else {
                        Text("Loads a clearly labelled fictional child so you can explore the app. Only possible while the app is empty; remove it before entering real records.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { confirmSample = true }) { Text("Load sample data") }
                    }
                }
            }
            item {
                AppCard(title = "Offline & privacy", icon = Icons.Default.AirplanemodeActive) {
                    Text(
                        "• No internet permission: the app cannot connect to any server.\n• No API keys, no accounts, no ads, no analytics.\n• Database encrypted with SQLCipher (AES-256); key protected by the Android Keystore.\n" +
                            "• Documents, photos and videos encrypted with AES-256-GCM.\n• OCR runs on the phone (ML Kit bundled model).\n• Android cloud backup is disabled; only your own encrypted backups exist.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Stored files: ${c.vault.totalBytes() / (1024 * 1024)} MB", style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                AppCard(title = "Activity log", icon = Icons.Default.History) {
                    if (history.isEmpty()) EmptyState("No backups, restores or exports yet.")
                    history.take(8).forEach { h -> KeyValue(Fmt.dateTime(h.createdAt), "${h.type.lowercase().replace('_', ' ')} · ${h.status.lowercase()}" + if (h.notes.isNotBlank()) " · ${h.notes}" else "") }
                }
            }
            item { Text("Ishaan AI ${BuildConfig.VERSION_NAME} · not a medical device · does not diagnose or advise on treatment", style = MaterialTheme.typography.labelSmall) }
        }
    }

    pwdFor?.let { mode ->
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        val ok = if (mode == "BACKUP") p1.length >= 8 && p1 == p2 else p1.isNotEmpty()
        AlertDialog(onDismissRequest = { pwdFor = null },
            title = { Text(if (mode == "BACKUP") "Backup password" else "Restore backup") },
            text = {
                Column {
                    if (mode == "BACKUP") Text("Choose a password of at least 8 characters. Without it the backup cannot be opened — there is no recovery. Write it down somewhere safe.", style = MaterialTheme.typography.bodySmall)
                    else Text("This replaces ALL records on this phone with the backup. Enter the backup password.", style = MaterialTheme.typography.bodySmall, color = UrgentRed)
                    OutlinedTextField(p1, { p1 = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (mode == "BACKUP") OutlinedTextField(p2, { p2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    val uri = pendingUri ?: return@TextButton
                    val pw = p1.toCharArray()
                    pwdFor = null; busy = true
                    scope.launch {
                        if (mode == "BACKUP") {
                            val r = c.backup.backup(uri, pw); msg = r.message
                        } else {
                            val r = c.backup.restore(uri, pw); msg = r.message
                            if (r.ok) { kotlinx.coroutines.delay(1200); restartApp(ctx) }
                        }
                        pw.fill(' '); busy = false
                    }
                }) { Text(if (mode == "BACKUP") "Create backup" else "Restore") }
            },
            dismissButton = { TextButton(onClick = { pwdFor = null }) { Text("Cancel") } })
    }
    if (changePin) {
        var old by remember { mutableStateOf("") }; var new1 by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { changePin = false }, title = { Text("Change PIN") },
            text = {
                Column {
                    OutlinedTextField(old, { if (it.all(Char::isDigit) && it.length <= 8) old = it }, label = { Text("Current PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(new1, { if (it.all(Char::isDigit) && it.length <= 8) new1 = it }, label = { Text("New PIN (4–8 digits)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = new1.length >= 4, onClick = { scope.launch { if (c.settings.verifyPin(old)) { c.settings.setPin(new1); changePin = false; msg = "PIN changed." } else err = "Current PIN is wrong." } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { changePin = false }) { Text("Cancel") } })
    }
    if (confirmSample) ConfirmDialog("Load sample data?", "A fictional demo child will be created. Remove it (Settings → Remove sample data) before entering Ishaan's real records.", confirm = "Load", onConfirm = {
        scope.launch { msg = runCatching { c.sample.load(); c.refreshAlerts(); "Sample data loaded." }.getOrElse { it.message } }
    }, onDismiss = { confirmSample = false })
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange)
    }
}

private fun restartApp(ctx: android.content.Context) {
    val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(i)
    (ctx as? Activity)?.finishAffinity()
    exitProcess(0)
}

@Composable
fun ProfileEditScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val st = profileState()
    if (st is ProfileState.Loading) return
    val init = (st as? ProfileState.Ready)?.p ?: ChildProfile(name = "", dateOfBirthEpochDay = null, sex = "M", primaryDiagnosis = "")
    var p by remember(init.id) { mutableStateOf(init) }
    var dob by remember(init.id) { mutableStateOf(init.dateOfBirthEpochDay?.let { Fmt.startOfDay(LocalDate.ofEpochDay(it)) }) }
    var bw by remember(init.id) { mutableStateOf(init.birthWeightKg?.let { Fmt.num(it) } ?: "") }
    var gest by remember(init.id) { mutableStateOf(init.gestationWeeks?.toString() ?: "") }
    val photo = rememberMediaPickers(arrayOf("image/*")) { uris ->
        scope.launch { uris.firstOrNull()?.let { (u, m) -> val s = withContext(Dispatchers.IO) { c.vault.importUri(u, m ?: "image/jpeg") }; c.vault.delete(p.photoFile); p = p.copy(photoFile = s.fileName) } }
    }
    ScreenScaffold(if (init.id == 0L) "Create profile" else "Edit profile", onBack = { nav.back() }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.photoFile != null) EncryptedImage(p.photoFile!!, Modifier.size(80.dp)) else Icon(Icons.Default.ChildCare, null, Modifier.size(64.dp))
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = photo.pick) { Text("Choose photo") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = photo.photo) { Text("Camera") }
                }
                Field("Name", p.name, { p = p.copy(name = it) })
                DateTimeField("Date of birth", dob, { dob = it }, withTime = false)
                Dropdown("Sex (for WHO growth charts)", listOf("M", "F"), p.sex, { if (it == "M") "Boy" else "Girl" }, { p = p.copy(sex = it) })
                Field("Primary diagnosis (as written by the doctor)", p.primaryDiagnosis, { p = p.copy(primaryDiagnosis = it) })
                Text("e.g. \"Posterior Urethral Valve (PUV)\". Nothing is pre-filled — please enter and verify.", style = MaterialTheme.typography.labelSmall)
                Field("Birth weight (kg)", bw, { bw = it }, number = true)
                Field("Gestation at birth (weeks)", gest, { gest = it.filter(Char::isDigit) }, number = true)
                Field("Birth hospital", p.birthHospital, { p = p.copy(birthHospital = it) })
                Field("Blood group", p.bloodGroup, { p = p.copy(bloodGroup = it) })
                Field("Notes", p.notes, { p = p.copy(notes = it) }, singleLine = false, minLines = 3)
                Spacer(Modifier.height(12.dp))
                Button(enabled = p.name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        c.db.profileDao().upsert(p.copy(
                            dateOfBirthEpochDay = dob?.let { Fmt.toLocalDate(it).toEpochDay() },
                            birthWeightKg = bw.replace(',', '.').toDoubleOrNull(), gestationWeeks = gest.toIntOrNull(),
                            updatedAt = System.currentTimeMillis(),
                        ))
                        c.refreshAlerts(); nav.back()
                    }
                }) { Text("Save profile") }
            }
        }
    }
}
