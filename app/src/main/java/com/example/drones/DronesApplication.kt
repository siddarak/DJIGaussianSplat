package com.example.drones

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.drones.sdk.DjiSdkManager

class DronesApplication : Application() {

    companion object {
        private const val TAG = "DronesApplication"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // DJI's native library loader — must be called before any SDK class is used.
        // Wrapped in try-catch: if this fails the app will still launch, but DJI
        // features will be unavailable. DjiSdkManager will report the failure.
        try {
            com.cySdkyc.clx.Helper.install(this)
        } catch (e: Exception) {
            Log.e(TAG, "DJI native library install failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        DjiSdkManager.init(this)
    }
}
