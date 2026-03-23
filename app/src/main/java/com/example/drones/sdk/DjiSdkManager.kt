package com.example.drones.sdk

import android.content.Context
import android.util.Log
import com.example.drones.data.SdkConnectionState
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DjiSdkManager {

    private const val TAG = "DjiSdkManager"

    private val _connectionState = MutableStateFlow(SdkConnectionState.INITIALIZING)
    val connectionState: StateFlow<SdkConnectionState> = _connectionState.asStateFlow()

    private val _productConnected = MutableStateFlow(false)
    val productConnected: StateFlow<Boolean> = _productConnected.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Volatile ensures visibility across threads without full synchronization overhead
    @Volatile private var isInitialized = false

    fun init(context: Context) {
        // Double-checked locking pattern — prevents duplicate init from any thread
        if (isInitialized) {
            Log.w(TAG, "SDK already initialized — skipping")
            return
        }
        synchronized(this) {
            if (isInitialized) return
            isInitialized = true
        }

        _connectionState.value = SdkConnectionState.INITIALIZING
        Log.i(TAG, "Initializing DJI SDK")

        SDKManager.getInstance().init(context, object : SDKManagerCallback {

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                Log.d(TAG, "Init process: $event ($totalProcess%)")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    _connectionState.value = SdkConnectionState.REGISTERING
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                Log.i(TAG, "SDK registered successfully")
                _connectionState.value = SdkConnectionState.REGISTERED
                _errorMessage.value = null
            }

            override fun onRegisterFailure(error: IDJIError) {
                val msg = "Registration failed [${error.errorCode()}]: ${error.description()}"
                Log.e(TAG, msg)
                _connectionState.value = SdkConnectionState.ERROR
                _errorMessage.value = msg
            }

            override fun onProductConnect(productId: Int) {
                Log.i(TAG, "Product connected: id=$productId")
                _productConnected.value = true
                _connectionState.value = SdkConnectionState.PRODUCT_CONNECTED
            }

            override fun onProductDisconnect(productId: Int) {
                Log.w(TAG, "Product disconnected: id=$productId")
                _productConnected.value = false
                _connectionState.value = if (isRegistered) {
                    SdkConnectionState.REGISTERED
                } else {
                    SdkConnectionState.DISCONNECTED
                }
            }

            override fun onProductChanged(productId: Int) {
                Log.i(TAG, "Product changed: id=$productId")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (total > 0) {
                    Log.d(TAG, "DB download: ${(current * 100 / total)}%")
                }
            }
        })
    }

    val isRegistered: Boolean
        get() = try {
            SDKManager.getInstance().isRegistered
        } catch (e: Exception) {
            Log.w(TAG, "isRegistered check failed: ${e.message}")
            false
        }
}
