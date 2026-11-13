package com.ishaan.ai.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ishaan.ai.data.db.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room schema, relationships and cascade rules (JVM via Robolectric; production adds SQLCipher on top). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test fun crudAndOrdering() = runTest {
        val child = db.profileDao().upsert(ChildProfile(name = "Ishaan", dateOfBirthEpochDay = 19000))
        db.reportDao().upsertLab(ExtractedLabResult(childId = child, testCode = "CREATININE", displayName = "", valueText = "0.5", valueNumeric = 0.5, sampleDate = 2000))
        db.reportDao().upsertLab(ExtractedLabResult(childId = child, testCode = "CREATININE", displayName = "", valueText = "0.4", valueNumeric = 0.4, sampleDate = 1000))
        val series = db.reportDao().observeLabSeries(child, "CREATININE").first()
        assertEquals(listOf(0.4, 0.5), series.map { it.valueNumeric })
    }

    @Test fun reportDeletionKeepsValuesButUnlinks() = runTest {
        val child = db.profileDao().upsert(ChildProfile(name = "I", dateOfBirthEpochDay = null))
        val rep = db.reportDao().insertReport(MedicalReport(childId = child, title = "KFT", category = "KIDNEY_FUNCTION", fileName = "x.bin", originalName = "x.pdf", mimeType = "application/pdf"))
        db.reportDao().upsertLab(ExtractedLabResult(childId = child, reportId = rep, testCode = "CREATININE", displayName = "", valueText = "0.5", sampleDate = 1, originalOcrLine = "Creatinine 0.5", originalValueText = "0.5"))
        db.reportDao().deleteReport(db.reportDao().report(rep)!!)
        val labs = db.reportDao().labs(child)
        assertEquals(1, labs.size); assertNull(labs[0].reportId); assertEquals("Creatinine 0.5", labs[0].originalOcrLine)
    }

    @Test fun deletingSampleChildCascades() = runTest {
        val child = db.profileDao().upsert(ChildProfile(name = "Sample", dateOfBirthEpochDay = null, isSample = true))
        val med = db.medicationDao().upsert(Medication(childId = child, name = "Demo", prescribedDose = "1"))
        db.medicationDao().insertSchedule(MedicationSchedule(medicationId = med, minuteOfDay = 600))
        db.monitoringDao().upsertUrine(UrineLog(childId = child, dateTime = 1))
        db.profileDao().deleteSampleProfiles()
        assertEquals(0, db.profileDao().count())
        assertTrue(db.medicationDao().activeSchedules().isEmpty())
        assertTrue(db.monitoringDao().urineSince(child, 0).isEmpty())
    }

    @Test fun alertsDeduplicateAndRespectDismissal() = runTest {
        val a = HealthAlert(childId = 1, sourceKey = "rule:x", severity = "INFO", title = "t", message = "m")
        db.planDao().insertAlert(a); db.planDao().insertAlert(a)
        val active = db.planDao().observeActiveAlerts(1).first()
        assertEquals(1, active.size)
        db.planDao().dismissAlert(active[0].id)
        assertEquals(listOf("rule:x"), db.planDao().dismissedKeys(1))
        assertTrue(db.planDao().observeActiveAlerts(1).first().isEmpty())
    }

    @Test fun sampleDataLoadsOnlyIntoEmptyAppAndRemovesCleanly() = runTest {
        val s = SampleData(db)
        assertTrue(s.canLoad()); s.load()
        assertFalse(s.canLoad())
        assertTrue(db.reportDao().labs(db.profileDao().getProfile()!!.id).all { it.isSample })
        s.remove()
        assertEquals(0, db.profileDao().count())
    }

    @Test fun backupTableListCoversEveryTable() {
        val c = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')")
        val names = mutableSetOf<String>(); while (c.moveToNext()) names += c.getString(0); c.close()
        assertEquals(names, AppDatabase.TABLES_IN_ORDER.toSet())
    }
}
