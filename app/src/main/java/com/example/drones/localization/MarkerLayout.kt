package com.example.drones.localization

import android.content.Context
import android.util.Log
import com.example.drones.util.FileLogger
import org.json.JSONObject
import java.io.File

/** Marker roles for the capture perimeter. */
enum class MarkerRole { INNER, OUTER, UNKNOWN }

/** One marker's expected role + optional measured ground position (meters). */
data class MarkerSpec(
    val id: Int,
    val role: MarkerRole,
    val xM: Double? = null,
    val yM: Double? = null
) {
    val hasMeasuredPos: Boolean get() = xM != null && yM != null
}

/**
 * Site config: which marker IDs exist, their roles (inner/outer perimeter),
 * optional measured positions, and table height. Read from
 * externalFilesDir/site/<id>.json (or bundled assets). Editable without rebuild.
 *
 * The ArUco ID itself comes free from the marker pattern — this only supplies
 * role, optional ground-truth positions, and the table height.
 */
data class MarkerLayout(
    val layoutId: String,
    val tableHeightM: Double?,
    val specs: Map<Int, MarkerSpec>
) {
    fun role(id: Int): MarkerRole = specs[id]?.role ?: defaultRole(id)
    fun spec(id: Int): MarkerSpec? = specs[id]
    val allMeasured: Boolean get() = specs.isNotEmpty() && specs.values.all { it.hasMeasuredPos }

    companion object {
        private const val TAG = "MarkerLayout"

        /** Fallback role convention when no config: 0-3 inner, 4-7 outer. */
        fun defaultRole(id: Int): MarkerRole = when (id) {
            in 0..3 -> MarkerRole.INNER
            in 4..7 -> MarkerRole.OUTER
            else -> MarkerRole.UNKNOWN
        }

        /** No-config default — roles by ID range, no measured positions, no table height. */
        fun default(): MarkerLayout = MarkerLayout("default", null, emptyMap())

        fun load(context: Context, layoutId: String = "default"): MarkerLayout {
            val text = readFile(context, "site/$layoutId.json") ?: return default()
            return try {
                val o = JSONObject(text)
                val specs = mutableMapOf<Int, MarkerSpec>()
                val arr = o.optJSONArray("markers")
                if (arr != null) for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val id = m.getInt("id")
                    val role = when (m.optString("role", "").lowercase()) {
                        "inner" -> MarkerRole.INNER
                        "outer" -> MarkerRole.OUTER
                        else -> defaultRole(id)
                    }
                    val x = if (m.has("x")) m.getDouble("x") else null
                    val y = if (m.has("y")) m.getDouble("y") else null
                    specs[id] = MarkerSpec(id, role, x, y)
                }
                val table = if (o.has("table_height_m")) o.getDouble("table_height_m") else null
                MarkerLayout(o.optString("layout_id", layoutId), table, specs)
                    .also { FileLogger.write("MarkerLayout '$layoutId': ${specs.size} markers, table=${table}m, measured=${it.allMeasured}") }
            } catch (e: Exception) {
                Log.e(TAG, "layout parse fail: ${e.message}"); default()
            }
        }

        private fun readFile(context: Context, rel: String): String? {
            val ext = File(context.getExternalFilesDir(null), rel)
            if (ext.exists()) return runCatching { ext.readText() }.getOrNull()
            return runCatching { context.assets.open(rel).bufferedReader().use { it.readText() } }.getOrNull()
        }
    }
}
