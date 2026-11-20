package ru.netpause.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }

    private val refresh = object : Runnable {
        override fun run() {
            updateBubble()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(72, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createBubble()
        handler.post(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "netpause_overlay", "Плавающая кнопка NetPause",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val open = PendingIntent.getActivity(
            this, 72, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "netpause_overlay")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("NetPause работает")
                .setContentText("Плавающая кнопка доступна поверх приложений")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("NetPause работает")
                .setContentText("Плавающая кнопка доступна поверх приложений")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun createBubble() {
        val b = TextView(this).apply {
            text = "N"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 210, 220), Color.rgb(55, 90, 235))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(10).toFloat()
            setOnTouchListener(BubbleTouch())
        }
        bubble = b
        bubbleParams = WindowManager.LayoutParams(
            dp(58), dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(180)
        }
        try {
            wm.addView(b, bubbleParams)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - e.rawX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    bubbleParams.x = (startX + dx).coerceAtLeast(0)
                    bubbleParams.y = (startY + dy).coerceAtLeast(0)
                    try { wm.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    return true
                }
            }
            return false
        }
    }

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(17), dp(19), dp(17))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(14, 22, 45), Color.rgb(29, 44, 80))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(65, 105, 165))
            }
            elevation = dp(18).toFloat()
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = text("NETPAUSE", 18f, Color.rgb(100, 230, 240), true)
        val close = text("×", 28f, Color.LTGRAY, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { closePanel() }
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(38), 1f))
        top.addView(close)
        root.addView(top)

        root.addView(text("БЫСТРОЕ УПРАВЛЕНИЕ ИНТЕРНЕТОМ", 10f, Color.LTGRAY, true).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(text("Сегодня осталось: " + (3 - usedToday()).coerceAtLeast(0) + " из 3 запусков", 12f, Color.rgb(180, 205, 230), true))
        root.addView(text(statusText(), 13f, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(10))
        })

        val allowedCount = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        root.addView(text(
            "Интернет останется у $allowedCount выбранных приложений. Остальные будут заблокированы.",
            12f, Color.LTGRAY
        ).apply { setPadding(0, 0, 0, dp(10)) })

        val settings = Button(this).apply {
            text = "НАСТРОИТЬ ПРИЛОЖЕНИЯ"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.rgb(175, 230, 245))
            background = rounded(Color.rgb(36, 57, 91), 12, Color.rgb(70, 110, 165))
            setOnClickListener {
                closePanel()
                try {
                    startActivity(
                        Intent(this@OverlayService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (_: Exception) {
                    Toast.makeText(this@OverlayService, "Открой NetPause для выбора приложений", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(settings, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(12) })

        root.addView(text("На сколько минут включить блокировку?", 13f, Color.LTGRAY))
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("last_minutes", 30).coerceIn(1, 120).toString())
            hint = "1–120 минут"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = rounded(Color.rgb(35, 49, 78), 13, Color.rgb(70, 105, 155))
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(7) })

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(15, 30, 60, 90, 120).forEach { value ->
            val chip = TextView(this).apply {
                text = value.toString()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(Color.rgb(43, 62, 100), 9)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { input.setText(value.toString()) }
            }
            presets.addView(chip, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(2), dp(9), dp(2), 0)
            })
        }
        root.addView(presets)

        val activate = Button(this).apply {
            text = "НАЧАТЬ БЛОКИРОВКУ  →"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 170, 190), Color.rgb(70, 100, 240))
            ).apply { cornerRadius = dp(14).toFloat() }
            setOnClickListener {
                val mins = input.text.toString().toIntOrNull()
                if (mins == null || mins !in 1..120) {
                    Toast.makeText(this@OverlayService, "Укажи от 1 до 120 минут", Toast.LENGTH_SHORT).show()
                } else if (usedToday() >= 3) {
                    Toast.makeText(this@OverlayService, "На сегодня все 3 запуска использованы", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putInt("last_minutes", mins).apply()
                    incrementUsage()
                    val until = System.currentTimeMillis() + mins * 60_000L
                    prefs.edit().putLong("active_until", until).apply()
                    val service = Intent(this@OverlayService, BlockVpnService::class.java)
                        .putExtra(BlockVpnService.EXTRA_DURATION_MS, mins * 60_000L)
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service)
                        else startService(service)
                        closePanel()
                        Toast.makeText(this@OverlayService, "Блокировка включена на $mins мин.", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        incrementUsageBack()
                        prefs.edit().putLong("active_until", 0L).apply()
                        Toast.makeText(this@OverlayService, "Не удалось включить VPN-блокировку", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(activate, LinearLayout.LayoutParams(-1, dp(51)).apply { topMargin = dp(13) })

        val stop = Button(this).apply {
            text = "СНЯТЬ БЛОКИРОВКУ"
            isAllCaps = false
            setTextColor(Color.rgb(255, 190, 195))
            background = rounded(Color.rgb(58, 43, 65), 13)
            setOnClickListener {
                stopService(Intent(this@OverlayService, BlockVpnService::class.java))
                prefs.edit().putLong("active_until", 0L).apply()
                closePanel()
                Toast.makeText(this@OverlayService, "Блокировка остановлена", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        root.addView(text(
            "Выбранные приложения остаются онлайн. Остальные — без доступа к сети. Максимум 2 часа.",
            10f, Color.LTGRAY
        ).apply { setPadding(0, dp(10), 0, 0) })

        panel = root
        val panelParams = WindowManager.LayoutParams(
            dp(326), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(64)
        }
        try {
            wm.addView(root, panelParams)
            bubble?.visibility = View.GONE
        } catch (_: Exception) {
            panel = null
            Toast.makeText(this, "Не удалось открыть окно поверх приложений", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        bubble?.visibility = View.VISIBLE
        updateBubble()
    }

    private fun statusText(): String {
        val left = prefs.getLong("active_until", 0L) - System.currentTimeMillis()
        return if (left > 0) {
            val total = left / 1000
            "● БЛОКИРОВКА АКТИВНА  " + (total / 60) + ":" + (total % 60).toString().padStart(2, '0')
        } else "○ БЛОКИРОВКА ВЫКЛЮЧЕНА"
    }

    private fun updateBubble() {
        val b = bubble as? TextView ?: return
        val active = prefs.getLong("active_until", 0L) > System.currentTimeMillis()
        b.text = if (active) "●" else "N"
        b.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (active) intArrayOf(Color.rgb(0, 190, 130), Color.rgb(0, 120, 160))
            else intArrayOf(Color.rgb(0, 210, 220), Color.rgb(55, 90, 235))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun usedToday(): Int {
        val day = todayKey()
        if (prefs.getString("usage_day", "") != day) {
            prefs.edit().putString("usage_day", day).putInt("usage_count", 0).apply()
            return 0
        }
        return prefs.getInt("usage_count", 0)
    }

    private fun incrementUsage() {
        prefs.edit().putString("usage_day", todayKey())
            .putInt("usage_count", usedToday() + 1).apply()
    }

    private fun incrementUsageBack() {
        val count = usedToday()
        prefs.edit().putInt("usage_count", (count - 1).coerceAtLeast(0)).apply()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
