package com.example.drones.waypoints

import android.content.Context
import android.util.Log
import com.example.drones.util.FileLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves/loads WaypointPath as JSON to externalFilesDir/waypoints/<id>.json,
 * plus an index. DJI-style structure (position + yaw + gimbal + action).
 */
object WaypointStore {
    private const val TAG = "WaypointStore"
    private const val DIR = "waypoints"
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun save(context: Context, path: WaypointPath) {
        try {
            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            File(dir, "${path.id}.json").writeText(toJson(path).toString(2))
            FileLogger.write("WaypointPath saved: ${path.id} (${path.waypoints.size} wp)")
        } catch (e: Exception) { Log.e(TAG, "save: ${e.message}") }
    }

    fun load(context: Context, id: String): WaypointPath? {
        return try {
            val f = File(File(context.getExternalFilesDir(null), DIR), "$id.json")
            if (!f.exists()) null else fromJson(JSONObject(f.readText()))
        } catch (e: Exception) { Log.e(TAG, "load: ${e.message}"); null }
    }

    fun list(context: Context): List<String> {
        val dir = File(context.getExternalFilesDir(null), DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    fun newId(): String = "path-${tsFmt.format(Date())}"

    private fun toJson(p: WaypointPath): JSONObject {
        val arr = JSONArray()
        p.waypoints.forEach { w ->
            arr.put(JSONObject().apply {
                put("seq", w.seq); put("x", w.xM); put("y", w.yM); put("z", w.zM)
                put("yaw", w.yawDeg); put("gimbal", w.gimbalPitchDeg); put("action", w.action.name)
            })
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("id", p.id); put("created_utc", p.createdUtc)
            put("center_x", p.centerXM); put("center_y", p.centerYM)
            put("frame", "marker_local_meters")
            put("waypoints", arr)
        }
    }

    private fun fromJson(o: JSONObject): WaypointPath {
        val arr = o.getJSONArray("waypoints")
        val wps = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            CaptureWaypoint(
                seq = w.getInt("seq"), xM = w.getDouble("x"), yM = w.getDouble("y"), zM = w.getDouble("z"),
                yawDeg = w.getDouble("yaw"), gimbalPitchDeg = w.getDouble("gimbal"),
                action = runCatching { WaypointAction.valueOf(w.optString("action", "NONE")) }.getOrDefault(WaypointAction.NONE)
            )
        }
        return WaypointPath(
            id = o.getString("id"), createdUtc = o.optString("created_utc", ""),
            centerXM = o.optDouble("center_x", 0.0), centerYM = o.optDouble("center_y", 0.0),
            waypoints = wps
        )
    }
}
