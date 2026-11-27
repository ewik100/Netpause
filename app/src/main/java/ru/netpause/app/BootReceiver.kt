package ru.netpause.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Restore only the floating control after reboot.
 *
 * VPN blocking is intentionally NEVER restored automatically: the user must
 * open the floating panel and press "Start blocking" for each new session.
 * This avoids an unexpected network lock after a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("netpause_state", Context.MODE_PRIVATE)

        // A previous VPN session must never silently resume after reboot.
        prefs.edit().putLong("active_until", 0L).apply()

        if (prefs.getBoolean("overlay_enabled", false) && Settings.canDrawOverlays(context)) {
            try {
                val overlay = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(overlay)
                else context.startService(overlay)
            } catch (_: Exception) {
                // The user can launch the floating button manually from NetPause.
            }
        }
    }
}
