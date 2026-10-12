package com.example.rovan

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

// یه VPN محلی که فقط ترافیک اپ‌های «قطع‌شدنی» رو می‌گیره و دور می‌ریزه.
// بقیه‌ی اپ‌ها (از جمله اینستاگرام و اپ‌های سیستمی) کاملاً عادی و بدون VPN کار می‌کنن.
class NetFocusVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkgs = intent?.getStringArrayListExtra("pkgs") ?: arrayListOf()
        startVpn(pkgs)
        return START_NOT_STICKY
    }

    private fun startVpn(pkgs: List<String>) {
        stopVpn()
        if (pkgs.isEmpty()) return
        val b = Builder()
            .setSession("روان")
            .addAddress("10.77.0.2", 32)
            .addRoute("0.0.0.0", 0)
        try {
            b.addAddress("fd00:77::2", 128).addRoute("::", 0)
        } catch (e: Exception) {
        }
        for (p in pkgs) {
            try { b.addAllowedApplication(p) } catch (e: Exception) {}
        }
        val pfd: ParcelFileDescriptor = (try { b.establish() } catch (e: Exception) { null }) ?: return
        tun = pfd
        worker = Thread {
            val ins = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            try {
                while (ins.read(buf) >= 0) {
                    // بسته‌ها عمداً به جایی فرستاده نمی‌شن؛ یعنی این اپ‌ها اینترنت ندارن
                }
            } catch (e: Exception) {
            }
        }.also { it.start() }
    }

    private fun stopVpn() {
        try { tun?.close() } catch (e: Exception) {}
        tun = null
        worker?.interrupt()
        worker = null
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
