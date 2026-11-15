package ru.netpause.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity

class MainActivity : Activity() {
    private val vpnRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this) && VpnService.prepare(this) == null) {
            startOverlay()
        }
    }

    private fun showDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 56, 28, 28)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 17, 35), Color.rgb(27, 39, 72))
            )
        }
        val title = TextView(this).apply {
            text = "NETPAUSE"
            textSize = 30f
            setTextColor(Color.rgb(90, 225, 240))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Тишина для важных дел."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 34)
        }
        val card = TextView(this).apply {
            text = "Плавающая кнопка появится поверх других приложений. Нажми её, выбери время и включи блокировку. После запуска окно свернётся в маленький значок."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 22, 20, 22)
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 47, 79))
                cornerRadius = 24f
                setStroke(2, Color.rgb(65, 100, 160))
            }
        }
        val enable = Button(this).apply {
            text = "ВКЛЮЧИТЬ ПЛАВАЮЩУЮ КНОПКУ"
            isAllCaps = false
            setOnClickListener { preparePermissions() }
        }
        val stop = Button(this).apply {
            text = "СНЯТЬ БЛОКИРОВКУ"
            isAllCaps = false
            setOnClickListener {
                stopService(Intent(this@MainActivity, BlockVpnService::class.java))
                Toast.makeText(this@MainActivity, "Блокировка остановлена", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(card)
        root.addView(enable, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 28 })
        root.addView(stop, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 10 })
        setContentView(root)
    }

    private fun preparePermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Разреши NetPause показываться поверх других приложений", Toast.LENGTH_LONG).show()
            return
        }
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) startActivityForResult(vpnIntent, vpnRequestCode)
        else startOverlay()
    }

    @Deprecated("Use Activity Result API in a production app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) startOverlay()
            else Toast.makeText(this, "Разрешение VPN не выдано", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlay() {
        val i = Intent(this, OverlayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Плавающая кнопка NetPause включена", Toast.LENGTH_SHORT).show()
        finish()
    }
}
