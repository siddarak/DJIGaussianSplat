package com.example.drones.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Publishes a private app file to public MediaStore so it appears in
 * Samsung Gallery / Files / Google Drive picker.
 *
 * On Android 10+ (Q): uses MediaStore RELATIVE_PATH (no permission needed).
 * On Android 9-: uses direct write to external storage (we have WRITE_EXTERNAL_STORAGE for SDK<=32).
 *
 * Original app-private file is left untouched (this is a copy).
 */
object MediaPublisher {
    private const val TAG = "MediaPublisher"

    /** Copy a finished video to public Movies/DroneCaptures/. Returns Uri or null. */
    fun publishVideo(context: Context, src: File): String? {
        if (!src.exists() || src.length() == 0L) {
            Log.w(TAG, "Skip empty/missing: ${src.name}")
            return null
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(
                    context, src,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "video/mp4",
                    "Movies/DroneCaptures"
                )
            } else {
                publishLegacy(src, Environment.DIRECTORY_MOVIES, "DroneCaptures")
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishVideo fail: ${e.message}", e)
            null
        }
    }

    /** Copy log to public Documents/Drones/. */
    fun publishLog(context: Context, src: File): String? {
        if (!src.exists() || src.length() == 0L) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(
                    context, src,
                    MediaStore.Files.getContentUri("external"),
                    "text/plain",
                    "Documents/Drones"
                )
            } else {
                publishLegacy(src, Environment.DIRECTORY_DOCUMENTS, "Drones")
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishLog fail: ${e.message}", e)
            null
        }
    }

    private fun publishViaMediaStore(
        context: Context,
        src: File,
        collection: android.net.Uri,
        mime: String,
        relPath: String
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        Log.i(TAG, "Published ${src.name} → $relPath/")
        return uri.toString()
    }

    private fun publishLegacy(src: File, dirEnv: String, sub: String): String? {
        val pubDir = File(Environment.getExternalStoragePublicDirectory(dirEnv), sub)
        if (!pubDir.exists()) pubDir.mkdirs()
        val dst = File(pubDir, src.name)
        src.copyTo(dst, overwrite = true)
        Log.i(TAG, "Published legacy → ${dst.absolutePath}")
        return dst.absolutePath
    }
}
