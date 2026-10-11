package com.example.rovan

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

data class AppRow(
    val label: String, val pkg: String, val size: Long, val lastUsed: Long,
    val net: Long, val mobile: Long, val fgMs: Long
)

class MainActivity : Activity() {
    private val PRIMARY = 0xFF00796B.toInt()
    private val BG = 0xFFF4F6F8.toInt()
    private val TEXT = 0xFF212121.toInt()
    private val MUTED = 0xFF757575.toInt()
    private val RED = 0xFFC62828.toInt()
    private val ORANGE = 0xFFEF6C00.toInt()
    private val GREEN = 0xFF2E7D32.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private var healthBox: LinearLayout? = null
    private var tab = 0
    private var apps: List<AppRow>? = null
    private var loading = false
    private var loadedAt = 0L

    private val tabs = listOf("🏠" to "خانه", "📦" to "اپ‌ها", "📶" to "اینترنت", "🎯" to "تمرکز")

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            refreshHealth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PRIMARY
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        rootLayout.addView(TextView(this).apply {
            text = "روان"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(PRIMARY)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            elevation = dp(4).toFloat()
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(6))
        }
        scroll = ScrollView(this).apply { addView(content) }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        rootLayout.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        buildNav()
        renderTab()
        ensureApps()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) {}
    }

    // ---------- ابزارهای ظاهر ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float = 14f, bold: Boolean = false, color: Int = TEXT) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun pill(s: String, primary: Boolean = true, color: Int = PRIMARY, onClick: () -> Unit) =
        TextView(this).apply {
            text = s
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else color)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (primary) setColor(color) else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), color)
                }
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }

    private fun buildNav() {
        nav.removeAllViews()
        tabs.forEachIndexed { i, p ->
            nav.addView(TextView(this).apply {
                text = "${p.first}\n${p.second}"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (i == tab) PRIMARY else MUTED)
                if (i == tab) setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = i; buildNav(); renderTab() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun renderTab() {
        content.removeAllViews()
        healthBox = null
        when (tab) {
            0 -> homeTab()
            1 -> appsTab()
            2 -> netTab()
            else -> focusTab()
        }
        scroll.scrollTo(0, 0)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "این گزینه روی گوشی تو پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmt(b: Long): String =
        if (b >= (1L shl 30)) String.format(Locale.US, "%.1f GB", b / 1073741824.0)
        else String.format(Locale.US, "%d MB", b / 1048576)

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun accessCard() = card().apply {
        addView(tv("یک دسترسی لازمه", 16f, true))
        addView(tv("برای دیدن حجم و مصرف اپ‌ها، دسترسی «Usage access» رو برای روان روشن کن. فقط یک‌بار لازمه.", 13f, false, MUTED))
        addView(pill("دادن دسترسی") { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
    }

    // ---------- تب خانه ----------
    private fun homeTab() {
        val hc = card()
        hc.addView(tv("سلامت گوشی", 16f, true))
        healthBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(healthBox)
        content.addView(hc)
        refreshHealth()

        val st = StatFs(Environment.getDataDirectory().path)
        val pct = (100 * st.availableBytes / st.totalBytes).toInt()
        val col = if (pct < 10) RED else if (pct < 20) ORANGE else PRIMARY
        val sc = card()
        sc.addView(tv("حافظه", 16f, true))
        sc.addView(tv("${fmt(st.availableBytes)} خالی", 26f, true, col))
        sc.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 100 - pct
            progressTintList = ColorStateList.valueOf(col)
        })
        sc.addView(tv("از ${fmt(st.totalBytes)} (${pct}٪ آزاد)", 13f, false, MUTED))
        if (pct < 20) sc.addView(pill("پیدا کردن چیزهای قابل حذف") { tab = 1; buildNav(); renderTab() })
        content.addView(sc)

        if (!hasUsageAccess()) content.addView(accessCard())

        val qc = card()
        qc.addView(tv("روان‌تر شدن سریع", 16f, true))
        qc.addView(tv("در گزینه‌های توسعه‌دهنده، سه مورد Window animation scale، Transition animation scale و Animator duration scale رو روی 0.5x بذار.", 13f, false, MUTED))
        qc.addView(pill("باز کردن گزینه‌های توسعه‌دهنده", false) {
            open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        content.addView(qc)
    }

    private fun healthWarnings(): List<String> {
        val out = ArrayList<String>()
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val plugged = bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0) level * 100 / scale else -1
            val t = String.format(Locale.US, "%.0f", temp)
            if (temp >= 45) out.add("🔥 دمای باتری ${t}° هست و خطرناکه! شارژر رو بکش، برنامه‌ها رو ببند و گوشی رو از کاور و آفتاب دور کن.")
            else if (temp >= 40) out.add("🌡️ دمای باتری ${t}° و بالاست. بازی و فیلم سنگین رو متوقف کن تا خنک بشه.")
            else if (plugged && temp >= 37) out.add("🔌 گوشی موقع شارژ داغ شده. شارژ همراه با استفاده‌ی سنگین عمر باتری رو کم می‌کنه.")
            if (pct in 0..14 && !plugged) out.add("🪫 باتری زیر ۱۵٪ هست. خالی‌شدن کامل مداوم، عمر باتری رو کم می‌کنه.")
        }
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        if (mi.lowMemory) out.add("🧠 رم گوشی تقریباً پره و باعث لگ می‌شه. برنامه‌های باز رو ببند.")
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
                out.add("🔥 سیستم داره برای جلوگیری از آسیب، سرعت رو کم می‌کنه (گرمای شدید).")
        }
        val st = StatFs(Environment.getDataDirectory().path)
        if (100 * st.availableBytes / st.totalBytes < 8)
            out.add("💾 حافظه تقریباً پره. گوشی کند و ناپایدار می‌شه.")
        try {
            if (Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1)
                out.add("🔓 USB debugging روشنه. اگه لازم نداری خاموشش کن، چون ریسک امنیتی داره.")
        } catch (e: Exception) {}
        return out
    }

    private fun refreshHealth() {
        val box = healthBox ?: return
        box.removeAllViews()
        val w = healthWarnings()
        if (w.isEmpty()) box.addView(tv("✅ همه‌چیز خوبه و هشداری نیست.", 14f, false, GREEN))
        else w.forEach { box.addView(tv(it, 14f, false, if (it.startsWith("🔥")) RED else ORANGE)) }
    }

    // ---------- تب اپ‌ها ----------
    private fun appsTab() {
        if (!hasUsageAccess()) { content.addView(accessCard()); return }
        val list = apps
        if (list == null) {
            content.addView(card().apply { addView(tv("در حال بررسی اپ‌ها...", 14f, false, MUTED)) })
            return
        }
        content.addView(card().apply {
            addView(tv("اپ‌های سنگین و کم‌استفاده", 16f, true))
            addView(tv("هیچ‌چیز خودکار پاک نمی‌شه؛ حذف همیشه با تأیید خودته.", 13f, false, MUTED))
            addView(pill(if (loading) "در حال بروزرسانی..." else "بروزرسانی", false) { ensureApps(true) })
        })
        list.sortedByDescending { it.size }.take(30).forEach { content.addView(appCard(it)) }
    }

    private fun appCard(r: AppRow): View {
        val days = if (r.lastUsed <= 0L) -1L else (System.currentTimeMillis() - r.lastUsed) / 86400000L
        val stale = days < 0 || days >= 30
        val usedText = when {
            days < 0 -> "تو ۹۰ روز اخیر استفاده نشده"
            days >= 30 -> "$days روز پیش استفاده شده"
            days == 0L -> "امروز استفاده شده"
            else -> "$days روز پیش استفاده شده"
        }
        val c = card()
        c.addView(tv(r.label, 16f, true))
        c.addView(tv("حجم: ${fmt(r.size)}", 13f, false, MUTED))
        c.addView(tv(usedText, 13f, false, if (stale) ORANGE else MUTED))
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("حذف", false, RED) {
                loadedAt = 0L
                open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${r.pkg}")))
            })
            addView(pill("پاک کردن کش", false) {
                loadedAt = 0L
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
            })
        })
        return c
    }

    // ---------- تب اینترنت ----------
    private fun netTab() {
        if (!hasUsageAccess()) { content.addView(accessCard()); return }
        val list = apps
        if (list == null) {
            content.addView(card().apply { addView(tv("در حال بررسی...", 14f, false, MUTED)) })
            return
        }
        content.addView(card().apply {
            addView(tv("مصرف اینترنت و باتری (۳۰ روز اخیر)", 16f, true))
            addView(tv("اندروید اجازه نمی‌ده اپ‌ها مصرف باتری هر اپ رو بخونن؛ برای همین از «زمان استفاده» و «مصرف پس‌زمینه» تخمین می‌زنیم.", 12f, false, MUTED))
            addView(pill("مصرف باتری سیستم", false) { open(Intent("android.intent.action.POWER_USAGE_SUMMARY")) })
            addView(pill("مصرف دیتا", false) { open(Intent("android.settings.DATA_USAGE_SETTINGS")) })
        })
        val heavy = list.filter { it.net > 0 }.sortedByDescending { it.net }.take(10)
        if (heavy.isEmpty()) content.addView(card().apply { addView(tv("آماری از مصرف اینترنت پیدا نشد.", 14f, false, MUTED)) })
        heavy.forEach { content.addView(netCard(it)) }
    }

    private fun netCard(r: AppRow): View {
        val min = r.fgMs / 60000L
        val fg = if (min >= 60) "${min / 60} ساعت" else "$min دقیقه"
        val bgHeavy = r.net > 300L * 1048576 && min < 30
        val c = card()
        c.addView(tv(r.label, 16f, true))
        c.addView(tv("📶 موبایل ${fmt(r.mobile)}  •  کل ${fmt(r.net)}", 13f, false, MUTED))
        c.addView(tv("⏱ استفاده‌ی ۷ روز اخیر: $fg", 13f, false, MUTED))
        if (bgHeavy) c.addView(tv("مصرف اینترنت زیاد ولی استفاده‌ی کم؛ احتمالاً تو پس‌زمینه اینترنت و باتری می‌خوره. از بخش «Mobile data & Wi-Fi» و «Battery» محدودش کن.", 13f, false, ORANGE))
        c.addView(pill("محدود کردن", false) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.pkg}")))
        })
        return c
    }

    // ---------- تب تمرکز ----------
    private fun focusTab() {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        val on = prefs.getBoolean("focus_on", false)
        val nBoost = (prefs.getStringSet("boost", emptySet()) ?: emptySet()).size
        val nProt = (prefs.getStringSet("protect", emptySet()) ?: emptySet()).size
        val c = card()
        c.addView(tv("حالت تمرکز", 16f, true))
        c.addView(tv(if (on) "🟢 روشن" else "⚪ خاموش", 14f, true, if (on) GREEN else MUTED))
        c.addView(tv("وقتی یکی از اپ‌های انتخابی تو (مثلاً بازی) باز بشه، اپ‌های معمولی پس‌زمینه بسته می‌شن تا رم و پردازنده آزاد بشه. اپ‌های سیستمی، کیبورد، لانچر، تماس و پیامک و اپ‌های محافظت‌شده هیچ‌وقت بسته نمی‌شن.", 13f, false, MUTED))
        c.addView(pill(if (on) "خاموش کردن" else "روشن کردن", !on, if (on) RED else PRIMARY) { toggleFocus(on) })
        content.addView(c)

        content.addView(card().apply {
            addView(tv("کمک‌های اضافه حین بازی", 15f, true))
            addView(tv("فقط وقتی اپ بوست جلوی صفحه‌ست اعمال می‌شن و بعدش خودکار به حالت قبل برمی‌گردن.", 13f, false, MUTED))
            addView(optionRow("مزاحم نشو (بدون نوتیفیکیشن وسط بازی)", "dnd"))
            addView(optionRow("توقف همگام‌سازی خودکار حساب‌ها", "sync"))
        })

        content.addView(card().apply {
            addView(tv("اپ‌های بوست: $nBoost", 15f, true))
            addView(tv("با باز شدنشون، حالت تمرکز کار می‌کنه.", 13f, false, MUTED))
            addView(pill("انتخاب", false) { pickApps("اپ‌های بوست", "boost") })
        })
        content.addView(card().apply {
            addView(tv("اپ‌های محافظت‌شده: $nProt", 15f, true))
            addView(tv("مثلاً پیام‌رسان، آلارم و موزیک؛ هیچ‌وقت بسته نمی‌شن.", 13f, false, MUTED))
            addView(pill("انتخاب", false) { pickApps("اپ‌های محافظت‌شده", "protect") })
        })
    }

    private fun optionRow(label: String, key: String): View {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        val on = prefs.getBoolean(key, false)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 14f), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(pill(if (on) "روشن" else "خاموش", on) {
                val nm = getSystemService(NotificationManager::class.java)
                if (!on && key == "dnd" && !nm.isNotificationPolicyAccessGranted) {
                    Toast.makeText(this@MainActivity, "اجازه‌ی «Do Not Disturb» رو به روان بده و دوباره روشنش کن", Toast.LENGTH_LONG).show()
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else {
                    prefs.edit().putBoolean(key, !on).apply()
                    renderTab()
                }
            })
        }
    }

    private fun userApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
            .mapNotNull {
                try {
                    val ai = packageManager.getApplicationInfo(it, 0)
                    if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) null
                    else Pair(packageManager.getApplicationLabel(ai).toString(), it)
                } catch (e: Exception) { null }
            }.sortedBy { it.first }
    }

    private fun pickApps(titleText: String, key: String) {
        val list = userApps()
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        val sel = HashSet<String>(prefs.getStringSet(key, emptySet()) ?: emptySet())
        val checked = BooleanArray(list.size) { list[it].second in sel }
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMultiChoiceItems(list.map { it.first }.toTypedArray(), checked) { _, i, c ->
                if (c) sel.add(list[i].second) else sel.remove(list[i].second)
            }
            .setPositiveButton("ذخیره") { _, _ ->
                prefs.edit().putStringSet(key, sel).apply()
                renderTab()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun toggleFocus(on: Boolean) {
        val prefs = getSharedPreferences("rovan", MODE_PRIVATE)
        if (on) {
            stopService(Intent(this, FocusService::class.java))
            prefs.edit().putBoolean("focus_on", false).apply()
        } else if (!hasUsageAccess()) {
            Toast.makeText(this, "اول دسترسی Usage access رو بده", Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        } else if ((prefs.getStringSet("boost", emptySet()) ?: emptySet()).isEmpty()) {
            Toast.makeText(this, "اول اپ‌های بوست رو انتخاب کن", Toast.LENGTH_LONG).show()
            return
        } else {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this, FocusService::class.java))
            prefs.edit().putBoolean("focus_on", true).apply()
        }
        renderTab()
    }

    // ---------- داده‌ها ----------
    private fun ensureApps(force: Boolean = false) {
        if (loading || !hasUsageAccess()) return
        if (!force && apps != null && System.currentTimeMillis() - loadedAt < 10 * 60000L) return
        loading = true
        Thread {
            val list = try { collectApps() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                apps = list
                loadedAt = System.currentTimeMillis()
                loading = false
                if (!isFinishing && (tab == 1 || tab == 2)) renderTab()
            }
        }.start()
    }

    private fun netUsage(): Pair<Map<Int, Long>, Map<Int, Long>> {
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - 30L * 86400000L
        val total = HashMap<Int, Long>()
        val mobile = HashMap<Int, Long>()
        for (t in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                val s = nsm.querySummary(t, null, start, end)
                val b = NetworkStats.Bucket()
                while (s.hasNextBucket()) {
                    s.getNextBucket(b)
                    val bytes = b.rxBytes + b.txBytes
                    total[b.uid] = (total[b.uid] ?: 0L) + bytes
                    if (t == ConnectivityManager.TYPE_MOBILE) mobile[b.uid] = (mobile[b.uid] ?: 0L) + bytes
                }
                s.close()
            } catch (e: Exception) {}
        }
        return Pair(total, mobile)
    }

    private fun collectApps(): List<AppRow> {
        val pm = packageManager
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val now = System.currentTimeMillis()
        val usage = usm.queryAndAggregateUsageStats(now - 90L * 86400000L, now)
        val week = usm.queryAndAggregateUsageStats(now - 7L * 86400000L, now)
        val (netTotal, netMobile) = netUsage()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
        val out = ArrayList<AppRow>()
        for (p in pkgs) {
            if (p == packageName) continue
            try {
                val ai = pm.getApplicationInfo(p, 0)
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, p, Process.myUserHandle())
                out.add(
                    AppRow(
                        pm.getApplicationLabel(ai).toString(), p,
                        s.appBytes + s.dataBytes + s.cacheBytes,
                        usage[p]?.lastTimeUsed ?: 0L,
                        netTotal[ai.uid] ?: 0L, netMobile[ai.uid] ?: 0L,
                        week[p]?.totalTimeInForeground ?: 0L
                    )
                )
            } catch (e: Exception) {}
        }
        return out
    }
}
