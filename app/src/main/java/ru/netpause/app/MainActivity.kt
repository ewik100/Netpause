package ru.netpause.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val vpnRequestCode = 4102
    private val notificationRequestCode = 4103
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }

    private var permissionFlowRunning = false
    private var pendingPermissionStep: String? = null

    private data class LaunchApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 17, 35)
        window.navigationBarColor = Color.rgb(10, 17, 35)

        val alreadyOpenedSetup = prefs.getBoolean("permission_center_intro_seen", false)
        if (!alreadyOpenedSetup) {
            prefs.edit().putBoolean("permission_center_intro_seen", true).apply()
            showPermissionCenter()
        } else {
            showDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        when (pendingPermissionStep) {
            "overlay" -> {
                pendingPermissionStep = null
                if (Settings.canDrawOverlays(this)) continuePermissionFlow()
                else abortPermissionFlow("Разрешение отображения поверх окон пока не выдано.")
                return
            }
            "battery" -> {
                pendingPermissionStep = null
                completePermissionFlow()
                return
            }
            "vpn", "notifications" -> return
        }

        if (permissionFlowRunning && pendingPermissionStep == null) {
            continuePermissionFlow()
        } else if (!permissionFlowRunning) {
            showDashboard()
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
            setPadding(dp(22), dp(30), dp(22), dp(26))
            background = pageBackground()
        }
        scroll.addView(root)

        root.addView(makeText("NETPAUSE", 29f, Color.rgb(95, 230, 245), true))
        root.addView(makeText("Центр разрешений", 20f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(makeText(
            "Здесь собраны настройки, которые Android и POCO требуют для плавающего окна и фоновой работы. Они не включают блокировку автоматически.",
            13f, Color.LTGRAY
        ).apply { setPadding(0, 0, 0, dp(14)) })

        if (!message.isNullOrBlank()) {
            root.addView(makeText(message, 12f, Color.rgb(255, 205, 145)).apply {
                setPadding(0, 0, 0, dp(12))
            })
        }

        addPermissionItem(
            root,
            "1. Показ поверх других приложений",
            "Нужно для плавающей кнопки NetPause.",
            hasOverlayPermission(),
            "ОТКРЫТЬ НАСТРОЙКУ"
        ) { openOverlayPermission() }

        addPermissionItem(
            root,
            "2. Разрешение VPN",
            "Нужно только для запуска блокировки. Само по себе согласие VPN не включает.",
            hasVpnPermission(),
            "ЗАПРОСИТЬ VPN"
        ) { requestVpnPermission() }

        addPermissionItem(
            root,
            "3. Уведомления",
            "Показывают, что фоновый сервис активен, и дают быстрый способ его остановить.",
            notificationsGranted(),
            "РАЗРЕШИТЬ УВЕДОМЛЕНИЯ"
        ) { requestNotificationPermission() }

        addPermissionItem(
            root,
            "4. Работа без ограничений батареи",
            "Снижает вероятность остановки фонового сервиса системой.",
            batteryUnrestricted(),
            "НАСТРОИТЬ БАТАРЕЮ"
        ) { requestBackgroundFreedom() }

        addPermissionItem(
            root,
            "5. Питание, производительность и автозапуск POCO",
            "Открой настройки NetPause. В разделе «Батарея» выбери «Нет ограничений», а в «Фоновом автозапуске» разреши запуск. Названия могут отличаться.",
            false,
            "ОТКРЫТЬ НАСТРОЙКИ POCO"
        ) { openPocoAppSettings() }

        root.addView(makeButton("ХОЧУ РАЗРЕШИТЬ  →") {
            permissionFlowRunning = true
            pendingPermissionStep = null
            continuePermissionFlow()
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })

        root.addView(makeText(
            "Кнопка откроет доступные системные экраны по очереди. Настройки автозапуска POCO подтверждаются вручную.",
            11f, Color.LTGRAY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, 0)
        })

        root.addView(makeButton("НАЗАД В NETPAUSE") {
            permissionFlowRunning = false
            pendingPermissionStep = null
            showDashboard()
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })

        setContentView(scroll)
    }

    private fun addPermissionItem(
        root: LinearLayout,
        title: String,
        description: String,
        granted: Boolean,
        actionText: String,
        action: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panelBackground()
        }
        val state = if (granted) "✓ РАЗРЕШЕНО" else "НУЖНО ПРОВЕРИТЬ"
        val stateColor = if (granted) Color.rgb(95, 225, 160) else Color.rgb(255, 195, 115)
        card.addView(makeText(title, 14f, Color.WHITE, true))
        card.addView(makeText(state, 10f, stateColor, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
        card.addView(makeText(description, 12f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(makeButton(actionText, action), LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun continuePermissionFlow() {
        if (!hasOverlayPermission()) {
            pendingPermissionStep = "overlay"
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                abortPermissionFlow("Не удалось открыть настройку отображения поверх окон.")
            }
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingPermissionStep = "vpn"
            startActivityForResult(vpnIntent, vpnRequestCode)
            return
        }

        if (!notificationsGranted() && Build.VERSION.SDK_INT >= 33) {
            pendingPermissionStep = "notifications"
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
            return
        }

        if (!batteryUnrestricted()) {
            pendingPermissionStep = "battery"
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                pendingPermissionStep = null
                completePermissionFlow()
            }
            return
        }

        completePermissionFlow()
    }

    private fun abortPermissionFlow(message: String) {
        permissionFlowRunning = false
        pendingPermissionStep = null
        showPermissionCenter(message)
    }

    private fun completePermissionFlow() {
        permissionFlowRunning = false
        pendingPermissionStep = null
        prefs.edit().putBoolean("permission_flow_completed", true).apply()
        showDashboard()
        Toast.makeText(this, "Проверка разрешений завершена. Плавающее окно запускается отдельно.", Toast.LENGTH_LONG).show()
    }

    private fun openOverlayPermission() {
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
        if (intent != null) startActivityForResult(intent, vpnRequestCode)
        else Toast.makeText(this, "VPN уже разрешён. Сам VPN сейчас не включён.", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
        } else {
            Toast.makeText(this, "Уведомления уже разрешены или не требуют отдельного запроса", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBackgroundFreedom() {
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

    private fun openPocoAppSettings() {
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
            if (resultCode == RESULT_OK) {
                pendingPermissionStep = null
                if (permissionFlowRunning) continuePermissionFlow() else showPermissionCenter()
            } else {
                abortPermissionFlow("Разрешение VPN не выдано. Без него блокировка не запустится.")
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
            if (permissionFlowRunning) continuePermissionFlow() else showPermissionCenter()
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

    private fun shutdownNetPause() {
        stopService(Intent(this, BlockVpnService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        prefs.edit()
            .putBoolean("overlay_enabled", false)
            .putLong("active_until", 0L)
            .apply()
        Toast.makeText(this, "NetPause полностью остановлен", Toast.LENGTH_LONG).show()
        showDashboard()
    }

    private fun showDashboard() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(26))
            background = pageBackground()
        }
        scroll.addView(root)

        root.addView(makeText("NETPAUSE", 29f, Color.rgb(95, 230, 245), true))
        root.addView(makeText("Управляй доступом к интернету", 15f, Color.LTGRAY).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        val allowedCount = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        val overlayRunning = prefs.getBoolean("overlay_enabled", false)
        val vpnUntil = prefs.getLong("active_until", 0L)
        val blockActive = vpnUntil > System.currentTimeMillis()
        val remaining = if (blockActive) ((vpnUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L) else 0L

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = panelBackground()
        }
        statusCard.addView(makeText("СОСТОЯНИЕ NETPAUSE", 11f, Color.rgb(130, 190, 225), true))
        statusCard.addView(makeText(
            if (blockActive) "Блокировка активна · осталось около $remaining мин."
            else "VPN-блокировка выключена",
            17f, if (blockActive) Color.rgb(100, 230, 160) else Color.WHITE, true
        ).apply { setPadding(0, dp(7), 0, dp(8)) })
        statusCard.addView(makeText(
            "Приложений с сохранённым интернетом: $allowedCount\n" +
                "Плавающая кнопка: " + if (overlayRunning) "запущена" else "выключена" +
                "\nОверлей: " + if (hasOverlayPermission()) "разрешён" else "не разрешён" +
                "\nVPN-доступ: " + if (hasVpnPermission()) "разрешён (не означает, что VPN включён)" else "не разрешён" +
                "\nБатарея: " + if (batteryUnrestricted()) "без оптимизации" else "ограничения могут мешать фону",
            12f, Color.LTGRAY
        ))
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        fun addSection(title: String, description: String, buttonText: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(15), dp(17), dp(15))
                background = panelBackground()
            }
            card.addView(makeText(title, 15f, Color.WHITE, true))
            card.addView(makeText(description, 12f, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(10))
            })
            card.addView(makeButton(buttonText, action), LinearLayout.LayoutParams(-1, dp(48)))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) })
        }

        addSection(
            "Приложения с интернетом",
            "Выбранные приложения останутся онлайн, остальные будут заблокированы. Список хранится постоянно и его можно менять в любое время.",
            "ПОИСК И ВЫБОР ПРИЛОЖЕНИЙ"
        ) { showAppPicker() }

        addSection(
            "Плавающее окно",
            if (overlayRunning) "Плавающая кнопка работает поверх приложений. VPN включается только после нажатия «Начать блокировку» внутри неё."
            else "Запускает маленькую кнопку поверх других приложений. Это не включает VPN.",
            if (overlayRunning) "СКРЫТЬ ПЛАВАЮЩУЮ КНОПКУ" else "ЗАПУСТИТЬ ПЛАВАЮЩУЮ КНОПКУ"
        ) {
            if (overlayRunning) stopOverlayService()
            else startOverlayService()
        }

        addSection(
            "Разрешения и производительность",
            "Открыть центр разрешений, параметры батареи и настройки фонового автозапуска POCO.",
            "ОТКРЫТЬ НАСТРОЙКИ"
        ) { showPermissionCenter() }

        root.addView(makeButton("ПОЛНОСТЬЮ ВЫКЛЮЧИТЬ NETPAUSE", {
            shutdownNetPause()
        }), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(2) })

        root.addView(makeText("NetPause 0.4 · Таймер до 120 минут · До 3 запусков в день", 11f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll)
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

        root.addView(makeText("Выбрано: ${selected.size}", 12f, Color.rgb(170, 210, 235), true).apply {
            setPadding(0, dp(8), 0, dp(4))
            tag = "selected_count"
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
        setContentView(root)
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
