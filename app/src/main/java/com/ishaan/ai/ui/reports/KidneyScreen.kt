package com.ishaan.ai.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.ishaan.ai.domain.HealthSnapshot
import com.ishaan.ai.ui.back
import com.ishaan.ai.ui.components.*

/** PUV-focused kidney monitoring: lab panel, eGFR (only when valid), creatinine trend, ultrasound and DMSA comparisons. */
@Composable
fun KidneyScreen(nav: NavHostController) = WithProfile(nav) { p ->
    val c = LocalContainer.current
    val labs = rememberFlow(p.id, emptyList()) { c.db.reportDao().observeLabs(p.id) }
    val imaging = rememberFlow(p.id, emptyList()) { c.db.reportDao().observeImaging(p.id) }
    val growth = rememberFlow(p.id, emptyList()) { c.db.monitoringDao().observeGrowth(p.id) }
    val snap = HealthSnapshot(profile = p, labs = labs.sortedBy { it.sampleDate }, imaging = imaging, growth = growth)
    var addImg by remember { mutableStateOf<ImagingFinding?>(null) }
    var addLab by remember { mutableStateOf<ExtractedLabResult?>(null) }
    ScreenScaffold("Kidney monitoring", onBack = { nav.back() }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { InfoBanner("This screen organises measured values and imaging reports. It does not judge kidney health, diagnose CKD, or use creatinine alone to describe kidney function. Lab values and imaging findings are kept separate.") }
            item {
                AppCard(title = "Laboratory panel (latest)", icon = Icons.Default.Science, trailing = { TextButton(onClick = { addLab = ExtractedLabResult(childId = p.id, testCode = LabTest.CREATININE.name, displayName = "", valueText = "", sampleDate = System.currentTimeMillis()) }) { Text("+ Add") } }) {
                    val panel = LabTest.kidneyPanel + listOf(LabTest.URINE_PROTEIN, LabTest.URINE_PUS_CELLS, LabTest.URINE_CULTURE, LabTest.BP_SYSTOLIC, LabTest.BP_DIASTOLIC, LabTest.URINE_OUTPUT)
                    var any = false
                    panel.forEach { t ->
                        val l = snap.latestLab(t) ?: return@forEach
                        any = true
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate("lab/${t.name}") }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, modifier = Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium)
                            Column(Modifier.weight(0.4f)) {
                                Text("${l.valueText} ${l.unit}".trim(), fontWeight = FontWeight.SemiBold)
                                Text(Fmt.date(l.sampleDate), style = MaterialTheme.typography.labelSmall)
                            }
                            VerificationPill(l.verification)
                        }
                        HorizontalDivider()
                    }
                    if (!any) EmptyState("No kidney-related results yet.")
                }
            }
            item {
                val sec = c.engine.egfrSection(snap)
                AppCard(title = "Estimated GFR", icon = Icons.Default.Calculate) {
                    sec.findings.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                    sec.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item {
                val cr = snap.labSeries(LabTest.CREATININE).filter { it.valueNumeric != null }
                val unit = cr.lastOrNull()?.unit
                AppCard(title = "Creatinine timeline", icon = Icons.Default.ShowChart, onClick = { nav.navigate("lab/CREATININE") }) {
                    if (cr.isEmpty()) EmptyState("No creatinine values yet.")
                    else TrendChart(cr.filter { it.unit.equals(unit, true) }.map { ChartPoint(it.sampleDate, it.valueNumeric!!, it.verification == Verification.UNVERIFIED) }, refLow = cr.last().refLow, refHigh = cr.last().refHigh)
                    if (cr.isNotEmpty()) Text("Tap for the full table with change and printed reference range for every result.", style = MaterialTheme.typography.labelSmall)
                }
            }
            val sections = c.engine.imagingSections(snap)
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Imaging comparison", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { addImg = ImagingFinding(childId = p.id, modality = "ULTRASOUND", studyDate = System.currentTimeMillis(), side = "RIGHT", parameter = ImagingParam.APRPD.name, unit = "mm") }) { Text("+ Add finding") }
                }
            }
            if (sections.isEmpty()) item { EmptyState("No ultrasound or DMSA findings yet. Upload the reports or add findings manually (right and left kidneys separately).") }
            sections.forEach { s ->
                item {
                    AppCard(title = s.title, icon = Icons.Default.Biotech) {
                        s.table?.let { TableView(it) }
                        s.findings.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                        s.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            val apd = imaging.filter { it.parameter == ImagingParam.APRPD.name && it.valueNumeric != null }
            if (apd.size >= 2) item {
                AppCard(title = "Renal pelvis AP diameter over time", icon = Icons.Default.ShowChart) {
                    val right = apd.filter { it.side == "RIGHT" }.sortedBy { it.studyDate }.map { ChartPoint(it.studyDate, it.valueNumeric!!, it.verification == Verification.UNVERIFIED) }
                    val left = apd.filter { it.side == "LEFT" }.sortedBy { it.studyDate }.map { ChartPoint(it.studyDate, it.valueNumeric!!, it.verification == Verification.UNVERIFIED) }
                    TrendChart(right.ifEmpty { left }, extraLines = if (right.isNotEmpty()) listOf(left) else emptyList())
                    Text("Solid line: right kidney · grey line: left kidney (as reported, mm).", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    addImg?.let { ImagingEditor(it, onDismiss = { addImg = null }) }
    addLab?.let { LabEditor(it, onDismiss = { addLab = null }) }
}
