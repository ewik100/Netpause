package ru.netpause.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        private const val CHANNEL_ID = "netpause_block"
        private const val NOTIFICATION_ID = 71
        private const val MAX_DURATION_MS = 120L * 60_000L
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var readerStopSignal: AtomicBoolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        getSharedPreferences("netpause_state", MODE_PRIVATE)
            .edit().putLong("active_until", 0L).apply()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val duration = (intent?.getLongExtra(EXTRA_DURATION_MS, 30 * 60_000L)
            ?: 30 * 60_000L).coerceIn(1_000L, MAX_DURATION_MS)
        val reconfigure = intent?.getBooleanExtra(EXTRA_RECONFIGURE, false) == true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (vpnInterface == null || reconfigure) {
            if (reconfigure) closeTunnel()
            if (!establishTunnel()) {
                getSharedPreferences("netpause_state", MODE_PRIVATE)
                    .edit().putLong("active_until", 0L).apply()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val activeUntil = System.currentTimeMillis() + duration
        getSharedPreferences("netpause_state", MODE_PRIVATE)
            .edit().putLong("active_until", activeUntil).apply()

        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, duration)
        return START_NOT_STICKY
    }

    private fun establishTunnel(): Boolean {
        val builder = Builder()
            .setSession("NetPause — блокировка интернета")
            .setMtu(1500)
            // FileInputStream reads this descriptor on a worker thread.
            // Android defaults to non-blocking; blocking mode prevents EAGAIN
            // from terminating the reader when no packet is ready.
            .setBlocking(true)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd77:7777::1", 128)
            .addRoute("::", 0)

        val allowedApps = getSharedPreferences("netpause_state", MODE_PRIVATE)
            .getStringSet("allowed_apps", emptySet())?.toSet() ?: emptySet()

        // Option B: these apps bypass the VPN and keep their normal internet.
        // All other apps are routed into this VPN and their packets are discarded.
        for (packageName in allowedApps) {
            if (packageName == this.packageName) continue
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
                // Ignore removed apps or packages no longer installed.
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
                        // Deliberately do not write packets back to the network.
                    }
                }
            } catch (_: Exception) {
                // Closing the VPN descriptor during stop/reconfiguration is expected.
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
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("NetPause")
                .setContentText("Интернет заблокирован по таймеру")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("NetPause")
                .setContentText("Интернет заблокирован по таймеру")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
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

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        closeTunnel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
