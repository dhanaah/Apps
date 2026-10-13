package com.aisglass.transporter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last 100 pushes (time, vehicle, place, result) so the driver
 *  and the transporter office can see what was sent and what AIS said. */
object LogStore {
    private const val MAX = 100
    private val fmt = SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault())

    fun add(ctx: Context, vehicle: String, place: String, result: String, ok: Boolean) {
        val arr = load(ctx)
        val e = JSONObject()
            .put("t", fmt.format(Date()))
            .put("v", vehicle).put("p", place).put("r", result).put("ok", ok)
        val out = JSONArray().put(e)
        for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
        ctx.getSharedPreferences("ais_transporter_log", Context.MODE_PRIVATE)
            .edit().putString("log", out.toString()).apply()
    }

    fun load(ctx: Context): JSONArray {
        val s = ctx.getSharedPreferences("ais_transporter_log", Context.MODE_PRIVATE).getString("log", "[]")
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    fun lines(ctx: Context): List<String> {
        val arr = load(ctx)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mark = if (o.optBoolean("ok")) "✓" else "✗"
            out.add("$mark ${o.optString("t")}  ${o.optString("v")}\n    ${o.optString("p")} - ${o.optString("r")}")
        }
        return out
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("ais_transporter_log", Context.MODE_PRIVATE).edit().remove("log").apply()
    }
}
