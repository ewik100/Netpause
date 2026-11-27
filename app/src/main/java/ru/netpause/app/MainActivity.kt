package ru.netpause.app

import android.Manifest
import kotlin.math.sin
import kotlin.math.cos
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.WindowInsets
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_PERMISSIONS = "open_permissions"
    }

    private val vpnRequestCode = 4102
    private val notificationRequestCode = 4103
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }

    private var pendingPermissionStep: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dashboardVisible = false
    private var dashboardRenderedActive = false
    private var dashboardTimeLabel: TextView? = null
    private var dashboardStateLabel: TextView? = null
    private var dashboardDescriptionLabel: TextView? = null
    private var dashboardErrorLabel: TextView? = null
    private var dashboardOrbit: StatusOrbitView? = null

    private val dashboardTicker = object : Runnable {
        override fun run() {
            if (!dashboardVisible || isFinishing) return
            val now = System.currentTimeMillis()
            val until = prefs.getLong("active_until", 0L)
            val active = until > now

            if (active != dashboardRenderedActive) {
                if (!active) clearExpiredBlockState()
                showDashboard()
                return
            }

            if (active) {
                val remaining = (until - now).coerceAtLeast(0L)
                dashboardTimeLabel?.text = formatRemaining(remaining)
                val total = prefs.getLong(
                    "active_duration_ms",
                    prefs.getInt("last_minutes", 30).coerceIn(1, 120) * 60_000L
                ).coerceAtLeast(1L)
                dashboardOrbit?.updateState(true, remaining.toFloat() / total.toFloat())
            } else {
                dashboardOrbit?.updateState(false, 0f)
            }

            val error = prefs.getString("last_error", null).orEmpty()
            dashboardErrorLabel?.let {
                it.text = error
                it.visibility = if (error.isBlank()) View.GONE else View.VISIBLE
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun formatRemaining(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun scheduleDashboardTicker() {
        if (!dashboardVisible) return
        mainHandler.removeCallbacks(dashboardTicker)
        mainHandler.postDelayed(dashboardTicker, 1000L)
    }

    private data class LaunchApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearExpiredBlockState()
        window.statusBarColor = Color.rgb(10, 17, 35)
        window.navigationBarColor = Color.rgb(10, 17, 35)

        val alreadyOpenedSetup = prefs.getBoolean("permission_center_intro_seen", false)
        if (intent.getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false)) {
            showPermissionCenter()
        } else if (!alreadyOpenedSetup) {
            prefs.edit().putBoolean("permission_center_intro_seen", true).apply()
            showPermissionCenter()
        } else {
            showDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        clearExpiredBlockState()
        when (pendingPermissionStep) {
            "manual_overlay", "manual_battery", "manual_notifications" -> {
                pendingPermissionStep = null
                showPermissionCenter()
            }
            "manual_poco" -> {
                pendingPermissionStep = null
                showPocoGuide()
            }
            "manual_system_vpn" -> {
                pendingPermissionStep = null
                BlockVpnService.refreshSystemModes(this)
                window.decorView.postDelayed({
                    if (!isFinishing) showPermissionCenter()
                }, 300L)
            }
            "vpn" -> Unit // VPN consent returns through onActivityResult().
            else -> Unit
        }
        scheduleDashboardTicker()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(dashboardTicker)
        super.onPause()
    }

    override fun onDestroy() {
        dashboardVisible = false
        mainHandler.removeCallbacks(dashboardTicker)
        super.onDestroy()
    }

    /**
     * If the saved deadline has passed, force-stop the VPN service as a
     * fail-safe. This also clears stale status after the app was backgrounded.
     */
    private fun clearExpiredBlockState() {
        val until = prefs.getLong("active_until", 0L)
        if (until > 0L && until <= System.currentTimeMillis()) {
            BlockVpnService.requestStop(this)
            prefs.edit()
                .putLong("active_until", 0L)
                .putLong("active_duration_ms", 0L)
                .apply()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun pageBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(8, 15, 31), Color.rgb(25, 38, 70))
    )

    private fun panelBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(14, 23, 45), Color.rgb(29, 43, 76))
    ).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.rgb(55, 92, 145))
    }

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun makeButton(value: String, listener: () -> Unit): Button =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 165, 190), Color.rgb(65, 95, 225))
            ).apply { cornerRadius = dp(14).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { listener() }
        }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
    private fun hasVpnPermission(): Boolean = VpnService.prepare(this) == null

    private fun systemReportsActiveVpn(): Boolean {
        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    private fun openSystemVpnSettings() {
        pendingPermissionStep = "manual_system_vpn"
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            pendingPermissionStep = null
            Toast.makeText(this, "Открой Android → Сеть и интернет → VPN.", Toast.LENGTH_LONG).show()
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun batteryUnrestricted(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun showPermissionCenter(message: String? = null) {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            background = pageBackground()
        }
        scroll.addView(root)

        root.addView(makeText("NETPAUSE", 27f, Color.rgb(95, 230, 245), true))
        root.addView(makeText("Разрешения и работа в фоне", 19f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(7))
        })
        root.addView(makeText(
            "Каждый доступ настраивается отдельно. Уже выданные разрешения повторно не запрашиваются. Само разрешение VPN не подключает телефон к VPN — соединение появляется только на время включённой блокировки.",
            12f, Color.LTGRAY
        ).apply { setPadding(0, 0, 0, dp(14)) })

        if (!message.isNullOrBlank()) {
            root.addView(makeText(message, 12f, Color.rgb(255, 205, 145)).apply {
                setPadding(0, 0, 0, dp(12))
            })
        }

        val vpnReady = hasVpnPermission()
        val overlayReady = hasOverlayPermission()
        val optionalReady = listOf(notificationsGranted(), batteryUnrestricted()).count { it }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panelBackground()
        }
        summary.addView(makeText(
            "VPN: " + if (vpnReady) "РАЗРЕШЁН" else "НУЖНО РАЗРЕШИТЬ",
            13f, if (vpnReady) Color.rgb(95, 225, 160) else Color.rgb(255, 195, 115), true
        ))
        summary.addView(makeText(
            "Плавающее окно: " + if (overlayReady) "разрешено" else "не обязательно · выключено",
            11f, Color.LTGRAY
        ).apply { setPadding(0, dp(5), 0, 0) })
        summary.addView(makeText(
            "Дополнительные настройки: $optionalReady из 2",
            11f, Color.LTGRAY
        ).apply { setPadding(0, dp(3), 0, 0) })
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) })

        addPermissionItem(
            root,
            "1 · Плавающее окно",
            "Нужно, чтобы кнопка NetPause отображалась поверх других приложений. Без этого можно пользоваться приложением из обычного окна.",
            hasOverlayPermission(),
            "РАЗРЕШИТЬ ОТОБРАЖЕНИЕ"
        ) { openOverlayPermission() }

        addPermissionItem(
            root,
            "2 · Разрешение локального VPN",
            "Обязательное разрешение Android для фильтрации трафика. Оно не запускает соединение и не включает VPN само по себе.",
            hasVpnPermission(),
            "РАЗРЕШИТЬ VPN"
        ) { requestVpnPermission() }

        addPermissionItem(
            root,
            "3 · Уведомления",
            "Показывают активную сессию и кнопку остановки. Можно разрешить отдельно; отказ не должен запускать повторные запросы.",
            notificationsGranted(),
            "РАЗРЕШИТЬ УВЕДОМЛЕНИЯ"
        ) { requestNotificationPermission() }

        addPermissionItem(
            root,
            "4 · Работа без ограничений батареи",
            "Помогает Android не завершать плавающее окно и таймер в фоне. Это дополнительная настройка, не разрешение VPN.",
            batteryUnrestricted(),
            "НАСТРОИТЬ БАТАРЕЮ"
        ) { requestBackgroundFreedom() }

        addPermissionItem(
            root,
            "5 · Автозапуск и фон POCO",
            "Это настройка оболочки телефона, а не отдельное разрешение Android. Инструкция покажет, где проверить автозапуск и режим «Нет ограничений».",
            null,
            "ПОКАЗАТЬ ИНСТРУКЦИЮ"
        ) { showPocoGuide() }

        val vpnStillActive = systemReportsActiveVpn() && prefs.getLong("active_until", 0L) <= System.currentTimeMillis()
        val alwaysOnConfigured = prefs.getBoolean("system_vpn_always_on", false)
        val lockdownConfigured = prefs.getBoolean("system_vpn_lockdown", false)
        if (vpnStillActive || alwaysOnConfigured || lockdownConfigured) {
            val warning = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(65, 39, 48))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(190, 93, 105))
                }
            }
            warning.addView(makeText(
                if (alwaysOnConfigured || lockdownConfigured) "ПРОВЕРЬ СИСТЕМНЫЙ РЕЖИМ VPN"
                else "ANDROID СООБЩАЕТ ОБ АКТИВНОМ VPN",
                12f, Color.rgb(255, 190, 190), true
            ))
            val warningBody = when {
                alwaysOnConfigured && lockdownConfigured ->
                    "Для NetPause обнаружены режимы «Всегда включён» и «Блокировать соединения без VPN». Отключи оба переключателя в системных настройках VPN. Иначе Android может удерживать или перезапускать VPN независимо от кнопки приложения."
                alwaysOnConfigured ->
                    "Для NetPause обнаружен режим «Всегда включён». Отключи его в системных настройках VPN, чтобы соединение не запускалось системой самостоятельно."
                lockdownConfigured ->
                    "Для NetPause обнаружена системная блокировка без VPN. Отключи её в системных настройках VPN, иначе Android может сохранять сетевое ограничение даже после остановки приложения."
                else ->
                    "Блокировка NetPause не отмечена как активная, но Android всё ещё сообщает об активном VPN-соединении. Открой системные настройки VPN и проверь подключение NetPause."
            }
            warning.addView(makeText(warningBody, 12f, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(9))
            })
            warning.addView(makeButton("ОТКРЫТЬ НАСТРОЙКИ VPN") { openSystemVpnSettings() },
                LinearLayout.LayoutParams(-1, dp(43)))
            root.addView(warning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        root.addView(makeText(
            "Системное VPN-соединение управляется Android. Если в настройках телефона всё ещё указано «Всегда включён» или включена блокировка без VPN, это нужно отключить в системном меню — обычное разрешение приложения не может менять этот защищённый переключатель.",
            11f, Color.rgb(175, 195, 220)
        ).apply { setPadding(dp(2), dp(8), dp(2), dp(12)) })

        root.addView(makeButton("ОТКРЫТЬ СИСТЕМНЫЕ НАСТРОЙКИ VPN") {
            openSystemVpnSettings()
        }, LinearLayout.LayoutParams(-1, dp(46)))

        root.addView(makeButton("НАЗАД В NETPAUSE") {
            pendingPermissionStep = null
            showDashboard()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        setScreenContent(scroll)
        root.alpha = 0f
        root.translationY = dp(6).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(230L).start()
    }

    private fun addPermissionItem(
        root: LinearLayout,
        title: String,
        description: String,
        granted: Boolean?,
        actionText: String,
        action: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panelBackground()
        }
        val state = when (granted) {
            true -> "✓ РАЗРЕШЕНО"
            false -> "НУЖНО РАЗРЕШИТЬ"
            null -> "РУЧНАЯ НАСТРОЙКА"
        }
        val stateColor = when (granted) {
            true -> Color.rgb(95, 225, 160)
            false -> Color.rgb(255, 195, 115)
            null -> Color.rgb(130, 200, 235)
        }
        card.addView(makeText(title, 14f, Color.WHITE, true))
        card.addView(makeText(state, 10f, stateColor, true).apply {
            setPadding(0, dp(4), 0, dp(5))
        })
        card.addView(makeText(description, 12f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, dp(9))
        })
        val button = makeButton(if (granted == true) "РАЗРЕШЕНИЕ ЕСТЬ" else actionText, action)
        if (granted == true) {
            button.isEnabled = false
            button.alpha = 0.55f
        }
        card.addView(button, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun openOverlayPermission() {
        pendingPermissionStep = "manual_overlay"
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть настройку поверх других приложений", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingPermissionStep = "vpn"
            startActivityForResult(intent, vpnRequestCode)
        }
        else Toast.makeText(this, "VPN уже разрешён. Сам VPN сейчас не включён.", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notificationsGranted()) {
            Toast.makeText(this, "Уведомления уже разрешены или не требуют отдельного запроса", Toast.LENGTH_SHORT).show()
            return
        }

        pendingPermissionStep = "manual_notifications"
        val askedBefore = prefs.getBoolean("notification_permission_asked", false)
        if (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            prefs.edit().putBoolean("notification_permission_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
        } else {
            // The system no longer shows the dialog after a permanent denial.
            // Send the user to the app's notification settings instead of
            // repeatedly requesting a permission that cannot be granted here.
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun requestBackgroundFreedom() {
        pendingPermissionStep = "manual_battery"
        if (Build.VERSION.SDK_INT >= 23 && !batteryUnrestricted()) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {
                // Fall back to app settings.
            }
        }
        openPocoAppSettings()
    }

    private fun showPocoGuide() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            background = pageBackground()
        }
        scroll.addView(root)
        root.addView(makeText("НАСТРОЙКА POCO", 25f, Color.rgb(95, 230, 245), true))
        root.addView(makeText(
            "Эти параметры настраиваются один раз. Они не включают VPN и не заменяют основные разрешения Android.",
            12f, Color.LTGRAY
        ).apply { setPadding(0, dp(8), 0, dp(15)) })

        val steps = listOf(
            "1 · Батарея приложения" to "Настройки телефона → Приложения → NetPause → Экономия заряда батареи → «Нет ограничений».",
            "2 · Фоновый автозапуск" to "Открой приложение «Безопасность» или настройки приложений → Автозапуск. Разреши NetPause, если такой пункт доступен на версии HyperOS.",
            "3 · Закрепление в фоне" to "После запуска плавающей кнопки открой список недавних приложений. При наличии значка замка закрепи NetPause.",
            "4 · Проверка" to "Вернись в приложение, запусти плавающую кнопку и проверь, что она остаётся доступной при переключении приложений."
        )
        steps.forEach { (heading, body) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = panelBackground()
            }
            card.addView(makeText(heading, 14f, Color.WHITE, true))
            card.addView(makeText(body, 12f, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, 0)
            })
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        root.addView(makeButton("ОТКРЫТЬ НАСТРОЙКИ NETPAUSE") {
            openPocoAppSettings()
        }, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(makeButton("НАЗАД К РАЗРЕШЕНИЯМ") {
            pendingPermissionStep = null
            showPermissionCenter()
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })
        setScreenContent(scroll)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(220L).start()
    }

    private fun openPocoAppSettings() {
        pendingPermissionStep = "manual_poco"
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Открой настройки Android → Приложения → NetPause → Батарея.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Use Activity Result API in a production app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            pendingPermissionStep = null
            if (resultCode == RESULT_OK) {
                showPermissionCenter("Разрешение VPN сохранено. Соединение пока выключено.")
            } else {
                showPermissionCenter("Разрешение VPN не выдано. Остальные функции NetPause доступны отдельно.")
            }
        }
    }

    @Deprecated("Use Activity Result API in a production app")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequestCode) {
            pendingPermissionStep = null
            showPermissionCenter()
        }
    }

    private fun startOverlayService() {
        if (!hasOverlayPermission()) {
            showPermissionCenter("Сначала разреши отображение поверх других приложений.")
            return
        }
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            prefs.edit().putBoolean("overlay_enabled", true).apply()
            Toast.makeText(this, "Плавающая кнопка включена. VPN пока выключен.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось запустить окно. Проверь разрешения и настройки батареи.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        prefs.edit().putBoolean("overlay_enabled", false).apply()
        Toast.makeText(this, "Плавающая кнопка скрыта. Блокировка VPN не затронута.", Toast.LENGTH_SHORT).show()
        showDashboard()
    }

    private fun startSession(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 120)
        if (!hasVpnPermission()) {
            showPermissionCenter("Сначала разреши локальный VPN. Это не включает блокировку.")
            return
        }
        prefs.edit()
            .putInt("last_minutes", safeMinutes)
            .remove("last_error")
            .apply()
        val vpn = Intent(this, BlockVpnService::class.java)
            .putExtra(BlockVpnService.EXTRA_DURATION_MS, safeMinutes * 60_000L)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn) else startService(vpn)
            Toast.makeText(this, "Запрос отправлен. Проверяю подключение VPN…", Toast.LENGTH_SHORT).show()
            showDashboard()
        } catch (_: Exception) {
            prefs.edit().putString("last_error", "Не удалось запустить службу VPN. Проверь разрешения и повтори попытку.").apply()
            Toast.makeText(this, "Не удалось запустить блокировку. Проверь разрешения VPN.", Toast.LENGTH_LONG).show()
            showDashboard()
        }
    }

    private fun stopVpnOnly() {
        // Clear the visible state first, then stop the VPN service itself.
        prefs.edit()
            .putLong("active_until", 0L)
            .putLong("active_duration_ms", 0L)
            .apply()
        BlockVpnService.requestStop(this)
    }

    private fun shutdownNetPause() {
        stopVpnOnly()
        stopService(Intent(this, OverlayService::class.java))
        prefs.edit().putBoolean("overlay_enabled", false).apply()
        Toast.makeText(this, "NetPause полностью остановлен", Toast.LENGTH_LONG).show()
        showDashboard()
    }

    private fun setScreenContent(view: View) {
        dashboardVisible = false
        mainHandler.removeCallbacks(dashboardTicker)
        setContentView(view)
        if (Build.VERSION.SDK_INT >= 35) {
            val baseLeft = view.paddingLeft
            val baseTop = view.paddingTop
            val baseRight = view.paddingRight
            val baseBottom = view.paddingBottom
            view.setOnApplyWindowInsetsListener { target, insets ->
                val bars = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Insets.of(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }
                target.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom
                )
                insets
            }
            view.requestApplyInsets()
        }
    }

    private fun showDashboard() {
        val now = System.currentTimeMillis()
        val allowedCount = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        val overlayRunning = prefs.getBoolean("overlay_enabled", false)
        val vpnUntil = prefs.getLong("active_until", 0L)
        val blockActive = vpnUntil > now
        val remainingMs = (vpnUntil - now).coerceAtLeast(0L)
        val remainingSeconds = remainingMs / 1000L
        val timeLabel = String.format(
            Locale.US,
            "%02d:%02d",
            remainingSeconds / 60L,
            remainingSeconds % 60L
        )
        var selectedMinutes = prefs.getInt("last_minutes", 30).coerceIn(1, 120)
        dashboardRenderedActive = blockActive

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(26))
            background = pageBackground()
        }
        scroll.addView(root)

        // Compact brand header.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "NP"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 211, 213), Color.rgb(63, 91, 230))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(115, 235, 245))
            }
            elevation = dp(5).toFloat()
            contentDescription = "Логотип NetPause"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(11)
        })

        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(makeText("NETPAUSE", 21f, Color.WHITE, true))
        brand.addView(makeText("Контроль интернет-доступа", 11f, Color.rgb(165, 190, 220)))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))

        val version = TextView(this).apply {
            text = "v1.5"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(115, 235, 230))
            background = roundedVersionBadge()
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        header.addView(version)
        root.addView(header)

        root.addView(makeText(
            "Все основные действия — на одном экране.",
            13f, Color.rgb(190, 205, 225)
        ).apply { setPadding(0, dp(13), 0, dp(15)) })

        // Main status card.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(13), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(18, 31, 57), Color.rgb(28, 48, 82))
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(66, 110, 164))
            }
            elevation = dp(3).toFloat()
        }
        val progress = if (blockActive) {
            val total = prefs.getInt("last_minutes", 30).coerceIn(1, 120) * 60_000L
            (remainingMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val orbit = StatusOrbitView(blockActive, progress)
        dashboardOrbit = orbit
        hero.addView(
            orbit,
            LinearLayout.LayoutParams(dp(112), dp(112)).apply { marginEnd = dp(12) }
        )
        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stateLabel = makeText(
            if (blockActive) "СЕССИЯ ИДЁТ" else "СИСТЕМА ГОТОВА",
            11f,
            if (blockActive) Color.rgb(100, 230, 165) else Color.rgb(100, 225, 235),
            true
        )
        dashboardStateLabel = stateLabel
        heroInfo.addView(stateLabel)
        val timerLabel = makeText(
            if (blockActive) timeLabel else "VPN отключён",
            if (blockActive) 27f else 21f,
            Color.WHITE,
            true
        ).apply { setPadding(0, dp(5), 0, dp(4)) }
        dashboardTimeLabel = timerLabel
        heroInfo.addView(timerLabel)
        val descriptionLabel = makeText(
            if (blockActive) "До автоматической остановки"
            else "Подключение появится только после твоего запуска.",
            11f, Color.rgb(190, 205, 225)
        )
        dashboardDescriptionLabel = descriptionLabel
        heroInfo.addView(descriptionLabel)
        hero.addView(heroInfo, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val errorLabel = makeText(
            prefs.getString("last_error", null).orEmpty(),
            12f, Color.rgb(255, 190, 145)
        ).apply {
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            setPadding(dp(12), dp(8), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(62, 39, 43))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(150, 80, 80))
            }
        }
        dashboardErrorLabel = errorLabel
        root.addView(errorLabel, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        if (blockActive) {
            root.addView(makeButton("ОСТАНОВИТЬ БЛОКИРОВКУ") {
                stopVpnOnly()
                showDashboard()
            }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(14) })
        } else {
            val durationCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                background = panelBackground()
            }
            durationCard.addView(makeText("ДЛИТЕЛЬНОСТЬ СЕССИИ", 12f, Color.rgb(115, 230, 240), true))
            durationCard.addView(makeText(
                "Выбери время — максимум 120 минут. Число запусков не ограничено.",
                11f, Color.LTGRAY
            ).apply { setPadding(0, dp(5), 0, dp(11)) })

            val presets = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val chips = ArrayList<TextView>()
            fun updatePresetStyle() {
                chips.forEach { chip ->
                    val value = chip.tag as Int
                    val selected = value == selectedMinutes
                    chip.background = GradientDrawable().apply {
                        setColor(if (selected) Color.rgb(0, 145, 170) else Color.rgb(35, 51, 80))
                        cornerRadius = dp(11).toFloat()
                        setStroke(
                            dp(1),
                            if (selected) Color.rgb(100, 230, 240) else Color.rgb(67, 91, 130)
                        )
                    }
                    chip.setTextColor(if (selected) Color.WHITE else Color.rgb(205, 218, 236))
                }
            }
            val presetValues = (listOf(15, 30, 60, 90, 120) + selectedMinutes).distinct().sorted().take(6)
            presetValues.forEach { value ->
                val chip = TextView(this).apply {
                    text = value.toString()
                    tag = value
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(dp(1), dp(11), dp(1), dp(11))
                    minWidth = dp(39)
                    contentDescription = "$value минут"
                }
                chips.add(chip)
                presets.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
            updatePresetStyle()
            durationCard.addView(presets)
            val preciseDurationLabel = makeText(
                "Точная длительность: $selectedMinutes мин",
                11f, Color.rgb(185, 210, 235), true
            ).apply { setPadding(dp(2), dp(10), 0, dp(2)) }
            durationCard.addView(preciseDurationLabel)

            val durationSlider = SeekBar(this).apply {
                max = 119
                setProgress(selectedMinutes - 1)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 205, 215))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(125, 235, 245))
                contentDescription = "Точная длительность сессии от 1 до 120 минут"
            }
            durationCard.addView(durationSlider, LinearLayout.LayoutParams(-1, dp(36)))

            val startButton = makeButton("НАЧАТЬ БЛОКИРОВКУ  ·  " + selectedMinutes + " МИН") {
                startSession(selectedMinutes)
            }
            durationCard.addView(startButton, LinearLayout.LayoutParams(-1, dp(49)).apply {
                topMargin = dp(8)
            })

            durationSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progressValue: Int, fromUser: Boolean) {
                    selectedMinutes = progressValue + 1
                    preciseDurationLabel.text = "Точная длительность: $selectedMinutes мин"
                    prefs.edit().putInt("last_minutes", selectedMinutes).apply()
                    updatePresetStyle()
                    startButton.text = "НАЧАТЬ БЛОКИРОВКУ  ·  " + selectedMinutes + " МИН"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            chips.forEach { chip ->
                chip.setOnClickListener {
                    selectedMinutes = chip.tag as Int
                    durationSlider.setProgress(selectedMinutes - 1)
                }
            }
            root.addView(durationCard, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            })
        }

        // Three compact status tiles.
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun metric(value: String, caption: String): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(11), dp(5), dp(11))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 34, 61))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(48, 76, 119))
                }
            }
            card.addView(makeText(value, 18f, Color.WHITE, true))
            card.addView(makeText(caption, 9f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            return card
        }
        metrics.addView(metric("∞", "запусков"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        metrics.addView(metric("120 мин", "за сессию"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        metrics.addView(metric(allowedCount.toString(), "исключений"),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Reusable action card.
        fun addActionCard(
            title: String,
            detail: String,
            actionTitle: String,
            action: () -> Unit
        ) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                background = panelBackground()
            }
            card.addView(makeText(title, 14f, Color.WHITE, true))
            card.addView(makeText(detail, 11f, Color.LTGRAY).apply {
                setPadding(0, dp(5), 0, dp(10))
            })
            val actionButton = makeButton(actionTitle, action)
            card.addView(actionButton, LinearLayout.LayoutParams(-1, dp(44)))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }

        addActionCard(
            "ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ",
            "Выбери программы, которым разрешено оставаться онлайн во время блокировки. Список хранится на этом телефоне.",
            "НАСТРОИТЬ ИСКЛЮЧЕНИЯ"
        ) { showAppPicker() }

        addActionCard(
            "ПЛАВАЮЩЕЕ УПРАВЛЕНИЕ",
            if (overlayRunning) {
                "Кнопка NP включена. Через неё можно открыть панель управления поверх других приложений."
            } else {
                "Показывает кнопку NP поверх приложений. Запуск плавающего окна сам по себе не включает VPN."
            },
            if (overlayRunning) "СКРЫТЬ КНОПКУ NP" else "ВКЛЮЧИТЬ КНОПКУ NP"
        ) {
            if (overlayRunning) stopOverlayService() else {
                startOverlayService()
                if (hasOverlayPermission()) showDashboard()
            }
        }

        addActionCard(
            "РАЗРЕШЕНИЯ И РАБОТА В ФОНЕ",
            "Отдельная проверка разрешений Android, уведомлений, батареи и настроек POCO.",
            "ОТКРЫТЬ ЦЕНТР РАЗРЕШЕНИЙ"
        ) { showPermissionCenter() }

        addActionCard(
            "О ПРИЛОЖЕНИИ И ДАННЫХ",
            "Настройки, длительность и исключения хранятся локально. Аккаунт и собственный сервер не используются.",
            "ОТКРЫТЬ ИНФОРМАЦИЮ"
        ) { showAbout() }

        root.addView(makeButton("ПОЛНОСТЬЮ ВЫКЛЮЧИТЬ NETPAUSE") {
            shutdownNetPause()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(2) })

        root.addView(makeText(
            "Настройки сохраняются на устройстве при совместимом обновлении приложения.",
            10f, Color.rgb(150, 174, 204)
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(16), dp(6), dp(4))
        })

        setScreenContent(scroll)
        dashboardVisible = true
        scheduleDashboardTicker()
        root.alpha = 0f
        root.translationY = dp(6).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(240L).start()
    }

    private fun roundedVersionBadge(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(23, 49, 76))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), Color.rgb(55, 130, 160))
    }

    private fun showAbout() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            background = pageBackground()
        }
        scroll.addView(root)
        root.addView(makeText("NETPAUSE 1.5", 26f, Color.rgb(100, 230, 240), true))
        root.addView(makeText("Локальный инструмент цифрового фокуса", 14f, Color.WHITE, true)
            .apply { setPadding(0, dp(7), 0, dp(15)) })

        val notes = listOf(
            "КАК РАБОТАЕТ VPN" to "Во время активной блокировки Android создаёт локальный VPN-туннель. NetPause не подключает внешний VPN-сервер: трафик приложений, не внесённых в исключения, намеренно блокируется.",
            "ИСКЛЮЧЕНИЯ" to "Приложения из списка исключений обходят локальный VPN и сохраняют доступ к интернету. Список хранится на устройстве и может быть изменён в любое время.",
            "ПРИВАТНОСТЬ" to "В этой версии нет аккаунта и собственного сервера. NetPause не отправляет историю использования на сервер. Само приложение не анализирует содержимое трафика.",
            "ВАЖНО" to "Пока блокировка включена, интернет у остальных приложений недоступен. При проблемах останови VPN кнопкой в NetPause или системным переключателем VPN. Поведение нужно проверить на конкретной версии Android и оболочке POCO.",
            "НОВЫЕ ВОЗМОЖНОСТИ" to "Версия 1.5 обновляет экран сессии в реальном времени, добавляет точную настройку длительности от 1 до 120 минут и показывает понятную ошибку, если VPN не удалось запустить.",
            "СТАТУС ВЕРСИИ" to "Версия 1.5 — тестовая сборка. Перед публикацией в Google Play необходимы отдельная проверка совместимости, постоянная подпись релизной сборки, политика конфиденциальности и проверка соответствия правилам магазина."
        )
        notes.forEach { (heading, body) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                background = panelBackground()
            }
            card.addView(makeText(heading, 13f, Color.rgb(100, 225, 235), true))
            card.addView(makeText(body, 12f, Color.LTGRAY).apply {
                setPadding(0, dp(7), 0, 0)
            })
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
        root.addView(makeButton("НАЗАД") { showDashboard() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        setScreenContent(scroll)
    }

    private inner class StatusOrbitView(
        initialActive: Boolean,
        initialProgress: Float
    ) : View(this) {
        private var active: Boolean = initialActive
        private var progress: Float = initialProgress.coerceIn(0f, 1f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun updateState(activeNow: Boolean, progressNow: Float) {
            active = activeNow
            progress = progressNow.coerceIn(0f, 1f)
            invalidate()
        }
        private var phase = 0f
        private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            pulse.start()
        }

        override fun onDetachedFromWindow() {
            pulse.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val radius = side * 0.36f
            val color = if (active) Color.rgb(55, 220, 165) else Color.rgb(55, 195, 225)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = Color.argb((18 + phase * 28).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(cx, cy, radius + dp(10) + phase * dp(4), paint)

            paint.strokeWidth = dp(7).toFloat()
            paint.color = Color.rgb(39, 58, 88)
            val ring = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(ring, -90f, 360f, false, paint)
            if (active) {
                paint.color = color
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(ring, -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }

            val angle = (-Math.PI / 2.0) + (phase * Math.PI * 2.0)
            val dotX = cx + radius * cos(angle).toFloat()
            val dotY = cy + radius * sin(angle).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(dotX, dotY, dp(4).toFloat(), paint)

            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = dp(21).toFloat()
            paint.color = Color.WHITE
            canvas.drawText(if (active) "ON" else "READY", cx, cy + dp(5), paint)
            paint.textSize = dp(9).toFloat()
            paint.color = Color.LTGRAY
            canvas.drawText(if (active) "VPN" else "PAUSE", cx, cy + dp(22), paint)
        }
    }

    private fun launcherApps(): List<LaunchApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (appInfo.packageName == packageName) return@mapNotNull null
                try {
                    LaunchApp(
                        appInfo.packageName,
                        info.loadLabel(packageManager).toString(),
                        info.loadIcon(packageManager)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun showAppPicker() {
        val selected = (prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()).toMutableSet()
        val selectedCountLabel = makeText("Выбрано: ${selected.size}", 12f, Color.rgb(170, 210, 235), true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(14))
            background = pageBackground()
        }
        root.addView(makeText("ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ", 21f, Color.rgb(95, 230, 245), true))
        root.addView(makeText(
            "Выбери приложения, которым нужно оставить интернет во время блокировки. Список сохраняется постоянно — можно менять его когда угодно.",
            13f, Color.LTGRAY
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        val search = EditText(this).apply {
            setSingleLine(true)
            hint = "Поиск приложения или пакета…"
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 49, 78))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(70, 105, 155))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })

        val appScroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apps = launcherApps()
        val appRows = ArrayList<Pair<String, View>>()

        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(27, 42, 70))
                    cornerRadius = dp(12).toFloat()
                }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })

            val check = CheckBox(this).apply {
                text = app.label
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = selected.contains(app.packageName)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 215, 230))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    selectedCountLabel.text = "Выбрано: ${selected.size}"
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, dp(48), 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            appRows.add(Pair((app.label + " " + app.packageName).lowercase(Locale.getDefault()), row))
        }

        if (apps.isEmpty()) {
            rows.addView(makeText("Не удалось получить список приложений.", 14f, Color.WHITE))
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val term = s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
                appRows.forEach { (searchText, row) ->
                    row.visibility = if (term.isEmpty() || searchText.contains(term)) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        appScroll.addView(rows)
        root.addView(appScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(selectedCountLabel.apply {
            setPadding(0, dp(8), 0, dp(4))
        })

        val save = makeButton("СОХРАНИТЬ СПИСОК") {
            prefs.edit().putStringSet("allowed_apps", selected.toSet()).apply()
            reconfigureActiveVpn()
            Toast.makeText(this, "Список сохранён и останется после перезапуска.", Toast.LENGTH_LONG).show()
            showDashboard()
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(7) })
        root.addView(makeButton("НАЗАД") { showDashboard() }, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(8)
        })
        setScreenContent(root)
    }

    private fun reconfigureActiveVpn() {
        val until = prefs.getLong("active_until", 0L)
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0L && hasVpnPermission()) {
            val vpn = Intent(this, BlockVpnService::class.java)
                .putExtra(BlockVpnService.EXTRA_DURATION_MS, remaining)
                .putExtra(BlockVpnService.EXTRA_RECONFIGURE, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn) else startService(vpn)
            } catch (_: Exception) {
                Toast.makeText(this, "Новые правила применятся при следующем запуске.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
