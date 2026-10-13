package com.aisglass.transporter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service: every N minutes gets a fresh GPS fix from the phone
 * and pushes it to the AIS tracker for the active vehicle. Keeps running
 * with the screen off until the driver stops it, the vehicle is marked
 * REACHED by AIS, or the gate pass is closed.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.aisglass.transporter.START"
        const val ACTION_STOP = "com.aisglass.transporter.STOP"
        const val ACTION_SEND_NOW = "com.aisglass.transporter.SEND_NOW"
        const val BROADCAST_UPDATE = "com.aisglass.transporter.UPDATE"
        const val EXTRA_VEHICLE = "vehicle"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 1001
        private const val DONE_NOTIF_ID = 1002

        fun start(ctx: Context, vehicle: String) {
            val i = Intent(ctx, TrackingService::class.java).setAction(ACTION_START).putExtra(EXTRA_VEHICLE, vehicle)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_STOP))
        }
        fun sendNow(ctx: Context) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(ACTION_SEND_NOW))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle = ""
    private var running = false
    private var lastLine = "Starting..."
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            captureAndSend()
            handler.postDelayed(this, prefs.intervalMins.coerceAtLeast(1) * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish("Tracking stopped by driver", false); return START_NOT_STICKY }
            ACTION_SEND_NOW -> { if (running) captureAndSend() else stopSelf(); return START_STICKY }
            else -> {
                vehicle = intent?.getStringExtra(EXTRA_VEHICLE) ?: prefs.vehicleNo
                if (vehicle.isBlank()) { stopSelf(); return START_NOT_STICKY }
                startForegroundCompat(buildNotification("Tracking $vehicle", "Waiting for first GPS fix..."))
                if (!running) {
                    running = true
                    prefs.tracking = true
                    prefs.vehicleNo = vehicle
                    acquireWakeLock()
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
                return START_STICKY
            }
        }
    }

    private fun captureAndSend() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            update("Location permission missing", false); return
        }
        val cts = CancellationTokenSource()
        // a fresh fix (not a cached one) - on a moving truck a stale point is useless
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    // fall back to the last known position rather than skipping the cycle
                    fused.lastLocation.addOnSuccessListener { last ->
                        if (last != null) push(last.latitude, last.longitude) else update("No GPS fix - is location ON?", false)
                    }
                } else push(loc.latitude, loc.longitude)
            }
            .addOnFailureListener { update("GPS error: ${it.message}", false) }
        // safety: cancel the request if it hangs
        handler.postDelayed({ cts.cancel() }, 45_000L)
    }

    private fun push(lat: Double, lon: Double) {
        Thread {
            val api = Api(prefs.serverUrl, prefs.apiKey)
            val r = api.pushLocation(vehicle, lat, lon, "", prefs.driverName, prefs.driverMobile, "")
            val now = timeFmt.format(Date())
            when {
                r.ok -> {
                    val j = r.json!!
                    val place = j.optString("place", "")
                    val status = j.optString("status", j.optString("result", ""))
                    val eta = j.optString("eta", "")
                    val reached = j.optBoolean("reached", false) || status.startsWith("REACHED")
                    LogStore.add(this, vehicle, place, if (j.optString("result") == "pending") "Received (awaiting AIS confirmation)" else "$status${if (eta.isNotBlank()) " ETA $eta" else ""}", true)
                    if (reached) {
                        finish("$vehicle has REACHED the customer. Tracking finished.", true)
                    } else {
                        update("$now sent: $place  ($status${if (eta.isNotBlank()) ", ETA $eta" else ""})", true)
                    }
                }
                r.code == 410 || r.code == 404 -> {
                    // reached already / gate pass closed -> nothing more to send
                    LogStore.add(this, vehicle, "", r.message(), false)
                    finish("${r.message()} - tracking finished.", true)
                }
                r.code == 429 -> update("$now skipped: ${r.message()}", true)   // too soon - not an error
                else -> {
                    LogStore.add(this, vehicle, "%.4f,%.4f".format(lat, lon), r.message(), false)
                    update("$now failed: ${r.message()}", false)
                }
            }
        }.start()
    }

    private fun update(line: String, ok: Boolean) {
        lastLine = line
        prefs.trackingSummary = line
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification("Tracking $vehicle" + if (ok) "" else " - problem", line))
        sendBroadcast(Intent(BROADCAST_UPDATE).putExtra("line", line).putExtra("running", running).setPackage(packageName))
    }

    private fun finish(reason: String, showDone: Boolean) {
        running = false
        prefs.tracking = false
        prefs.trackingSummary = reason
        handler.removeCallbacks(tick)
        releaseWakeLock()
        if (showDone) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(DONE_NOTIF_ID, buildNotification("AIS Track Master", reason, ongoing = false))
        }
        sendBroadcast(Intent(BROADCAST_UPDATE).putExtra("line", reason).putExtra("running", false).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── notification / wake lock plumbing ─────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while vehicle location is being sent to AIS"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_truck)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ongoing) b.addAction(0, "Stop", stop)
        return b.build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AISTrackMaster:tracking").apply { acquire() }
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
