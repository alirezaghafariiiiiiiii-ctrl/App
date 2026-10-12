package com.example.rovan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Locale

class HealthJobService : JobService() {
    companion object {
        private const val JOB_ID = 42

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val name = ComponentName(ctx, HealthJobService::class.java)
            try {
                js.schedule(JobInfo.Builder(JOB_ID, name).setPeriodic(30 * 60 * 1000L).setPersisted(true).build())
            } catch (e: Exception) {
                try {
                    js.schedule(JobInfo.Builder(JOB_ID, name).setPeriodic(30 * 60 * 1000L).build())
                } catch (e2: Exception) {}
            }
        }

        fun cancel(ctx: Context) {
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        try { runCheck() } catch (e: Exception) {}
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private fun runCheck() {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        if (!prefs.getBoolean("alerts", false)) return
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val st = StatFs(Environment.getDataDirectory().path)
        val freePct = (100 * st.availableBytes / st.totalBytes).toInt()
        if (temp >= 42) {
            val t = String.format(Locale.US, "%.0f", temp)
            post("heat", "🔥 دمای باتری بالاست", "دمای باتری ${t}° هست. گوشی رو از شارژ جدا کن و بذار خنک بشه.")
        }
        if (freePct < 8) {
            post("storage", "💾 حافظه تقریباً پره", "فقط ${freePct}٪ فضا مونده و گوشی کند می‌شه. چیزهای بی‌مصرف رو حذف کن.")
        }
    }

    // هر نوع هشدار حداکثر هر ۶ ساعت یه‌بار
    private fun post(key: String, title: String, text: String) {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("alert_$key", 0L) < 6L * 3600000L) return
        prefs.edit().putLong("alert_$key", now).apply()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("alerts", "هشدارهای سلامت گوشی", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            key.hashCode(),
            Notification.Builder(this, "alerts")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
