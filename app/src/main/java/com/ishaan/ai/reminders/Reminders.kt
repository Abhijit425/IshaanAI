package com.ishaan.ai.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ishaan.ai.IshaanApp
import com.ishaan.ai.MainActivity
import com.ishaan.ai.R
import com.ishaan.ai.data.db.MedicationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Local reminders via AlarmManager (no Firebase, no network). Medicine reminders show the medicine name
 * and the dose exactly as prescribed; the app never alters it.
 */
object ReminderScheduler {
    const val CH_MEDS = "meds"
    const val CH_APPT = "appointments"
    private const val MED_BASE = 100_000
    private const val APPT_BASE = 600_000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MEDS, "Medicine reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_APPT, "Appointment reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    suspend fun rescheduleAll(ctx: Context) {
        val c = (ctx.applicationContext as IshaanApp).container() ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (s in c.db.medicationDao().activeSchedules()) {
            val next = nextOccurrence(s.minuteOfDay)
            set(ctx, am, MED_BASE + s.id.toInt(), next, Intent(ctx, ReminderReceiver::class.java).putExtra("type", "MED").putExtra("scheduleId", s.id).putExtra("medId", s.medicationId).putExtra("at", next))
        }
        val now = System.currentTimeMillis()
        for (a in c.db.planDao().upcomingAppointments(now)) {
            val at = a.dateTime - a.remindMinutesBefore * 60_000L
            if (at > now) set(ctx, am, APPT_BASE + a.id.toInt(), at, Intent(ctx, ReminderReceiver::class.java).putExtra("type", "APPT").putExtra("apptId", a.id))
        }
    }

    fun cancelSchedule(ctx: Context, scheduleId: Long) = cancel(ctx, MED_BASE + scheduleId.toInt())
    fun cancelAppointment(ctx: Context, id: Long) = cancel(ctx, APPT_BASE + id.toInt())

    private fun cancel(ctx: Context, code: Int) {
        val pi = PendingIntent.getBroadcast(ctx, code, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) ctx.getSystemService(AlarmManager::class.java).cancel(pi)
    }

    fun nextOccurrence(minuteOfDay: Int, from: LocalDateTime = LocalDateTime.now()): Long {
        var t = LocalDate.from(from).atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (!t.isAfter(from)) t = t.plusDays(1)
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun set(ctx: Context, am: AlarmManager, code: Int, at: Long, intent: Intent) {
        val pi = PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun canScheduleExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { handle(ctx, intent) } finally { pending.finish() }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val c = (ctx.applicationContext as IshaanApp).container() ?: return
        val nm = NotificationManagerCompat.from(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        when (intent.getStringExtra("type")) {
            "MED" -> {
                val medId = intent.getLongExtra("medId", 0)
                val med = c.db.medicationDao().medication(medId) ?: return
                if (!med.active) return
                val notifId = (medId * 1000 + (intent.getLongExtra("scheduleId", 0) % 1000)).toInt()
                val given = PendingIntent.getBroadcast(
                    ctx, notifId, Intent(ctx, ReminderReceiver::class.java).putExtra("type", "GIVEN").putExtra("medId", medId)
                        .putExtra("at", intent.getLongExtra("at", System.currentTimeMillis())).putExtra("notifId", notifId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(ctx, ReminderScheduler.CH_MEDS)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle("Medicine time: ${med.name}")
                    .setContentText("${med.prescribedDose} ${med.doseUnit} — as prescribed".replace("  ", " "))
                    .setStyle(NotificationCompat.BigTextStyle().bigText("${med.name} ${med.strength}\nDose (as prescribed): ${med.prescribedDose} ${med.doseUnit}\n${med.specialInstructions}".trim()))
                    .setContentIntent(open).setAutoCancel(true)
                    .addAction(0, "Mark given", given)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                runCatching { nm.notify(notifId, n) }
                ReminderScheduler.rescheduleAll(ctx) // schedules tomorrow's occurrence
            }
            "GIVEN" -> {
                val medId = intent.getLongExtra("medId", 0)
                c.db.medicationDao().insertLog(MedicationLog(medicationId = medId, scheduledTime = intent.getLongExtra("at", 0), actionTime = System.currentTimeMillis(), status = "GIVEN", note = "Marked from notification"))
                nm.cancel(intent.getIntExtra("notifId", 0))
            }
            "APPT" -> {
                val a = c.db.planDao().appointment(intent.getLongExtra("apptId", 0)) ?: return
                val n = NotificationCompat.Builder(ctx, ReminderScheduler.CH_APPT)
                    .setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle("Appointment: ${a.specialty.ifBlank { "Doctor visit" }}")
                    .setContentText("${a.doctorName} · ${com.ishaan.ai.domain.Fmt.dateTime(a.dateTime)}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText("${a.doctorName} at ${a.hospital}\n${com.ishaan.ai.domain.Fmt.dateTime(a.dateTime)}\n${if (a.testsToDo.isNotBlank()) "Tests: ${a.testsToDo}" else ""}".trim()))
                    .setContentIntent(open).setAutoCancel(true).build()
                runCatching { nm.notify(APPT_NOTIF + a.id.toInt(), n) }
            }
        }
    }

    companion object { private const val APPT_NOTIF = 900_000 }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { ReminderScheduler.rescheduleAll(ctx) } finally { pending.finish() }
        }
    }
}
