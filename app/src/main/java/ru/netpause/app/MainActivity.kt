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
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
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

        if (hasCorePermissions()) showDashboard() else showPermissionCenter()
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
        } else if (hasCorePermissions()) {
            if (prefs.getBoolean("overlay_enabled", false)) startOverlayService()
            showDashboard()
        } else {
            showPermissionCenter()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(14, 23, 45), Color.rgb(29, 43, 76))
    ).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), Color.rgb(55, 92, 145))
    }

    private fun pageBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(8, 15, 31), Color.rgb(25, 38, 70))
    )

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

    private fun hasCorePermissions(): Boolean =
        Settings.canDrawOverlays(this) && VpnService.prepare(this) == null

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
        root.addView(makeText("Сначала подготовим приложение", 19f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        root.addView(makeText(
            "Для плавающей кнопки и блокировки по таймеру Android должен разрешить несколько отдельных действий. Нажми «ХОЧУ РАЗРЕШИТЬ» — NetPause будет открывать нужные экраны по очереди.",
            13f, Color.LTGRAY
        ).apply { setPadding(0, 0, 0, dp(14)) })

        if (!message.isNullOrBlank()) {
            root.addView(makeText(message, 12f, Color.rgb(255, 205, 145)).apply {
                setPadding(0, 0, 0, dp(12))
            })
        }

        addPermissionItem(
            root,
            "1. Отображение поверх приложений",
            "Нужно для маленькой плавающей кнопки NetPause.",
            Settings.canDrawOverlays(this),
            "ОТКРЫТЬ НАСТРОЙКУ"
        ) { openOverlayPermission() }

        addPermissionItem(
            root,
            "2. Разрешение VPN",
            "Android покажет системное подтверждение. Оно нужно, чтобы выборочно направлять трафик приложений через блокировщик.",
            VpnService.prepare(this) == null,
            "ЗАПРОСИТЬ VPN"
        ) { requestVpnPermission() }

        addPermissionItem(
            root,
            "3. Уведомления",
            "Нужны для постоянного уведомления о работающем сервисе и его состоянии.",
            notificationsGranted(),
            "РАЗРЕШИТЬ УВЕДОМЛЕНИЯ"
        ) { requestNotificationPermission() }

        addPermissionItem(
            root,
            "4. Работа без ограничений батареи",
            "Помогает системе реже останавливать фоновую работу. На POCO отдельно проверь автозапуск и режим «Нет ограничений».",
            batteryUnrestricted(),
            "НАСТРОИТЬ БАТАРЕЮ"
        ) { requestBackgroundFreedom() }

        root.addView(makeButton("ХОЧУ РАЗРЕШИТЬ  →") {
            permissionFlowRunning = true
            pendingPermissionStep = null
            continuePermissionFlow()
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })

        root.addView(makeText(
            "Разрешения выдаются только в системных настройках Android. NetPause не может включить их самостоятельно.",
            11f, Color.LTGRAY
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(13), 0, 0)
        })

        if (hasCorePermissions()) {
            root.addView(makeButton("ПРОДОЛЖИТЬ В NETPAUSE") {
                showDashboard()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        }
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
        val state = if (granted) "✓ РАЗРЕШЕНО" else "НУЖНО РАЗРЕШИТЬ"
        val stateColor = if (granted) Color.rgb(95, 225, 160) else Color.rgb(255, 195, 115)
        card.addView(makeText(title, 14f, Color.WHITE, true))
        card.addView(makeText(state, 10f, stateColor, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
        card.addView(makeText(description, 12f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, dp(8))
        })
        val button = makeButton(if (granted) "НАСТРОЙКА ОТКРЫТА ✓" else actionText, action)
        button.isEnabled = !granted
        card.addView(button, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun continuePermissionFlow() {
        if (!Settings.canDrawOverlays(this)) {
            pendingPermissionStep = "overlay"
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                abortPermissionFlow("Не удалось открыть системную настройку отображения поверх окон.")
            }
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingPermissionStep = "vpn"
            startActivityForResult(vpnIntent, vpnRequestCode)
            return
        }

        if (!notificationsGranted()) {
            if (Build.VERSION.SDK_INT >= 33) {
                pendingPermissionStep = "notifications"
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequestCode)
                return
            }
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
        if (hasCorePermissions()) {
            prefs.edit().putBoolean("overlay_enabled", true).apply()
            startOverlayService()
            showDashboard()
            Toast.makeText(this, "Основные разрешения настроены", Toast.LENGTH_SHORT).show()
        } else {
            showPermissionCenter("Для запуска плавающей кнопки ещё нужны два основных разрешения: отображение поверх окон и VPN.")
        }
    }

    private fun openOverlayPermission() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть настройки поверх других приложений", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, vpnRequestCode)
        else Toast.makeText(this, "VPN уже разрешён", Toast.LENGTH_SHORT).show()
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
                // Open general app settings if the direct exemption screen is unavailable.
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "Открой настройки NetPause и выбери режим батареи «Нет ограничений».", Toast.LENGTH_LONG).show()
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
                abortPermissionFlow("Разрешение VPN не выдано. Без него блокировка интернета работать не сможет.")
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
        if (!Settings.canDrawOverlays(this) || VpnService.prepare(this) != null) return
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось запустить плавающую кнопку. Проверь настройки фона.", Toast.LENGTH_LONG).show()
        }
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
        val changedToday = prefs.getString("allowlist_changed_day", "") == todayKey()
        val until = prefs.getLong("active_until", 0L)
        val blockText = if (until > System.currentTimeMillis()) {
            val remaining = ((until - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
            "Блокировка активна · осталось примерно $remaining мин."
        } else "Блокировка сейчас выключена"

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = panelBackground()
        }
        statusCard.addView(makeText("СОСТОЯНИЕ", 11f, Color.rgb(130, 190, 225), true))
        statusCard.addView(makeText(blockText, 17f, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(8))
        })
        statusCard.addView(makeText(
            "Интернет сохраняют: $allowedCount приложений\n" +
                "Плавающее окно: " + if (Settings.canDrawOverlays(this)) "разрешено" else "не разрешено" +
                "\nVPN: " + if (VpnService.prepare(this) == null) "разрешён" else "не разрешён" +
                "\nРабота без ограничений батареи: " + if (batteryUnrestricted()) "разрешена" else "не включена",
            13f, Color.LTGRAY
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
            if (changedToday) "Сегодня список уже сохранён. Изменить его можно после полуночи. Выбранные приложения останутся онлайн, остальные будут заблокированы."
            else "Выбери приложения-исключения. Только они сохранят интернет во время блокировки. Менять список можно один раз в день.",
            "ВЫБРАТЬ ПРИЛОЖЕНИЯ"
        ) { showAppPicker() }

        addSection(
            "Плавающее окно",
            "Круглая кнопка будет поверх других приложений. Нажми её, укажи время до 120 минут и запусти блокировку.",
            "ПОКАЗАТЬ ПЛАВАЮЩУЮ КНОПКУ"
        ) {
            if (!hasCorePermissions()) showPermissionCenter()
            else {
                prefs.edit().putBoolean("overlay_enabled", true).apply()
                startOverlayService()
                Toast.makeText(this, "Плавающая кнопка включена", Toast.LENGTH_SHORT).show()
            }
        }

        addSection(
            "Разрешения и фон",
            "Проверка всех системных разрешений, уведомлений и ограничений батареи.",
            "ПРОВЕРИТЬ РАЗРЕШЕНИЯ"
        ) { showPermissionCenter() }

        root.addView(makeButton("ОСТАНОВИТЬ БЛОКИРОВКУ", {
            stopService(Intent(this, BlockVpnService::class.java))
            prefs.edit().putLong("active_until", 0L).apply()
            Toast.makeText(this, "Блокировка остановлена", Toast.LENGTH_SHORT).show()
            showDashboard()
        }), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(2) })

        root.addView(makeText("NetPause 0.3 · Максимум 2 часа · До 3 запусков в день", 11f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll)
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

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
        val locked = prefs.getString("allowlist_changed_day", "") == todayKey()
        val selected = (prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()).toMutableSet()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(14))
            background = pageBackground()
        }
        root.addView(makeText("ПРИЛОЖЕНИЯ С ИНТЕРНЕТОМ", 21f, Color.rgb(95, 230, 245), true))
        root.addView(makeText(
            if (locked) "Список зафиксирован до следующего дня. Выбранные приложения будут работать, остальные — без интернета."
            else "Отметь приложения, которым нужно оставить интернет во время блокировки. Список можно сохранить только один раз в день.",
            13f, Color.LTGRAY
        ).apply { setPadding(0, dp(8), 0, dp(12)) })

        val appScroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apps = launcherApps()
        if (apps.isEmpty()) {
            rows.addView(makeText("Не удалось получить список приложений.", 14f, Color.WHITE))
        }
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
                isEnabled = !locked
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 215, 230))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, dp(48), 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        appScroll.addView(rows)
        root.addView(appScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val save = makeButton(
            if (locked) "СПИСОК СОХРАНЁН ДО ЗАВТРА" else "СОХРАНИТЬ СПИСОК НА СЕГОДНЯ"
        ) {
            prefs.edit()
                .putStringSet("allowed_apps", selected.toSet())
                .putString("allowlist_changed_day", todayKey())
                .apply()
            reconfigureActiveVpn()
            Toast.makeText(this, "Список сохранён. Изменить его можно завтра.", Toast.LENGTH_LONG).show()
            showDashboard()
        }
        save.isEnabled = !locked
        root.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        root.addView(makeButton("НАЗАД") { showDashboard() }, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(8)
        })
        setContentView(root)
    }

    private fun reconfigureActiveVpn() {
        val until = prefs.getLong("active_until", 0L)
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0L && VpnService.prepare(this) == null) {
            val vpn = Intent(this, BlockVpnService::class.java)
                .putExtra(BlockVpnService.EXTRA_DURATION_MS, remaining)
                .putExtra(BlockVpnService.EXTRA_RECONFIGURE, true)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn) else startService(vpn)
            } catch (_: Exception) {
                Toast.makeText(this, "Новые правила применятся при следующем запуске блокировки.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
