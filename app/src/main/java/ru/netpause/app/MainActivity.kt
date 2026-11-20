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
    private val prefs by lazy { getSharedPreferences("netpause_state", MODE_PRIVATE) }
    private var continueAfterOverlay = false

    private data class LaunchApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 17, 35)
        window.navigationBarColor = Color.rgb(10, 17, 35)
        showDashboard()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4103)
        }
    }

    override fun onResume() {
        super.onResume()
        if (continueAfterOverlay) {
            if (Settings.canDrawOverlays(this)) {
                continueAfterOverlay = false
                requestVpnAndStart()
            }
        } else if (
            prefs.getBoolean("overlay_enabled", false) &&
            Settings.canDrawOverlays(this) &&
            VpnService.prepare(this) == null
        ) {
            launchOverlay(false)
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

    private fun showDashboard() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(26))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 15, 31), Color.rgb(25, 38, 70))
            )
        }
        scroll.addView(root)

        root.addView(makeText("NETPAUSE", 29f, Color.rgb(95, 230, 245), true))
        root.addView(makeText("Управляй доступом к интернету", 15f, Color.LTGRAY).apply {
            setPadding(0, dp(5), 0, dp(18))
        })

        val allowedCount = prefs.getStringSet("allowed_apps", emptySet())?.size ?: 0
        val changedToday = prefs.getString("allowlist_changed_day", "") == todayKey()
        val overlayReady = Settings.canDrawOverlays(this)
        val vpnReady = VpnService.prepare(this) == null
        val batteryFree = if (Build.VERSION.SDK_INT >= 23) {
            getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        } else true
        val until = prefs.getLong("active_until", 0L)
        val blockText = if (until > System.currentTimeMillis()) {
            val remaining = (until - System.currentTimeMillis()) / 60000L
            "Блокировка активна · осталось примерно $remaining мин."
        } else "Блокировка сейчас выключена"

        val overlayState = if (overlayReady && prefs.getBoolean("overlay_enabled", false)) "разрешено" else "не включено"
        val vpnState = if (vpnReady) "разрешён" else "нужно разрешение"
        val batteryState = if (batteryFree) "без оптимизации батареи" else "ограничивается батареей"

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
                "Плавающее окно: $overlayState\nVPN: $vpnState\nРабота в фоне: $batteryState",
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
            "ВКЛЮЧИТЬ ПЛАВАЮЩУЮ КНОПКУ"
        ) { preparePermissions() }

        addSection(
            "Работа в фоне",
            "Разреши NetPause работу без ограничений батареи. На POCO дополнительно включи автозапуск и режим «Нет ограничений» в настройках батареи.",
            if (batteryFree) "ОТКРЫТЬ НАСТРОЙКИ ПРИЛОЖЕНИЯ" else "РАЗРЕШИТЬ РАБОТУ В ФОНЕ"
        ) { requestBackgroundFreedom() }

        root.addView(makeButton("ОСТАНОВИТЬ БЛОКИРОВКУ", {
            stopService(Intent(this, BlockVpnService::class.java))
            prefs.edit().putLong("active_until", 0L).apply()
            Toast.makeText(this, "Блокировка остановлена", Toast.LENGTH_SHORT).show()
            showDashboard()
        }), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(2) })

        root.addView(makeText("NetPause 0.2 · Максимум 2 часа · До 3 запусков в день", 11f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll)
    }

    private fun preparePermissions() {
        if (!Settings.canDrawOverlays(this)) {
            continueAfterOverlay = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Разреши NetPause показываться поверх других приложений", Toast.LENGTH_LONG).show()
            return
        }
        requestVpnAndStart()
    }

    private fun requestVpnAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) startActivityForResult(vpnIntent, vpnRequestCode)
        else launchOverlay(true)
    }

    @Deprecated("Use Activity Result API in a production app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) launchOverlay(true)
            else Toast.makeText(this, "Разрешение VPN не выдано", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchOverlay(closeActivity: Boolean) {
        prefs.edit().putBoolean("overlay_enabled", true).apply()
        val intent = Intent(this, OverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Плавающая кнопка NetPause включена", Toast.LENGTH_SHORT).show()
            if (closeActivity) finish()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось запустить плавающее окно. Проверь разрешения.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBackgroundFreedom() {
        if (Build.VERSION.SDK_INT >= 23 &&
            !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {
                // Fall back to the system battery-optimization settings.
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "Открой настройки приложения и выбери батарею: «Нет ограничений».", Toast.LENGTH_LONG).show()
        }
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8, 15, 31), Color.rgb(25, 38, 70))
            )
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
            stopService(Intent(this, BlockVpnService::class.java))
            val vpn = Intent(this, BlockVpnService::class.java)
                .putExtra(BlockVpnService.EXTRA_DURATION_MS, remaining)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn) else startService(vpn)
            } catch (_: Exception) {
                Toast.makeText(this, "Новые правила вступят в силу при следующем запуске.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
