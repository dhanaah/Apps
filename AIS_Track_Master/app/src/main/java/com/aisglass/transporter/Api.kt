package com.aisglass.transporter

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of one API call: HTTP status + parsed JSON (or an error message). */
class ApiResult(val code: Int, val json: JSONObject?, val error: String?) {
    val ok: Boolean get() = code in 200..299 && json != null && json.optBoolean("ok", true)
    /** Human-readable message for logs/UI */
    fun message(): String = when {
        json != null && json.has("error") -> json.optString("error")
        error != null -> error
        else -> "HTTP $code"
    }
}

/** Thin client for the AIS Vehicle Tracker transporter API (v1). Blocking -
 *  always call from a background thread. */
class Api(private val baseUrl: String, private val apiKey: String) {

    fun ping(): ApiResult = get("/api/ext/v1/ping")

    fun vehicles(): ApiResult = get("/api/ext/v1/vehicles")

    fun vehicle(vehicleNo: String): ApiResult =
        get("/api/ext/v1/vehicle/" + URLEncoder.encode(vehicleNo, "UTF-8"))

    fun pushLocation(vehicleNo: String, lat: Double?, lon: Double?, place: String,
                     driverName: String, driverMobile: String, remarks: String): ApiResult {
        val body = JSONObject().put("vehicle_no", vehicleNo)
        if (lat != null && lon != null) { body.put("lat", lat); body.put("lon", lon) }
        if (place.isNotBlank()) body.put("place", place)
        if (driverName.isNotBlank()) body.put("driver_name", driverName)
        if (driverMobile.isNotBlank()) body.put("driver_mobile", driverMobile)
        if (remarks.isNotBlank()) body.put("remarks", remarks)
        return post("/api/ext/v1/location", body)
    }

    private fun get(path: String): ApiResult = call("GET", path, null)
    private fun post(path: String, body: JSONObject): ApiResult = call("POST", path, body)

    private fun call(method: String, path: String, body: JSONObject?): ApiResult {
        if (baseUrl.isBlank() || apiKey.isBlank()) return ApiResult(0, null, "Server URL / API key not set")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            if (json == null && code !in 200..299) ApiResult(code, null, "Server error HTTP $code")
            else ApiResult(code, json, null)
        } catch (e: Exception) {
            ApiResult(0, null, "No connection: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            conn?.disconnect()
        }
    }
}
