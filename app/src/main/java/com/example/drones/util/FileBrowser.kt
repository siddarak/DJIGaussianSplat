package com.example.drones.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast

/**
 * Best-effort opener for the app's externalFilesDir in the Files app.
 * Always copies path to clipboard as fallback (Android 11+ blocks
 * direct browse of Android/data on many devices).
 */
object FileBrowser {
    private const val TAG = "FileBrowser"

    fun openDroneFolder(context: Context) {
        val path = FileLogger.rootPath()

        // Always copy path to clipboard so user can paste in any file picker
        try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("drone path", path))
        } catch (_: Exception) {}

        // Dump logcat so the latest log is fresh on disk
        FileLogger.dumpLogcat()

        Toast.makeText(context, "Path copied: $path", Toast.LENGTH_LONG).show()

        // Try to launch Files app on the folder. Often blocked on /Android/data — chain attempts.
        val pkg = context.packageName
        val docUri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2F$pkg%2Ffiles"
        )

        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            Intent(Intent.ACTION_VIEW).setDataAndType(docUri, "*/*"),
            Intent("android.intent.action.SHOW_APP_INFO")
        )

        for (intent in attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "intent failed: ${e.message}")
            }
        }
        Toast.makeText(context, "Open Files app manually → paste path", Toast.LENGTH_LONG).show()
    }
}
