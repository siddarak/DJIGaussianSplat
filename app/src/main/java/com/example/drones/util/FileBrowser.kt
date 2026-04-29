package com.example.drones.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android 11+ blocks /Android/data from third-party file managers, so we use
 * FileProvider + ACTION_SEND to push the log straight into a share sheet —
 * user picks Drive / Gmail / etc. Works on every Android version.
 */
object FileBrowser {
    private const val TAG = "FileBrowser"

    fun openDroneFolder(context: Context) {
        // Make sure latest logcat is flushed to disk first
        FileLogger.dumpLogcat()

        val logFile = File(FileLogger.logPath())
        val path = logFile.absolutePath

        // Always copy path to clipboard as fallback
        try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("drone log path", path))
        } catch (_: Exception) {}

        if (!logFile.exists()) {
            Toast.makeText(context, "No log yet — start detection first", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Drone log ${logFile.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "Send drone log to…")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}", e)
            Toast.makeText(
                context,
                "Share failed: ${e.message}\nPath copied: $path",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
