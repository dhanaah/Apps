package com.aisglass.transporter

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var edServer: EditText
    private lateinit var edKey: EditText
    private lateinit var txtTest: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Settings"
        prefs = Prefs(this)
        edServer = findViewById(R.id.edServer); edKey = findViewById(R.id.edKey); txtTest = findViewById(R.id.txtTest)
        edServer.setText(prefs.serverUrl); edKey.setText(prefs.apiKey)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { save(); test() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        refreshBatteryButton()
    }

    private fun save() {
        var url = edServer.text.toString().trim()
        if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        prefs.serverUrl = url
        prefs.apiKey = edKey.text.toString()
        edServer.setText(prefs.serverUrl)
    }

    private fun test() {
        txtTest.text = "Testing..."
        txtTest.setTextColor(ContextCompat.getColor(this, R.color.muted))
        Thread {
            val r = Api(prefs.serverUrl, prefs.apiKey).ping()
            runOnUiThread {
                if (r.ok) {
                    txtTest.text = "✓ Connected as ${r.json!!.optString("transporter")}  (server time ${r.json.optString("server_time")})"
                    txtTest.setTextColor(ContextCompat.getColor(this, R.color.green))
                } else {
                    txtTest.text = "✗ " + r.message()
                    txtTest.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
            }
        }.start()
    }

    private fun isBatteryExempt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryButton() {
        val b = findViewById<Button>(R.id.btnBattery)
        if (isBatteryExempt()) { b.text = "✓ Background running allowed"; b.isEnabled = false }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
        }
    }

    override fun onResume() { super.onResume(); refreshBatteryButton() }
}
