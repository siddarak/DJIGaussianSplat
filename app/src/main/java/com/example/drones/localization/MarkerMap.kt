package com.example.drones.localization

import android.content.Context
import android.util.Log
import com.example.drones.util.FileLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * World-frame positions of captured ArUco markers.
 *
 * Convention:
 *   - First captured marker is the world origin (0, 0, 0)
 *   - X = east, Y = north, Z = up (right-handed)
 *   - Each subsequent marker's position is derived from co-observation with
 *     a previously locked marker (see [SurveyController])
 *   - Capture order preserved as chronological waypoint sequence
 *
 * Persists to externalFilesDir/marker-maps/<env>.json
 */
data class WaypointEntry(
    val id: Int,
    val seq: Int,                    // 0-based capture order
    val x: Double,                   // meters in world frame
    val y: Double,
    val z: Double = 0.0,
    val timestampMs: Long
)

class MarkerMap {
    private val entries = mutableListOf<WaypointEntry>()

    val size: Int get() = entries.size
    fun snapshot(): List<WaypointEntry> = entries.toList()
    fun isLocked(id: Int): Boolean = entries.any { it.id == id }
    fun get(id: Int): WaypointEntry? = entries.firstOrNull { it.id == id }

    /** Add new waypoint. Returns false if id already locked. */
    fun add(id: Int, x: Double, y: Double, z: Double = 0.0): Boolean {
        if (isLocked(id)) return false
        entries.add(WaypointEntry(
            id = id, seq = entries.size,
            x = x, y = y, z = z,
            timestampMs = System.currentTimeMillis()
        ))
        return true
    }

    fun reset() {
        entries.clear()
    }

    /** Centroid of all waypoints (orbit focus). */
    fun centroid(): Triple<Double, Double, Double> {
        if (entries.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val n = entries.size
        return Triple(
            entries.sumOf { it.x } / n,
            entries.sumOf { it.y } / n,
            entries.sumOf { it.z } / n
        )
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("seq", e.seq)
                put("x", e.x); put("y", e.y); put("z", e.z)
                put("ts", e.timestampMs)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("count", entries.size)
            put("entries", arr)
        }
    }

    fun loadJson(obj: JSONObject) {
        entries.clear()
        val arr = obj.optJSONArray("entries") ?: return
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            entries.add(WaypointEntry(
                id = e.getInt("id"),
                seq = e.getInt("seq"),
                x = e.getDouble("x"), y = e.getDouble("y"), z = e.optDouble("z", 0.0),
                timestampMs = e.optLong("ts", System.currentTimeMillis())
            ))
        }
    }

    companion object {
        private const val TAG = "MarkerMap"

        fun saveTo(context: Context, name: String, map: MarkerMap) {
            try {
                val dir = File(context.getExternalFilesDir(null), "marker-maps").apply { mkdirs() }
                val f = File(dir, "$name.json")
                f.writeText(map.toJson().toString(2))
                FileLogger.write("MarkerMap saved: $name (${map.size} waypoints) → ${f.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "save fail: ${e.message}")
            }
        }

        fun loadFrom(context: Context, name: String): MarkerMap? {
            return try {
                val f = File(File(context.getExternalFilesDir(null), "marker-maps"), "$name.json")
                if (!f.exists()) return null
                val obj = JSONObject(f.readText())
                MarkerMap().also { it.loadJson(obj) }
            } catch (e: Exception) {
                Log.e(TAG, "load fail: ${e.message}"); null
            }
        }
    }
}
