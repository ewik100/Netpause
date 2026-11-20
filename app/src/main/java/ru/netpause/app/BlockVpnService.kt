package ru.netpause.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class BlockVpnService : VpnService() {
    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_RECONFIGURE = "reconfigure"
        const val ACTION_STOP = "ru.netpause.app.action.STOP_VPN"
        const val ACTION_REFRESH_SYSTEM_MODES = "ru.netpause.app.action.REFRESH_SYSTEM_VPN_MODES"
        private const val CHANNEL_ID = "netpause_block"
        private const val NOTIFICATION_ID = 71
        private const val MAX_DURATION_MS = 120L * 60_000L

        /**
         * Ask the running VPN service to tear down its interface before it
         * exits. This is more deterministic than relying only on stopService().
         */
        fun requestStop(context: Context) {
            val stopIntent = Intent(context, BlockVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(stopIntent)
            } catch (_: Exception) {
                // Last-resort fallback; onDestroy() also closes the descriptor.
                context.stopService(stopIntent)
            }
        }

        fun refreshSystemModes(context: Context) {
            try {
                context.startService(
                    Intent(context, BlockVpnService::class.java).setAction(ACTION_REFRESH_SYSTEM_MODES)
                )
            } catch (_: Exception) {
                // Diagnostics are optional; disconnect controls remain available.
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var readerStopSignal: AtomicBoolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }
    private val stopRunnable = Runnable {
        clearAndDisconnect()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        saveSystemModeState()
    }

    private fun saveSystemModeState() {
        if (Build.VERSION.SDK_INT >= 29) {
            // Record system-managed VPN modes so the app can explain why
            // Android may restart a VPN independently of the in-app timer.
            prefs.edit()
                .putBoolean("system_vpn_always_on", isAlwaysOn())
                .putBoolean("system_vpn_lockdown", isLockdownEnabled())
                .apply()
        } else {
            prefs.edit()
                .putBoolean("system_vpn_always_on", false)
                .putBoolean("system_vpn_lockdown", false)
                .apply()
        }
    }

    private fun clearAndDisconnect() {
        handler.removeCallbacks(stopRunnable)
        prefs.edit().putLong("active_until", 0L).apply()
        closeTunnel()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent can be delivered when Android tries to restore a
        // previous service. Never create a blocking tunnel from a system restart:
        // every blocking session must carry the user's explicit duration.
        if (intent == null) {
            clearAndDisconnect()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Refresh diagnostics without changing an active tunnel.
        if (intent.action == ACTION_REFRESH_SYSTEM_MODES) {
            saveSystemModeState()
            if (vpnInterface == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Only app actions carrying an explicit duration may create a tunnel.
        // This prevents an accidental default-length block on unexpected starts.
        if (intent.action != ACTION_STOP &&
            !intent.hasExtra(EXTRA_DURATION_MS) &&
            !intent.getBooleanExtra(EXTRA_RECONFIGURE, false)
        ) {
            clearAndDisconnect()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Handle the stop command before entering foreground mode. Closing the
        // descriptor releases Android's VPN interface immediately; removing
        // foreground state also removes the persistent VPN notification.
        if (intent?.action == ACTION_STOP) {
            // First release Android's VPN interface, then remove foreground UI,
            // then stop the service. The separate overlay service is untouched.
            clearAndDisconnect()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val duration = (intent?.getLongExtra(EXTRA_DURATION_MS, 30 * 60_000L)
            ?: 30 * 60_000L).coerceIn(1_000L, MAX_DURATION_MS)
        val reconfigure = intent?.getBooleanExtra(EXTRA_RECONFIGURE, false) == true

        // Establish the VPN interface first. On Android 14+, VPN foreground
        // services may use the systemExempted type only while the VPN is active.
        if (vpnInterface == null || reconfigure) {
            if (reconfigure) closeTunnel()
            if (!establishTunnel()) {
                prefs.edit().putLong("active_until", 0L).apply()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // Fail open: never leave a tunnel running if foreground promotion
            // is rejected by the OS or device policy.
            prefs.edit().putLong("active_until", 0L).apply()
            closeTunnel()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        prefs.edit().putLong("active_until", System.currentTimeMillis() + duration).apply()
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, duration)
        return START_NOT_STICKY
    }

    private fun establishTunnel(): Boolean {
        val builder = Builder()
            .setSession("NetPause — блокировка интернета")
            .setMtu(1500)
            // Blocking mode is important: a non-blocking read can return EAGAIN
            // when the VPN queue is momentarily empty.
            .setBlocking(true)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd77:7777::1", 128)
            .addRoute("::", 0)

        val allowedApps = prefs.getStringSet("allowed_apps", emptySet())?.toSet() ?: emptySet()

        // Option B: selected apps bypass the VPN. All remaining apps are routed
        // into this VPN; their packets are intentionally not forwarded.
        val exclusions = allowedApps + packageName
        for (appPackage in exclusions) {
            try {
                builder.addDisallowedApplication(appPackage)
            } catch (_: Exception) {
                // Ignore a package if it has been removed or is not installed.
            }
        }

        return try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                false
            } else {
                startDiscardingPackets()
                true
            }
        } catch (_: Exception) {
            vpnInterface = null
            false
        }
    }

    private fun startDiscardingPackets() {
        val descriptor = vpnInterface ?: return
        val stopSignal = AtomicBoolean(false)
        readerStopSignal = stopSignal

        readerThread = Thread {
            val buffer = ByteArray(32767)
            try {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    while (!stopSignal.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        // Do not write the packets back: they remain blocked.
                    }
                }
            } catch (_: Exception) {
                // Closing the descriptor on stop/reconfiguration is expected.
            }
        }.apply {
            name = "NetPause-PacketDrop"
            isDaemon = true
            start()
        }
    }

    private fun closeTunnel() {
        readerStopSignal?.set(true)
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        try { readerThread?.join(250) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readerThread = null
        readerStopSignal = null
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("NetPause — блокировка активна")
            .setContentText("Выбранные приложения остаются в сети. Нажми «Остановить», чтобы вернуть интернет.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Блокировка интернета",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onRevoke() {
        clearAndDisconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        clearAndDisconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
