package com.example.drones.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-flight log files. Layout:
 *   externalFilesDir/
 *     flights/flight-yyyy-MM-dd-HH-mm-ss/log.txt
 *     video/
 *     photo/
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val flightTs = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    @Volatile private var rootDir: File? = null
    @Volatile private var flightDir: File? = null
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        if (!dir.exists()) dir.mkdirs()
        rootDir = dir
        File(dir, "video").mkdirs()
        File(dir, "photo").mkdirs()
        File(dir, "flights").mkdirs()
        startFlight()
        Log.i(TAG, "Root dir: ${dir.absolutePath}")
    }

    /** Begin new flight folder. Called on init + can be called again on takeoff. */
    fun startFlight() {
        val root = rootDir ?: return
        val name = "flight-${flightTs.format(Date())}"
        val fdir = File(root, "flights/$name").apply { mkdirs() }
        flightDir = fdir
        logFile = File(fdir, "log.txt")
        write("=== Flight started ${Date()} ===")
    }

    fun rootPath(): String = rootDir?.absolutePath ?: "(uninit)"
    fun logPath():  String = logFile?.absolutePath ?: "(uninit)"

    fun write(line: String) {
        try {
            logFile?.appendText("[${ts.format(Date())}] $line\n")
        } catch (e: Exception) {
            Log.w(TAG, "write fail: ${e.message}")
        }
    }

    fun dumpLogcat(maxLines: Int = 500) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", maxLines.toString()))
            val output = proc.inputStream.bufferedReader().readText()
            write("=== LOGCAT (last $maxLines) ===")
            logFile?.appendText(output)
            write("=== END LOGCAT ===")
        } catch (e: Exception) {
            write("logcat dump fail: ${e.message}")
        }
    }
}
