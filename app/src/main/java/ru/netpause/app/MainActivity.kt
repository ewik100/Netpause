package ru.netpause.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var minutes: EditText
    private val vpnRequestCode = 4102
    private var requestedMinutes = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }

        val title = TextView(this).apply {
            text = "NetPause"
            textSize = 28f
        }
        status = TextView(this).apply {
            text = "Интернет: не заблокирован"
            textSize = 18f
            setPadding(0, 28, 0, 24)
        }
        minutes = EditText(this).apply {
            hint = "Время в минутах"
            setText("30")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val start = Button(this).apply {
            text = "ЗАБЛОКИРОВАТЬ ИНТЕРНЕТ"
            setOnClickListener { requestBlock() }
        }
        val stop = Button(this).apply {
            text = "СНЯТЬ БЛОКИРОВКУ"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BlockVpnService::class.java))
                status.text = "Интернет: блокировка снята"
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(minutes)
        layout.addView(start)
        layout.addView(stop)
        setContentView(layout)
    }

    private fun requestBlock() {
        val value = minutes.text.toString().toIntOrNull()
        if (value == null || value !in 1..1440) {
            Toast.makeText(this, "Укажи время от 1 до 1440 минут", Toast.LENGTH_SHORT).show()
            return
        }
        requestedMinutes = value
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, vpnRequestCode)
        } else {
            startBlockService()
        }
    }

    @Deprecated("Use Activity Result API in a production app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) startBlockService()
            else Toast.makeText(this, "Разрешение VPN не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBlockService() {
        val intent = Intent(this, BlockVpnService::class.java)
            .putExtra(BlockVpnService.EXTRA_DURATION_MS, requestedMinutes * 60_000L)
        startForegroundService(intent)
        status.text = "Интернет: заблокирован на $requestedMinutes мин."
    }
}
