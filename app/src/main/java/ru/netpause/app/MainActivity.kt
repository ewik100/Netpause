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
            "vpn" -> Unit // VPN consent returns through onActivityResult().
            else -> Unit
        }
    }

    /**
     * If the saved deadline has passed, force-stop the VPN service as a
     * fail-safe. This also clears stale status after the app was backgrounded.
     */
    private fun clearExpiredBlockState() {
        val until = prefs.getLong("active_until", 0L)
        if (until > 0L && until <= System.currentTimeMillis()) {
            BlockVpnService.requestStop(this)
            prefs.edit().putLong("active_until", 0L).apply()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
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

        val criticalReady = listOf(hasOverlayPermission(), hasVpnPermission()).count { it }
        val optionalReady = listOf(notificationsGranted(), batteryUnrestricted()).count { it }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panelBackground()
        }
        summary.addView(makeText(
            "ОСНОВНЫЕ ДОСТУПЫ: $criticalReady ИЗ 2",
            13f, if (criticalReady == 2) Color.rgb(95, 225, 160) else Color.rgb(255, 195, 115), true
        ))
        summary.addView(makeText(
            "Дополнительные настройки: $optionalReady из 2",
            11f, Color.LTGRAY
        ).apply { setPadding(0, dp(5), 0, 0) })
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
        if (vpnStillActive) {
            val warning = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(65, 39, 48))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(190, 93, 105))
                }
            }
            warning.addView(makeText("ANDROID СООБЩАЕТ ОБ АКТИВНОМ VPN", 12f, Color.rgb(255, 190, 190), true))
            warning.addView(makeText(
                "Блокировка NetPause сейчас не отмечена как активная, но система видит VPN-соединение. Проверь системные настройки VPN: отключи для NetPause режим «Всегда включён» и «Блокировать соединения без VPN», если они включены.",
                12f, Color.LTGRAY
            ).apply { setPadding(0, dp(6), 0, dp(9)) })
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
            true -> "✓ ГОТОВО"
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
        val button = makeButton(if (granted == true) "УЖЕ ВКЛЮЧЕНО" else actionText, action)
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
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            pendingPermissionStep = "manual_notifications"
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
        } else {
            Toast.makeText(this, "Уведомления уже разрешены или не требуют отдельного запроса", Toast.LENGTH_SHORT).show()
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

    private fun stopVpnOnly() {
        // Clear the visible state first, then stop the VPN service itself.
        prefs.edit().putLong("active_until", 0L).apply()
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
        val totalMs = prefs.getInt("last_minutes", 30).coerceIn(1, 120) * 60_000L
        val progress = if (blockActive) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
        val remainingSeconds = remainingMs / 1000L
        val timeLabel = String.format(
            Locale.US,
            "%02d:%02d",
            remainingSeconds / 60L,
            remainingSeconds % 60L
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(24))
            background = pageBackground()
        }
        scroll.addView(root)

        // Brand header with a custom animated identity mark.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = TextView(this).apply {
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
            elevation = dp(8).toFloat()
            contentDescription = "Логотип NetPause"
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            marginEnd = dp(13)
        })
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(makeText("NETPAUSE", 24f, Color.WHITE, true))
        brandText.addView(makeText("Цифровой фокус под твоим контролем", 11f, Color.rgb(165, 190, 220)))
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        val version = TextView(this).apply {
            text = "1.1"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(115, 235, 230))
            background = roundedVersionBadge()
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }
        header.addView(version)
        root.addView(header)

        root.addView(makeText(
            "Управляй доступом к интернету без лишних действий.",
            13f,
            Color.LTGRAY
        ).apply { setPadding(0, dp(15), 0, dp(17)) })

        // Main status visualization.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(17), dp(13), dp(17))
            background = panelBackground()
            elevation = dp(4).toFloat()
        }
        hero.addView(
            StatusOrbitView(blockActive, progress),
            LinearLayout.LayoutParams(dp(132), dp(132)).apply { marginEnd = dp(10) }
        )
        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroInfo.addView(makeText(
            if (blockActive) "БЛОКИРОВКА АКТИВНА" else "ГОТОВ К ФОКУСУ",
            11f,
            if (blockActive) Color.rgb(100, 230, 165) else Color.rgb(105, 225, 235),
            true
        ))
        heroInfo.addView(makeText(
            if (blockActive) timeLabel else "VPN выключен",
            25f,
            Color.WHITE,
            true
        ).apply { setPadding(0, dp(6), 0, dp(4)) })
        heroInfo.addView(makeText(
            if (blockActive) "Осталось до автоматического отключения"
            else "Нажми «Начать блокировку» в плавающем окне. VPN включится только на это время.",
            11f,
            Color.LTGRAY
        ))
        if (blockActive) {
            heroInfo.addView(makeButton("ОСТАНОВИТЬ VPN") {
                stopVpnOnly()
                showDashboard()
            }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
        } else {
            heroInfo.addView(makeText(
                if (overlayRunning) "Плавающая кнопка уже запущена"
                else "Плавающая кнопка пока выключена",
                10f,
                Color.rgb(145, 190, 215),
                true
            ).apply { setPadding(0, dp(9), 0, 0) })
        }
        hero.addView(heroInfo, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // A compact live summary, so limits are visible without opening settings.
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun metric(value: String, caption: String): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(12), dp(7), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 34, 61))
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), Color.rgb(48, 76, 119))
                }
            }
            card.addView(makeText(value, 19f, Color.WHITE, true))
            card.addView(makeText(caption, 10f, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            return card
        }
        metrics.addView(metric("∞", "без лимита запусков"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        metrics.addView(metric("120 мин", "макс. таймер сессии"),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        metrics.addView(metric(allowedCount.toString(), "исключений"),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        fun addSection(title: String, description: String, buttonText: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(15), dp(17), dp(15))
                background = panelBackground()
                alpha = 0f
                translationY = dp(8).toFloat()
            }
            card.addView(makeText(title, 15f, Color.WHITE, true))
            card.addView(makeText(description, 12f, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(10))
            })
            card.addView(makeButton(buttonText, action), LinearLayout.LayoutParams(-1, dp(46)))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            })
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((root.childCount * 35L).coerceAtMost(280L))
                .setDuration(260L)
                .start()
        }

        addSection(
            "ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ",
            "Выбери приложения, которым нужно оставить сеть во время блокировки. Список сохраняется и доступен для изменения в любое время.",
            "ПОИСК И ВЫБОР ПРИЛОЖЕНИЙ"
        ) { showAppPicker() }

        addSection(
            "ПЛАВАЮЩЕЕ ОКНО",
            if (overlayRunning) "Кнопка NetPause работает поверх приложений. Её можно перемещать и открывать в любой момент. Скрытие окна не отключает VPN."
            else "Запускает небольшую кнопку поверх приложений. Сам VPN при этом остаётся выключенным.",
            if (overlayRunning) "СКРЫТЬ ПЛАВАЮЩУЮ КНОПКУ" else "ЗАПУСТИТЬ ПЛАВАЮЩУЮ КНОПКУ"
        ) {
            if (overlayRunning) stopOverlayService() else startOverlayService()
        }

        addSection(
            "РАЗРЕШЕНИЯ И РАБОТА В ФОНЕ",
            "Проверка разрешений Android, настроек батареи и автозапуска POCO. Эти настройки не включают блокировку.",
            "ОТКРЫТЬ ЦЕНТР РАЗРЕШЕНИЙ"
        ) { showPermissionCenter() }

        addSection(
            "О NETPAUSE И ПРИВАТНОСТИ",
            "Что приложение делает с сетевым трафиком, какие данные хранит и какие ограничения важно знать.",
            "ПРОЧИТАТЬ"
        ) { showAbout() }

        root.addView(makeButton("ПОЛНОСТЬЮ ВЫКЛЮЧИТЬ NETPAUSE") {
            shutdownNetPause()
        }, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(3) })

        root.addView(makeText(
            "NETPAUSE 1.1  •  Без лимита запусков  •  До 120 минут за сессию",
            10f,
            Color.rgb(145, 170, 200)
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
        })

        setScreenContent(scroll)
        root.alpha = 0f
        root.translationY = dp(7).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(280L).start()
        avatar.animate().rotationBy(3f).setDuration(1400L).start()
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
        root.addView(makeText("NETPAUSE 1.1", 26f, Color.rgb(100, 230, 240), true))
        root.addView(makeText("Локальный инструмент цифрового фокуса", 14f, Color.WHITE, true)
            .apply { setPadding(0, dp(7), 0, dp(15)) })

        val notes = listOf(
            "КАК РАБОТАЕТ VPN" to "Во время активной блокировки Android создаёт локальный VPN-туннель. NetPause не подключает внешний VPN-сервер: трафик приложений, не внесённых в исключения, намеренно блокируется.",
            "ИСКЛЮЧЕНИЯ" to "Приложения из списка исключений обходят локальный VPN и сохраняют доступ к интернету. Список хранится на устройстве и может быть изменён в любое время.",
            "ПРИВАТНОСТЬ" to "В этой версии нет аккаунта и собственного сервера. NetPause не отправляет историю использования на сервер. Само приложение не анализирует содержимое трафика.",
            "ВАЖНО" to "Пока блокировка включена, интернет у остальных приложений недоступен. При проблемах останови VPN кнопкой в NetPause или системным переключателем VPN. Поведение нужно проверить на конкретной версии Android и оболочке POCO.",
            "СТАТУС ВЕРСИИ" to "Версия 1.1 предназначена для тестирования. Перед публикацией в Google Play необходимы отдельная проверка совместимости, корректная подпись релизной сборки, политика конфиденциальности и проверка соответствия правилам магазина."
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
        private val active: Boolean,
        private val progress: Float
    ) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
