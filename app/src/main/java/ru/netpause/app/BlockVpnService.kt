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
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class BlockVpnService : VpnService() {
    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        private const val CHANNEL_ID = "netpause_block"
        private const val NOTIFICATION_ID = 71
    }

    private var vpnInterface: android.os.ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, 30 * 60_000L)
            ?: 30 * 60_000L

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (vpnInterface == null) {
            val builder = Builder()
                .setSession("NetPause — блокировка интернета")
                .addAddress("10.77.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd77:7777::1", 128)
                .addRoute("::", 0)
                .setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startDiscardingPackets()
        }

        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, duration.coerceIn(60_000L, 24 * 60 * 60_000L))
        return START_NOT_STICKY
    }

    private fun startDiscardingPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        running.set(true)
        readerThread = Thread {
            val buffer = ByteArray(32767)
            try {
                FileInputStream(fd).use { input ->
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        // Намеренно не пересылаем пакеты: весь IP-трафик блокируется.
                    }
                }
            } catch (_: Exception) {
                // Закрытие VPN-интерфейса при остановке — ожидаемая ситуация.
            }
        }.apply {
            name = "NetPause-PacketDrop"
            isDaemon = true
            start()
        }
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
        running.set(false)
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}
