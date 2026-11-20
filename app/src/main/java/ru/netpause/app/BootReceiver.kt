package ru.netpause.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("netpause_state", Context.MODE_PRIVATE)
        val until = prefs.getLong("active_until", 0L)
        if (until > System.currentTimeMillis() && VpnService.prepare(context) == null) {
            val remaining = until - System.currentTimeMillis()
            val vpn = Intent(context, BlockVpnService::class.java)
                .putExtra(BlockVpnService.EXTRA_DURATION_MS, remaining)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(vpn)
                else context.startService(vpn)
            } catch (_: Exception) {
                // Android or the device manufacturer may restrict background starts.
            }
        }

        if (prefs.getBoolean("overlay_enabled", false) && Settings.canDrawOverlays(context)) {
            try {
                val overlay = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(overlay)
                else context.startService(overlay)
            } catch (_: Exception) {
                // The user can relaunch the floating button from the app.
            }
        }
    }
}
