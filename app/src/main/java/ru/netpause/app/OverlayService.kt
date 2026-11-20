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
import android.net.VpnService
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    companion object {
        const val ACTION_HIDE = "ru.netpause.app.action.HIDE_FLOATING"
        const val ACTION_SHUTDOWN = "ru.netpause.app.action.SHUTDOWN_ALL"
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var panelStatus: TextView? = null
    private var lastBubbleActive: Boolean? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }

    // The only periodic UI work is a tiny indicator refresh. VPN packet processing
    // happens on its own blocking worker thread inside BlockVpnService.
    private val refresh = object : Runnable {
        override fun run() {
            updateBubble()
            panelStatus?.text = statusText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(72, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("overlay_enabled", false).apply()
            stopSelf()
            return
        }
        createBubble()
        handler.postDelayed(refresh, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                prefs.edit().putBoolean("overlay_enabled", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHUTDOWN -> {
                prefs.edit()
                    .putBoolean("overlay_enabled", false)
                    .putLong("active_until", 0L)
                    .apply()
                BlockVpnService.requestStop(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        return START_STICKY
    }

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
        val shutdown = PendingIntent.getService(
            this, 73, Intent(this, OverlayService::class.java).setAction(ACTION_SHUTDOWN),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "netpause_overlay")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("NetPause работает")
            .setContentText("Плавающая кнопка доступна поверх приложений")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить NetPause", shutdown)

        return builder.build()
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
            text = "NP"
            textSize = 16f
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
            contentDescription = "NetPause: открыть плавающее меню"
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
            x = prefs.getInt("bubble_x", dp(14)).coerceAtLeast(0)
            y = prefs.getInt("bubble_y", dp(180)).coerceAtLeast(dp(24))
        }
        try {
            wm.addView(b, bubbleParams)
        } catch (_: Exception) {
            prefs.edit().putBoolean("overlay_enabled", false).apply()
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
                    val maxX = (resources.displayMetrics.widthPixels - dp(58)).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - dp(90)).coerceAtLeast(dp(24))
                    bubbleParams.x = (startX + dx).coerceIn(0, maxX)
                    bubbleParams.y = (startY + dy).coerceIn(dp(24), maxY)
                    try { wm.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit()
                            .putInt("bubble_x", bubbleParams.x)
                            .putInt("bubble_y", bubbleParams.y)
                            .apply()
                    } else {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        togglePanel()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putInt("bubble_x", bubbleParams.x)
                        .putInt("bubble_y", bubbleParams.y)
                        .apply()
                    return true
                }
            }
            return false
        }
    }

    private fun togglePanel() {
        updateBubble()
        if (panel != null) closePanel() else openPanel()
    }

    private fun openMainForPermissions() {
        try {
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PERMISSIONS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Открой NetPause и перейди в «Разрешения и производительность».", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Stop only the VPN tunnel/service. The floating overlay is a separate
     * service and deliberately remains visible after this method returns.
     */
    private fun stopVpnOnly() {
        prefs.edit().putLong("active_until", 0L).apply()
        BlockVpnService.requestStop(this)
        updateBubble()
        panelStatus?.text = statusText()
    }

    private fun hideBubbleOnly() {
        prefs.edit().putBoolean("overlay_enabled", false).apply()
        stopSelf()
    }

    private fun shutdownEverything() {
        prefs.edit()
            .putBoolean("overlay_enabled", false)
            .putLong("active_until", 0L)
            .apply()
        BlockVpnService.requestStop(this)
        stopSelf()
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

        root.addView(text("ПЛАВАЮЩЕЕ УПРАВЛЕНИЕ", 10f, Color.LTGRAY, true).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(text(
            "Запускай блокировку столько раз, сколько нужно.",
            12f, Color.rgb(180, 205, 230), true
        ))
        panelStatus = text(statusText(), 13f, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(panelStatus)

        val allowedCount = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        root.addView(text(
            "Интернет останется у $allowedCount выбранных приложений. Остальные будут заблокированы.",
            12f, Color.LTGRAY
        ).apply { setPadding(0, 0, 0, dp(10)) })

        val settings = Button(this).apply {
            text = "ПОИСК И ВЫБОР ПРИЛОЖЕНИЙ"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.rgb(175, 230, 245))
            background = rounded(Color.rgb(36, 57, 91), 12, Color.rgb(70, 110, 165))
            setOnClickListener {
                closePanel()
                try {
                    startActivity(
                        Intent(this@OverlayService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
                } else if (VpnService.prepare(this@OverlayService) != null) {
                    Toast.makeText(this@OverlayService, "Сначала разреши VPN в центре разрешений. Само окно VPN не включает.", Toast.LENGTH_LONG).show()
                    closePanel()
                    openMainForPermissions()
                } else {
                    prefs.edit().putInt("last_minutes", mins).apply()
                    val service = Intent(this@OverlayService, BlockVpnService::class.java)
                        .putExtra(BlockVpnService.EXTRA_DURATION_MS, mins * 60_000L)
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service)
                        else startService(service)
                        closePanel()
                        Toast.makeText(this@OverlayService, "Запускаю блокировку на $mins мин.", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this@OverlayService, "Не удалось запустить VPN. Проверь разрешение.", Toast.LENGTH_LONG).show()
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
                stopVpnOnly()
                closePanel()
                Toast.makeText(
                    this@OverlayService,
                    "VPN полностью остановлен. Плавающее окно осталось включено.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        root.addView(text(
            "Выбранные приложения остаются онлайн. Остальные — без доступа к сети. Таймер максимум 2 часа.",
            10f, Color.LTGRAY
        ).apply { setPadding(0, dp(10), 0, 0) })

        root.addView(makeActionButton("СВЕРНУТЬ ПАНЕЛЬ", Color.rgb(36, 57, 91)) {
            closePanel()
        })
        root.addView(makeActionButton("СКРЫТЬ ПЛАВАЮЩУЮ КНОПКУ", Color.rgb(58, 52, 72)) {
            hideBubbleOnly()
        })
        root.addView(makeActionButton("ПОЛНОСТЬЮ ВЫКЛЮЧИТЬ NETPAUSE", Color.rgb(105, 40, 56)) {
            shutdownEverything()
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        panel = scroll

        val maxHeight = (resources.displayMetrics.heightPixels - dp(70)).coerceAtLeast(dp(360))
        val panelParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(280)),
            maxHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(30)
        }
        try {
            wm.addView(scroll, panelParams)
            bubble?.visibility = View.GONE
        } catch (_: Exception) {
            panel = null
            Toast.makeText(this, "Не удалось открыть окно поверх приложений", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeActionButton(label: String, backgroundColor: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            background = rounded(backgroundColor, 11)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(39)).apply { topMargin = dp(7) }
        }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        panelStatus = null
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
        b.text = if (active) "●" else "NP"
        b.contentDescription = if (active) {
            "NetPause: блокировка активна. Нажми, чтобы открыть управление."
        } else {
            "NetPause: открыть управление блокировкой."
        }
        b.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (active) intArrayOf(Color.rgb(0, 190, 130), Color.rgb(0, 120, 160))
            else intArrayOf(Color.rgb(0, 210, 220), Color.rgb(55, 90, 235))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }
        if (lastBubbleActive != active) {
            lastBubbleActive = active
            b.animate().cancel()
            b.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(100L)
                .withEndAction {
                    b.animate().scaleX(1f).scaleY(1f).setDuration(190L).start()
                }
                .start()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        closePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(72)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
