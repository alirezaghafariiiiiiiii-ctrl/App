package com.example.rovan

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

class FocusService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var boosting = false
    private var lastKillAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (e: Exception) {}
            handler.postDelayed(this, 4000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("focus", "حالت تمرکز", NotificationManager.IMPORTANCE_MIN)
        )
        val n = notif("حالت تمرکز فعاله")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        restoreSettings(getSharedPreferences("rovan", MODE_PRIVATE)) // اگه دفعه‌ی قبل نیمه‌کاره موند
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopNetFocus()
        restoreSettings(getSharedPreferences("rovan", MODE_PRIVATE))
        super.onDestroy()
    }

    private fun notif(text: String): Notification =
        Notification.Builder(this, "focus")
            .setContentTitle("روان")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

    private fun check() {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        if (!(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            if (boosting) endBoost(prefs)
            return
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // اولین بار (یا بعد از ریستارت) تا ۲ ساعت قبل رو نگاه می‌کنیم تا اپ جلوی صفحه رو پیدا کنیم
        val from = if (current == null) now - 2 * 3600000L else now - 10000
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) current = e.packageName
        }
        val cur = current ?: return
        val boost = prefs.getStringSet("boost", emptySet()) ?: emptySet()
        if (cur !in boost) {
            if (boosting) endBoost(prefs)
            return
        }
        if (!boosting) startBoost(cur, prefs)
        else if (now - lastKillAt > 90000) {
            lastKillAt = now
            killOthers(cur, prefs)
        }
    }

    private fun startBoost(cur: String, prefs: SharedPreferences) {
        boosting = true
        lastKillAt = System.currentTimeMillis()
        val ramBefore = freeRam()
        killOthers(cur, prefs)
        if (prefs.getBoolean("netfocus", false)) startNetFocus(cur, prefs)
        // بعضی اپ‌ها خودشون دوباره راه می‌افتن؛ یه دور دوم بعد از چند ثانیه
        handler.postDelayed({ if (boosting && current == cur) killOthers(cur, prefs) }, 8000)
        // اندازه‌گیری رم خالی بعد از دور دوم
        handler.postDelayed({
            prefs.edit()
                .putLong("rb", ramBefore)
                .putLong("ra", freeRam())
                .putLong("rt", System.currentTimeMillis())
                .apply()
        }, 10000)

        if (prefs.getBoolean("dnd", false)) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted &&
                    nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                ) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    prefs.edit().putBoolean("r_dnd", true).apply()
                }
            } catch (e: Exception) {}
        }
        if (prefs.getBoolean("sync", false)) {
            try {
                if (ContentResolver.getMasterSyncAutomatically()) {
                    ContentResolver.setMasterSyncAutomatically(false)
                    prefs.edit().putBoolean("r_sync", true).apply()
                }
            } catch (e: Exception) {}
        }
    }

    private fun freeRam(): Long {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem
    }

    private fun endBoost(prefs: SharedPreferences) {
        boosting = false
        stopNetFocus()
        restoreSettings(prefs)
    }

    // هر چیزی که تغییر دادیم رو به حالت قبل برمی‌گردونه
    private fun restoreSettings(prefs: SharedPreferences) {
        if (prefs.getBoolean("r_dnd", false)) {
            try {
                getSystemService(NotificationManager::class.java)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (e: Exception) {}
            prefs.edit().putBoolean("r_dnd", false).apply()
        }
        if (prefs.getBoolean("r_sync", false)) {
            try { ContentResolver.setMasterSyncAutomatically(true) } catch (e: Exception) {}
            prefs.edit().putBoolean("r_sync", false).apply()
        }
    }

    // اپ‌های معمولی (غیرسیستمی) که نه محافظت‌شده‌ان و نه اپ جلوی صفحه
    private fun candidates(cur: String, prefs: SharedPreferences): List<String> {
        val pm = packageManager
        val skip = HashSet<String>(prefs.getStringSet("protect", emptySet()) ?: emptySet())
        skip.add(cur)
        skip.add(packageName)
        try {
            pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName?.let { skip.add(it) }
        } catch (e: Exception) {}
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.forEach { skip.add(it.packageName) }
        } catch (e: Exception) {}
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { skip.add(it) }
        } catch (e: Exception) {}
        try {
            Telephony.Sms.getDefaultSmsPackage(this)?.let { skip.add(it) }
        } catch (e: Exception) {}

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<String>()
        for (p in pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()) {
            if (p in skip) continue
            val ai = try { pm.getApplicationInfo(p, 0) } catch (e: Exception) { continue }
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            out.add(p)
        }
        return out
    }

    private fun killOthers(cur: String, prefs: SharedPreferences): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = candidates(cur, prefs)
        for (p in list) am.killBackgroundProcesses(p)
        return list.size
    }

    // قطع اینترنت بقیه‌ی اپ‌ها با یه VPN محلی؛ اگه VPN دیگه‌ای روشن باشه کاری نمی‌کنه تا اون قطع نشه
    private fun startNetFocus(cur: String, prefs: SharedPreferences) {
        try {
            if (VpnService.prepare(this) != null) return
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val blocked = ArrayList(candidates(cur, prefs))
            startService(Intent(this, NetFocusVpnService::class.java).putStringArrayListExtra("pkgs", blocked))
        } catch (e: Exception) {}
    }

    private fun stopNetFocus() {
        try { stopService(Intent(this, NetFocusVpnService::class.java)) } catch (e: Exception) {}
    }
}
