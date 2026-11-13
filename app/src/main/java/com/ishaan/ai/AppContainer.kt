package com.ishaan.ai

import android.app.Application
import android.content.Context
import com.ishaan.ai.data.BackupService
import com.ishaan.ai.data.OcrService
import com.ishaan.ai.data.PdfReportBuilder
import com.ishaan.ai.data.ReportRepository
import com.ishaan.ai.data.SampleData
import com.ishaan.ai.data.SettingsRepository
import com.ishaan.ai.data.db.AppDatabase
import com.ishaan.ai.domain.AlertEngine
import com.ishaan.ai.domain.AnalysisEngine
import com.ishaan.ai.domain.HealthSnapshot
import com.ishaan.ai.domain.LocalLlm
import com.ishaan.ai.domain.RuleAssistant
import com.ishaan.ai.domain.WhoGrowth
import com.ishaan.ai.reminders.ReminderScheduler
import com.ishaan.ai.security.KeyManager
import com.ishaan.ai.security.Vault

/** Manual dependency container — small, explicit, no reflection framework. */
class AppContainer(val context: Context) {
    private val keys = KeyManager(context)
    private val fileKey by lazy { keys.fileKey() }
    val db: AppDatabase = AppDatabase.open(context, keys.databasePassphrase())
    val vault = Vault(context) { fileKey }
    val settings = SettingsRepository(context)
    val ocr = OcrService(context, vault)
    val reports = ReportRepository(db, vault, ocr)
    val backup = BackupService(context, db, vault) { fileKey }
    val who: WhoGrowth? = runCatching { WhoGrowth(context.assets.open("who_lms.csv").bufferedReader().readLines()) }.getOrNull()
    val engine = AnalysisEngine(who)
    val assistant = RuleAssistant(engine)
    val llm = LocalLlm(context)
    val pdf get() = PdfReportBuilder()
    val sample = SampleData(db)

    suspend fun snapshot(): HealthSnapshot {
        val p = db.profileDao().getProfile() ?: return HealthSnapshot(null)
        val id = p.id
        val meds = db.medicationDao().medications(id)
        return HealthSnapshot(
            profile = p,
            events = db.historyDao().events(id),
            diagnoses = db.historyDao().diagnoses(id),
            surgeries = db.historyDao().surgeries(id),
            admissions = db.historyDao().admissions(id),
            reports = db.reportDao().reports(id),
            labs = db.reportDao().labs(id),
            imaging = db.reportDao().imaging(id),
            medications = meds,
            schedules = meds.associate { it.id to db.medicationDao().schedules(it.id) },
            medLogs = db.medicationDao().logsSince(id, 0),
            urine = db.monitoringDao().urineSince(id, 0),
            stool = db.monitoringDao().stoolSince(id, 0),
            growth = db.monitoringDao().growth(id),
            feeding = db.monitoringDao().feedingSince(id, 0),
            symptoms = db.monitoringDao().symptomsSince(id, 0),
            appointments = db.planDao().appointments(id),
            milestones = db.monitoringDao().milestones(id),
        )
    }

    /** Recomputes rule-based alerts. Alerts the parent dismissed stay dismissed. */
    suspend fun refreshAlerts() {
        val s = snapshot()
        val child = s.profile?.id ?: return
        val dismissed = db.planDao().dismissedKeys(child).toSet()
        db.planDao().clearActiveRuleAlerts(child)
        AlertEngine.evaluate(s).filter { it.sourceKey !in dismissed }.forEach { db.planDao().insertAlert(it) }
    }

    suspend fun rescheduleReminders() = ReminderScheduler.rescheduleAll(context)
}

class IshaanApp : Application() {
    @Volatile private var container: AppContainer? = null
    var startupError: Throwable? = null
        private set

    fun container(): AppContainer? {
        container?.let { return it }
        synchronized(this) {
            container?.let { return it }
            return try { AppContainer(this).also { container = it } } catch (t: Throwable) { startupError = t; null }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannels(this)
    }
}
