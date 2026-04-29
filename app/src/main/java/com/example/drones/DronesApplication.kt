package com.example.drones

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.drones.sdk.DjiSdkManager
import com.example.drones.sdk.FlightController
import com.example.drones.util.FileLogger

class DronesApplication : Application() {

    companion object {
        private const val TAG = "DronesApplication"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            com.cySdkyc.clx.Helper.install(this)
        } catch (e: Exception) {
            Log.e(TAG, "DJI native library install failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        FileLogger.write("App onCreate")
        DjiSdkManager.init(this)
        installCrashSafetyHandler()
    }

    /**
     * If the app crashes while the drone is flying, attempt RTH before the process dies.
     * RTH is preferred over land — safer if we don't know terrain below.
     * We sleep briefly to give the SDK time to dispatch the command before process exit.
     */
    private fun installCrashSafetyHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            Log.e(TAG, "UNCAUGHT EXCEPTION — attempting RTH before crash", ex)
            try {
                FlightController.returnToHome { _, _ -> }
                Thread.sleep(2500)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, ex)
        }
    }
}
