package com.aisglass.transporter

import android.content.Context
import android.content.SharedPreferences

/** Small wrapper around SharedPreferences - everything the app remembers. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("ais_transporter", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server_url", "") ?: ""
        set(v) = p.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    var driverName: String
        get() = p.getString("driver_name", "") ?: ""
        set(v) = p.edit().putString("driver_name", v.trim()).apply()

    var driverMobile: String
        get() = p.getString("driver_mobile", "") ?: ""
        set(v) = p.edit().putString("driver_mobile", v.trim()).apply()

    var vehicleNo: String
        get() = p.getString("vehicle_no", "") ?: ""
        set(v) = p.edit().putString("vehicle_no", v.trim().uppercase()).apply()

    /** Minutes between automatic location pushes while tracking. */
    var intervalMins: Int
        get() = p.getInt("interval_mins", 10)
        set(v) = p.edit().putInt("interval_mins", v).apply()

    var tracking: Boolean
        get() = p.getBoolean("tracking", false)
        set(v) = p.edit().putBoolean("tracking", v).apply()

    var trackingSummary: String
        get() = p.getString("tracking_summary", "") ?: ""
        set(v) = p.edit().putString("tracking_summary", v).apply()

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}
