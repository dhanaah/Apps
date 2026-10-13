package com.aisglass.transporter

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Launcher screen: one tap on UPDATE lists every in-transit vehicle of this
 * transporter with its last known location; tapping a vehicle opens the
 * update screen for it. Vehicles that need a location come first.
 */
class VehicleListActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtTrackingNow: TextView
    private lateinit var txtNotConfigured: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicles)
        prefs = Prefs(this)
        list = findViewById(R.id.list); txtStatus = findViewById(R.id.txtStatus)
        txtTrackingNow = findViewById(R.id.txtTrackingNow); txtNotConfigured = findViewById(R.id.txtNotConfigured)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.btnManual).setOnClickListener {
            val v = findViewById<EditText>(R.id.edManual).text.toString().trim().uppercase()
            if (v.isBlank()) Toast.makeText(this, "Enter the vehicle number", Toast.LENGTH_SHORT).show() else open(v)
        }
        txtTrackingNow.setOnClickListener { if (prefs.tracking) open(prefs.vehicleNo) }
    }

    override fun onResume() {
        super.onResume()
        txtNotConfigured.visibility = if (prefs.isConfigured) TextView.GONE else TextView.VISIBLE
        if (prefs.tracking) {
            txtTrackingNow.visibility = TextView.VISIBLE
            txtTrackingNow.text = "● Tracking ${prefs.vehicleNo} - tap to open"
        } else txtTrackingNow.visibility = TextView.GONE
        if (prefs.isConfigured && list.childCount == 0) load()
    }

    private fun open(vehicle: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("vehicle", vehicle))
    }

    private fun load() {
        if (!prefs.isConfigured) { Toast.makeText(this, "Set server and API key in Settings first", Toast.LENGTH_SHORT).show(); return }
        txtStatus.text = "Loading your vehicles..."
        Thread {
            val r = Api(prefs.serverUrl, prefs.apiKey).vehicles()
            runOnUiThread { render(r) }
        }.start()
    }

    private fun render(r: ApiResult) {
        list.removeAllViews()
        if (!r.ok) {
            txtStatus.text = r.message()
            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.red))
            return
        }
        txtStatus.setTextColor(ContextCompat.getColor(this, R.color.muted))
        val arr = r.json!!.optJSONArray("vehicles")
        val n = arr?.length() ?: 0
        if (n == 0) { txtStatus.text = "No vehicles in transit for ${r.json.optString("transporter")} right now."; return }
        val need = (0 until n).count { arr!!.getJSONObject(it).optBoolean("location_update_allowed") }
        txtStatus.text = "${r.json.optString("transporter")}: $n vehicle(s) in transit, $need need a location update. Tap a vehicle."
        val inf = LayoutInflater.from(this)
        for (i in 0 until n) {
            val v = arr!!.getJSONObject(i)
            val card = inf.inflate(R.layout.item_vehicle, list, false)
            bind(card, v)
            card.setOnClickListener { open(v.optString("vehicle_no")) }
            list.addView(card)
        }
    }

    private fun bind(card: android.view.View, v: JSONObject) {
        val allowed = v.optBoolean("location_update_allowed")
        val reached = v.optBoolean("reached")
        val gps = v.optBoolean("gps_active")
        card.findViewById<TextView>(R.id.txtVehicle).text = v.optString("vehicle_no")
        val badge = card.findViewById<TextView>(R.id.txtBadge)
        badge.text = v.optString("status")
        badge.setBackgroundColor(ContextCompat.getColor(this, when {
            reached -> R.color.muted
            gps -> R.color.green
            allowed -> R.color.amber
            else -> R.color.muted
        }))
        card.findViewById<TextView>(R.id.txtRoute).text = "${v.optString("plant")} → ${v.optString("customer")}, ${v.optString("destination")}"
        val eta = v.optString("eta")
        card.findViewById<TextView>(R.id.txtInfo).text = "Gate pass ${v.optString("gate_pass")} · Dispatched ${v.optString("dispatched")}" +
            (if (eta.isNotBlank()) "\nETA $eta" else "") +
            (if (v.optDouble("remaining_km", 0.0) > 0) " · ${v.opt("remaining_km")} km remaining" else "")
        val place = v.optString("current_place"); val last = v.optString("last_update")
        card.findViewById<TextView>(R.id.txtLast).text = if (place.isBlank()) "No location yet" else "Last: $place" + (if (last.isNotBlank()) "  ($last)" else "")
        val act = card.findViewById<TextView>(R.id.txtAction)
        when {
            allowed -> { act.text = "▶ Tap to update location"; act.setTextColor(ContextCompat.getColor(this, R.color.amber)) }
            reached -> { act.text = "✓ Reached customer"; act.setTextColor(ContextCompat.getColor(this, R.color.muted)) }
            else -> { act.text = "✓ GPS active - no update needed"; act.setTextColor(ContextCompat.getColor(this, R.color.green)) }
        }
    }
}
