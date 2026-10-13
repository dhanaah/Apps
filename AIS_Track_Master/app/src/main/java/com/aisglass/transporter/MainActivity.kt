package com.aisglass.transporter

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var edVehicle: EditText
    private lateinit var edDriver: EditText
    private lateinit var edMobile: EditText
    private lateinit var edPlace: EditText
    private lateinit var txtCheck: TextView
    private lateinit var txtTrip: TextView
    private lateinit var txtTracking: TextView
    private lateinit var txtSend: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtNotConfigured: TextView
    private lateinit var btnTrack: Button
    private lateinit var spInterval: Spinner

    private val intervals = listOf(5, 10, 15, 30, 60)
    private var pendingAction: (() -> Unit)? = null
    private val REQ_PERMS = 41

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("line") ?: return
            val running = intent.getBooleanExtra("running", false)
            txtTracking.text = line
            refreshTrackButton(running)
            refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        edVehicle = findViewById(R.id.edVehicle); edDriver = findViewById(R.id.edDriver)
        edMobile = findViewById(R.id.edMobile); edPlace = findViewById(R.id.edPlace)
        txtCheck = findViewById(R.id.txtCheck); txtTrip = findViewById(R.id.txtTrip)
        txtTracking = findViewById(R.id.txtTracking); txtSend = findViewById(R.id.txtSend)
        txtLog = findViewById(R.id.txtLog); txtNotConfigured = findViewById(R.id.txtNotConfigured)
        btnTrack = findViewById(R.id.btnTrack); spInterval = findViewById(R.id.spInterval)

        spInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            intervals.map { if (it >= 60) "${it / 60} hour" else "$it minutes" })
        spInterval.setSelection(intervals.indexOf(prefs.intervalMins).coerceAtLeast(0))

        val fromList = intent.getStringExtra("vehicle") ?: ""
        edVehicle.setText(if (fromList.isNotBlank()) fromList else prefs.vehicleNo)
        edDriver.setText(prefs.driverName); edMobile.setText(prefs.driverMobile)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkVehicle() }
        findViewById<Button>(R.id.btnSendNow).setOnClickListener { withPermissions { sendNowOnce() } }
        findViewById<Button>(R.id.btnPlace).setOnClickListener { sendPlace() }
        findViewById<TextView>(R.id.btnClearLog).setOnClickListener { LogStore.clear(this); refreshLog() }
        btnTrack.setOnClickListener {
            if (prefs.tracking) { TrackingService.stop(this) }
            else withPermissions { startTracking() }
        }
    }

    private var autoChecked = false

    override fun onResume() {
        super.onResume()
        txtNotConfigured.visibility = if (prefs.isConfigured) TextView.GONE else TextView.VISIBLE
        if (!autoChecked && prefs.isConfigured && vehicle().isNotBlank()) { autoChecked = true; checkVehicle() }
        refreshTrackButton(prefs.tracking)
        if (prefs.tracking || prefs.trackingSummary.isNotBlank()) txtTracking.text = prefs.trackingSummary
        refreshLog()
        val f = IntentFilter(TrackingService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
    }

    override fun onPause() {
        super.onPause()
        saveFields()
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    private fun saveFields() {
        prefs.vehicleNo = edVehicle.text.toString()
        prefs.driverName = edDriver.text.toString()
        prefs.driverMobile = edMobile.text.toString()
        prefs.intervalMins = intervals[spInterval.selectedItemPosition.coerceIn(0, intervals.size - 1)]
    }

    private fun vehicle(): String = edVehicle.text.toString().trim().uppercase()

    private fun refreshTrackButton(running: Boolean) {
        if (running) {
            btnTrack.text = "■  STOP TRACKING ${prefs.vehicleNo}"
            btnTrack.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
            edVehicle.isEnabled = false
        } else {
            btnTrack.text = "▶  START TRACKING"
            btnTrack.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            edVehicle.isEnabled = true
        }
    }

    private fun refreshLog() {
        val lines = LogStore.lines(this)
        txtLog.text = if (lines.isEmpty()) "Nothing sent yet" else lines.joinToString("\n")
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private fun checkVehicle() {
        if (!prefs.isConfigured) { toast("Set server and API key in Settings first"); return }
        val v = vehicle()
        if (v.isBlank()) { txtCheck.text = "Enter the vehicle number"; return }
        saveFields()
        txtCheck.text = "Checking..."; txtTrip.visibility = TextView.GONE
        Thread {
            val r = Api(prefs.serverUrl, prefs.apiKey).vehicle(v)
            runOnUiThread {
                if (r.ok) {
                    val t = r.json!!.optJSONObject("trip") ?: JSONObject()
                    txtTrip.text = "${t.optString("vehicle_no")}  ·  Gate pass ${t.optString("gate_pass")}\n" +
                        "${t.optString("plant")} → ${t.optString("customer")}, ${t.optString("destination")}\n" +
                        "Dispatched: ${t.optString("dispatched")}\n" +
                        "Status: ${t.optString("status")}" + (if (t.optString("eta").isNotBlank()) "  ·  ETA ${t.optString("eta")}" else "") +
                        (if (t.optString("current_place").isNotBlank()) "\nLast known: ${t.optString("current_place")}" else "")
                    txtTrip.visibility = TextView.VISIBLE
                    val allowed = r.json.optBoolean("location_update_allowed", false)
                    txtCheck.text = if (allowed) "✓ Vehicle in transit - you can start tracking" else r.json.optString("note", "Location update not needed")
                    txtCheck.setTextColor(ContextCompat.getColor(this, if (allowed) R.color.green else R.color.amber))
                } else {
                    txtCheck.text = r.message()
                    txtCheck.setTextColor(ContextCompat.getColor(this, R.color.red))
                }
            }
        }.start()
    }

    private fun startTracking() {
        if (!prefs.isConfigured) { toast("Set server and API key in Settings first"); return }
        val v = vehicle()
        if (v.isBlank()) { toast("Enter the vehicle number"); return }
        saveFields()
        prefs.trackingSummary = "Starting - first position will be sent in a few seconds"
        txtTracking.text = prefs.trackingSummary
        TrackingService.start(this, v)
        refreshTrackButton(true)
    }

    private fun sendNowOnce() {
        if (prefs.tracking) { TrackingService.sendNow(this); txtSend.text = "Sending current position..."; return }
        if (!prefs.isConfigured) { toast("Set server and API key in Settings first"); return }
        val v = vehicle()
        if (v.isBlank()) { toast("Enter the vehicle number"); return }
        saveFields()
        txtSend.text = "Getting phone GPS position..."
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val fused = LocationServices.getFusedLocationProviderClient(this)
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) { txtSend.text = "No GPS fix - switch on location, or send a place name"; return@addOnSuccessListener }
                pushOnce(v, loc.latitude, loc.longitude, edPlace.text.toString().trim())
            }
            .addOnFailureListener { txtSend.text = "GPS error: ${it.message}" }
    }

    private fun sendPlace() {
        if (!prefs.isConfigured) { toast("Set server and API key in Settings first"); return }
        val v = vehicle(); val place = edPlace.text.toString().trim()
        if (v.isBlank()) { toast("Enter the vehicle number"); return }
        if (place.isBlank()) { txtSend.text = "Type the place name first"; return }
        saveFields()
        txtSend.text = "Sending place name..."
        pushOnce(v, null, null, place)
    }

    private fun pushOnce(v: String, lat: Double?, lon: Double?, place: String) {
        Thread {
            val r = Api(prefs.serverUrl, prefs.apiKey).pushLocation(v, lat, lon, place, prefs.driverName, prefs.driverMobile, "")
            runOnUiThread {
                if (r.ok) {
                    val j = r.json!!
                    val msg = if (j.optString("result") == "pending") "Received ✓ - awaiting AIS confirmation"
                              else "Accepted ✓  ${j.optString("place")}\n${j.optString("status")}  ·  remaining ${j.opt("remaining_km")} km  ·  ETA ${j.optString("eta")}"
                    txtSend.text = msg; txtSend.setTextColor(ContextCompat.getColor(this, R.color.green))
                    LogStore.add(this, v, j.optString("place", place), j.optString("status", j.optString("result")), true)
                } else {
                    txtSend.text = r.message(); txtSend.setTextColor(ContextCompat.getColor(this, R.color.red))
                    LogStore.add(this, v, place.ifBlank { if (lat != null) "%.4f,%.4f".format(lat, lon) else "" }, r.message(), false)
                }
                refreshLog()
            }
        }.start()
    }

    // ── permissions ──────────────────────────────────────────────────────────

    private fun withPermissions(action: () -> Unit) {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) { action(); return }
        pendingAction = action
        ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (locOk) { pendingAction?.invoke() } else toast("Location permission is required to send the vehicle position")
        pendingAction = null
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
