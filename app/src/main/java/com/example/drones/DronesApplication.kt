package com.example.drones

import android.app.Application
import android.content.Context
import android.util.Log
import com.secneo.sdk.Helper
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.product.ProductType

class DronesApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // CRITICAL: Must be called before any DJI SDK usage
        // This loads native libraries from a secondary APK
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                Log.d("DronesApp", "✓ MSDK registered successfully")
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e("DronesApp", "✗ MSDK registration failed: ${error.errorCode} - ${error.description}")
            }

            override fun onProductDisconnect(product: ProductType) {
                Log.d("DronesApp", "Drone disconnected: $product")
            }

            override fun onProductConnect(product: ProductType) {
                Log.d("DronesApp", "Drone connected: $product")
            }

            override fun onProductChanged(product: ProductType) {
                Log.d("DronesApp", "Drone changed: $product")
            }

            override fun onInitProcess(event: dji.v5.manager.SDKManagerEvent, totalProcess: Int) {
                Log.d("DronesApp", "MSDK init: $event ($totalProcess%)")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d("DronesApp", "Database download: $current/$total bytes")
            }
        })
    }
}
