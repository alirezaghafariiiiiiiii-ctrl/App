package com.example.rovan

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OnboardingActivity : Activity() {
    private val PRIMARY = 0xFF00796B.toInt()
    private val BG = 0xFFF4F6F8.toInt()
    private val TEXT = 0xFF212121.toInt()
    private val MUTED = 0xFF757575.toInt()
    private val GREEN = 0xFF2E7D32.toInt()
    private val ORANGE = 0xFFEF6C00.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("rovan", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (prefs.getBoolean("setup_done", false)) {
            goMain()
            return
        }
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
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        rootLayout.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------- ابزارها ----------
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

    private fun pill(s: String, primary: Boolean = true, onClick: () -> Unit) =
        TextView(this).apply {
            text = s
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else PRIMARY)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (primary) setColor(PRIMARY) else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), PRIMARY)
                }
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "این گزینه روی گوشی تو پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appDetails() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    // ---------- وضعیت‌ها ----------
    private fun hasUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasDnd(): Boolean =
        getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun hasBatteryExemption(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun brandTip(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m ->
                "امنیت (Security) ← مجوزها ← شروع خودکار (Autostart): روان رو روشن کن. بعد تو تنظیمات باتریِ روان، «بدون محدودیت» (No restrictions) رو انتخاب کن."
            "samsung" in m ->
                "تنظیمات ← باتری ← محدودیت‌های مصرف در پس‌زمینه ← اپ‌های هرگز خواب‌نرو (Never sleeping apps): روان رو اضافه کن."
            "huawei" in m || "honor" in m ->
                "تنظیمات ← باتری ← راه‌اندازی برنامه (App launch) ← روان ← مدیریت دستی: هر سه گزینه رو روشن کن."
            "oppo" in m || "realme" in m || "oneplus" in m ->
                "تنظیمات ← باتری ← روان: اجازه‌ی فعالیت در پس‌زمینه و اجرای خودکار رو روشن کن."
            "vivo" in m || "iqoo" in m ->
                "تنظیمات ← باتری ← مصرف باتری در پس‌زمینه ← روان ← اجازه بده."
            else ->
                "تنظیمات ← اپ‌ها ← روان ← باتری: «بدون محدودیت» (Unrestricted) رو انتخاب کن."
        }
    }

    private fun openAutostart() {
        val comps = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        for (c in comps) {
            try {
                startActivity(Intent().setComponent(c))
                return
            } catch (e: Exception) {
            }
        }
        open(appDetails())
    }

    // ---------- صفحه ----------
    private fun step(title: String, desc: String, done: Boolean, vararg buttons: View) {
        val c = card()
        c.addView(tv((if (done) "✅ " else "⬜ ") + title, 16f, true, if (done) GREEN else TEXT))
        c.addView(tv(desc, 13f, false, MUTED))
        if (!done) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.forEach { row.addView(it) }
            c.addView(row)
        }
        content.addView(c)
    }

    private fun render() {
        content.removeAllViews()
        content.addView(tv("چند تنظیم سریع", 22f, true))
        content.addView(tv("با این چند قدم، روان بدون قطعی و درست کار می‌کنه. هر وقت خواستی می‌تونی ردش کنی.", 13f, false, MUTED))
        content.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(10)))

        step(
            "۱. دسترسی Usage access (ضروری)",
            "برای دیدن حجم اپ‌ها، مصرف اینترنت و تشخیص اپی که جلوی صفحه‌ست. روان به محتوای پیام‌ها و فایل‌هات دسترسی نداره.",
            hasUsage(),
            pill("دادن دسترسی") { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        )
        step(
            "۲. نوتیفیکیشن",
            "حالت تمرکز یه نوتیفیکیشن کوچیک و بی‌صدا نشون می‌ده تا اندروید سرویس رو نکشه.",
            hasNotif(),
            pill("اجازه دادن") {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            },
            pill("تنظیمات نوتیفیکیشن", false) {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        )
        step(
            "۳. مزاحم نشو (اختیاری)",
            "برای اینکه وقت بازی نوتیفیکیشن‌ها مزاحم نشن. بعد بازی خودش به حالت قبل برمی‌گرده.",
            hasDnd(),
            pill("دادن اجازه") { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        )
        step(
            "۴. معافیت از بهینه‌سازی باتری",
            "بدون این، بعضی گوشی‌ها سرویس تمرکز رو بعد از چند دقیقه می‌بندن.",
            hasBatteryExemption(),
            pill("معاف کردن") {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        )
        step(
            "۵. اجرای خودکار و پس‌زمینه (${Build.MANUFACTURER})",
            brandTip() + "\nاندروید وضعیتش رو به برنامه نمی‌گه؛ بعد از انجام، «انجام دادم» رو بزن.",
            prefs.getBoolean("autostart_done", false),
            pill("باز کردن تنظیمات") { openAutostart() },
            pill("انجام دادم", false) {
                prefs.edit().putBoolean("autostart_done", true).apply()
                render()
            }
        )

        if (!hasUsage()) content.addView(
            tv("بدون دسترسی Usage access، بخش اپ‌ها، اینترنت و حالت تمرکز کار نمی‌کنن.", 13f, false, ORANGE)
        )
        content.addView(pill("ورود به روان") {
            prefs.edit().putBoolean("setup_done", true).apply()
            goMain()
        })
    }
}
