package com.example.drones

import android.app.Application
import android.content.Context
import android.util.Log
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent

class DronesApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // CRITICAL: Must be called before any DJI SDK usage
        // This loads native libraries required by the DJI SDK
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                Log.d("DronesApp", "✓ MSDK registered successfully")
            }

            override fun onRegisterFailure(error: IDJIError) {
                Log.e("DronesApp", "✗ MSDK registration failed: ${error.errorCode()} - ${error.description()}")
            }

            override fun onProductDisconnect(productId: Int) {
                Log.d("DronesApp", "Drone disconnected: $productId")
            }

            override fun onProductConnect(productId: Int) {
                Log.d("DronesApp", "Drone connected: $productId")
            }

            override fun onProductChanged(productId: Int) {
                Log.d("DronesApp", "Drone changed: $productId")
            }

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.d("DronesApp", "MSDK init: $event ($totalProcess%)")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    // Start registration once initialization is complete
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.d("DronesApp", "Database download: $current/$total bytes")
            }
        })
    }
}
