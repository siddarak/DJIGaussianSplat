package com.example.drones.objectspec

import android.content.Context
import android.util.Log
import com.example.drones.util.FileLogger
import org.json.JSONObject
import java.io.File

/**
 * Loads ObjectConfig JSON files written by tools/cad_dims/extract_dims.py.
 *
 * Reads from externalFilesDir/objects/ so a new object can be dropped on the
 * device without rebuilding the app. Falls back to bundled assets/objects/.
 */
object ObjectConfigRepository {
    private const val TAG = "ObjectConfigRepo"
    private const val DIR = "objects"

    data class IndexEntry(val id: String, val file: String, val heightM: Double)

    /** List available objects from objects/index.json. Empty if none. */
    fun list(context: Context): List<IndexEntry> {
        val text = readFile(context, "$DIR/index.json") ?: return emptyList()
        return try {
            val arr = JSONObject(text).optJSONArray("objects") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IndexEntry(o.getString("id"), o.getString("file"), o.optDouble("height_m", 0.0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "index parse fail: ${e.message}"); emptyList()
        }
    }

    /** Load one object config by id. Null if missing/invalid. */
    fun load(context: Context, objectId: String): ObjectConfig? {
        val text = readFile(context, "$DIR/$objectId.json") ?: return null
        return try {
            val o = JSONObject(text)
            val d = o.getJSONObject("dimensions_m")
            ObjectConfig(
                objectId = o.getString("object_id"),
                sourceFile = o.optString("source_file", ""),
                sourceSha256 = o.optString("source_sha256", ""),
                unitsOriginal = o.optString("units_original", "UNKNOWN"),
                heightM = d.getDouble("height"),
                widthM = d.getDouble("width"),
                depthM = d.getDouble("depth"),
                upAxis = o.optString("up_axis", "Z"),
                schemaVersion = o.optInt("schema_version", 1)
            ).also { FileLogger.write("ObjectConfig loaded: $objectId h=${it.heightM}m units=${it.unitsOriginal}") }
        } catch (e: Exception) {
            Log.e(TAG, "config parse fail ($objectId): ${e.message}"); null
        }
    }

    /** externalFilesDir first (pushable), then bundled assets. */
    private fun readFile(context: Context, rel: String): String? {
        val ext = File(context.getExternalFilesDir(null), rel)
        if (ext.exists()) return runCatching { ext.readText() }.getOrNull()
        return runCatching { context.assets.open(rel).bufferedReader().use { it.readText() } }.getOrNull()
    }
}
