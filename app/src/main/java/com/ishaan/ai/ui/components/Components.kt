@file:OptIn(ExperimentalMaterial3Api::class)

package com.ishaan.ai.ui.components

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.ishaan.ai.AppContainer
import com.ishaan.ai.data.db.Verification
import com.ishaan.ai.domain.Fmt
import com.ishaan.ai.ui.theme.OkGreen
import com.ishaan.ai.ui.theme.ReviewAmber
import com.ishaan.ai.ui.theme.UrgentRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = fab,
        content = content,
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, title: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing()
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 8.dp))
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun VerificationPill(v: String) = when (v) {
    Verification.UNVERIFIED -> Pill("OCR · unverified", ReviewAmber)
    Verification.VERIFIED -> Pill("Verified", OkGreen)
    Verification.CORRECTED -> Pill("Corrected", MaterialTheme.colorScheme.primary)
    else -> Pill("Parent-entered", MaterialTheme.colorScheme.secondary)
}

@Composable
fun SeverityPill(sev: String) = when (sev) {
    "URGENT" -> Pill("URGENT", UrgentRed)
    "REVIEW" -> Pill("Review", ReviewAmber)
    else -> Pill("Info", MaterialTheme.colorScheme.primary)
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, number: Boolean = false, minLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine && minLines == 1, minLines = minLines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Date (and optional time) picker. Value in epoch millis, local time zone. */
@Composable
fun DateTimeField(label: String, value: Long?, onChange: (Long) -> Unit, withTime: Boolean = true, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var showDate by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    OutlinedTextField(
        value = if (value == null) "" else if (withTime) Fmt.dateTime(value) else Fmt.date(value),
        onValueChange = {}, readOnly = true, label = { Text(label) },
        trailingIcon = { IconButton(onClick = { showDate = true }) { Icon(Icons.Default.CalendarMonth, "Pick date") } },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    if (showDate) {
        val initial = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    showDate = false
                    val sel = state.selectedDateMillis ?: return@TextButton
                    val day = Instant.ofEpochMilli(sel).atZone(ZoneOffset.UTC).toLocalDate()
                    val old = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: java.time.LocalTime.now().withSecond(0).withNano(0)
                    if (withTime) {
                        TimePickerDialog(ctx, { _, h, m -> onChange(day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()) }, old.hour, old.minute, false).show()
                    } else onChange(day.atStartOfDay(zone).toInstant().toEpochMilli())
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Delete", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EditorDialog(title: String, onDismiss: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean = true, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.92f)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onSave, enabled = saveEnabled) { Text("Save") }
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) ConfirmDialog("Delete this record?", "This permanently removes it from the phone. This cannot be undone unless you have a backup.", onConfirm = { onDelete() }, onDismiss = { confirmDelete = false })
}

data class ChartPoint(val time: Long, val value: Double, val unverified: Boolean = false)

/** Simple line chart: measured points only, optional printed reference band. Unverified points are hollow. */
@Composable
fun TrendChart(points: List<ChartPoint>, modifier: Modifier = Modifier, refLow: Double? = null, refHigh: Double? = null, extraLines: List<List<ChartPoint>> = emptyList()) {
    if (points.isEmpty()) { EmptyState("No values to chart yet."); return }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
    val guide = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val all = points + extraLines.flatten()
    val minT = all.minOf { it.time }; val maxT = maxOf(all.maxOf { it.time }, minT + 1)
    val vals = all.map { it.value } + listOfNotNull(refLow, refHigh)
    val minV = (vals.min() * 0.9).coerceAtLeast(0.0); val maxV = (vals.max() * 1.1).coerceAtLeast(minV + 0.01)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(180.dp).padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
            fun x(t: Long) = (t - minT).toFloat() / (maxT - minT) * size.width
            fun y(v: Double) = size.height - ((v - minV) / (maxV - minV)).toFloat() * size.height
            for (k in 0..4) { val yy = size.height * k / 4; drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1f) }
            if (refLow != null && refHigh != null) drawRect(band, topLeft = Offset(0f, y(refHigh)), size = androidx.compose.ui.geometry.Size(size.width, y(refLow) - y(refHigh)))
            extraLines.forEach { l -> if (l.size > 1) { val p = Path(); l.forEachIndexed { i, c -> if (i == 0) p.moveTo(x(c.time), y(c.value)) else p.lineTo(x(c.time), y(c.value)) }; drawPath(p, guide, style = Stroke(2f)) } }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.time), y(p.value)) else path.lineTo(x(p.time), y(p.value)) }
            if (points.size > 1) drawPath(path, primary, style = Stroke(5f))
            points.forEach { p -> if (p.unverified) drawCircle(primary, 8f, Offset(x(p.time), y(p.value)), style = Stroke(3f)) else drawCircle(primary, 8f, Offset(x(p.time), y(p.value))) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Fmt.date(minT), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("range ${Fmt.num(minV)}–${Fmt.num(maxV)}", style = MaterialTheme.typography.labelSmall)
            Text(Fmt.date(maxT), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** Decrypts and shows a photo thumbnail. */
@Composable
fun EncryptedImage(fileName: String, modifier: Modifier = Modifier, maxSide: Int = 600, crop: Boolean = true) {
    val c = LocalContainer.current
    var bmp by remember(fileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileName) { bmp = withContext(Dispatchers.IO) { c.vault.decodeBitmap(fileName, maxSide) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, null) }
}

data class MediaItem(val fileName: String, val mime: String, val label: String = "")

@Composable
fun MediaStrip(items: List<MediaItem>, onDelete: ((MediaItem) -> Unit)? = null) {
    var open by remember { mutableStateOf<MediaItem?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { m ->
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).clickable { open = m }) {
                if (m.mime.startsWith("image/")) EncryptedImage(m.fileName, Modifier.fillMaxSize(), 300)
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (m.mime.startsWith("video/")) Icons.Default.PlayCircle else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
    open?.let { m -> MediaViewer(m, onDismiss = { open = null }, onDelete = onDelete?.let { d -> { d(m); open = null } }) }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
fun MediaViewer(item: MediaItem, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    var tempFile by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf(false) }
    DisposableEffect(item.fileName) { onDispose { tempFile?.delete() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    Text(item.label, color = Color.White, modifier = Modifier.weight(1f))
                    if (!item.mime.startsWith("image/") && !item.mime.startsWith("video/")) {
                        TextButton(onClick = {
                            val f = c.vault.decryptToTemp(item.fileName, item.label.ifBlank { "document" })
                            tempFile = f
                            openExternally(ctx, f, item.mime)
                        }) { Text("Open", color = Color.White) }
                    }
                    if (onDelete != null) IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        item.mime.startsWith("image/") -> EncryptedImage(item.fileName, Modifier.fillMaxSize(), 2400, crop = false)
                        item.mime.startsWith("video/") -> {
                            LaunchedEffect(item.fileName) { tempFile = withContext(Dispatchers.IO) { c.vault.decryptToTemp(item.fileName, "video.mp4") } }
                            tempFile?.let { f ->
                                AndroidView(factory = { vctx ->
                                    VideoView(vctx).apply {
                                        setVideoPath(f.absolutePath)
                                        setMediaController(MediaController(vctx).also { it.setAnchorView(this) })
                                        setOnPreparedListener { start() }
                                    }
                                }, modifier = Modifier.fillMaxWidth())
                            } ?: CircularProgressIndicator()
                        }
                        else -> Text("Tap Open to view this document in a viewer app on your phone.", color = Color.White, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
    if (confirm && onDelete != null) ConfirmDialog("Delete this file?", "The original file will be permanently removed from the phone.", onConfirm = onDelete, onDismiss = { confirm = false })
}

fun openExternally(ctx: android.content.Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open with")) }
}

fun shareFile(ctx: android.content.Context, f: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, title)) }
}

/**
 * Launchers for adding media: pick existing files, take a photo, record a video. Captured files go to private
 * cache first and are encrypted into the vault by the caller, then deleted.
 */
class MediaPickers(val pick: () -> Unit, val photo: () -> Unit, val video: () -> Unit)

@Composable
fun rememberMediaPickers(mimeTypes: Array<String>, onPicked: (List<Pair<Uri, String?>>) -> Unit): MediaPickers {
    val ctx = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMime by remember { mutableStateOf("image/jpeg") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onPicked(uris.map { it to null }) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> pendingUri?.let { if (ok) onPicked(listOf(it to "image/jpeg")) } }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> pendingUri?.let { if (ok) onPicked(listOf(it to "video/mp4")) } }
    fun newCapture(ext: String): Uri {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }
    return MediaPickers(
        pick = { picker.launch(mimeTypes) },
        photo = { val u = newCapture("jpg"); pendingUri = u; pendingMime = "image/jpeg"; runCatching { takePhoto.launch(u) } },
        video = { val u = newCapture("mp4"); pendingUri = u; pendingMime = "video/mp4"; runCatching { takeVideo.launch(u) } },
    )
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun InfoBanner(text: String, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector = Icons.Default.Info) {
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
