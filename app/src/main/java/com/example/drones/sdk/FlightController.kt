package com.example.drones.sdk

import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Flight control commands via MSDK V5 KeyManager.
 *
 * All keys verified against dji-sdk-v5-aircraft-provided-5.17.0.jar:
 *   KeyStartTakeoff, KeyStartAutoLanding, KeyStopAutoLanding,
 *   KeyStartGoHome, KeyStopGoHome, KeyConfirmLanding,
 *   KeyEmergencyStop — all DJIActionKeyInfo<EmptyMsg, EmptyMsg>
 *
 * performAction(key, param, callback) where key = KeyTools.createKey(...)
 * returns DJIKey.ActionKey — the correct type for performAction.
 */
object FlightController {

    private const val TAG = "FlightController"

    fun takeOff(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
                EmptyMsg(),
                resultCallback("takeoff", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeOff exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun land(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding),
                EmptyMsg(),
                resultCallback("land", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "land exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun stopLanding(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding),
                EmptyMsg(),
                resultCallback("stopLanding", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "stopLanding exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun returnToHome(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
                EmptyMsg(),
                resultCallback("RTH", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "RTH exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun cancelReturnToHome(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStopGoHome),
                EmptyMsg(),
                resultCallback("cancelRTH", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "cancelRTH exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun confirmLanding(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyConfirmLanding),
                EmptyMsg(),
                resultCallback("confirmLanding", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "confirmLanding exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    /**
     * EMERGENCY: Cut motor power immediately — drone will fall.
     * Use only to prevent collision with people or property.
     */
    fun emergencyStop(onResult: (Boolean, String?) -> Unit) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyEmergencyStop),
                EmptyMsg(),
                resultCallback("EMERGENCY_STOP", onResult)
            )
        } catch (e: Exception) {
            Log.e(TAG, "emergencyStop exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    private fun resultCallback(
        tag: String,
        onResult: (Boolean, String?) -> Unit
    ) = object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
        override fun onSuccess(result: EmptyMsg?) {
            Log.i(TAG, "$tag: success")
            onResult(true, null)
        }
        override fun onFailure(error: IDJIError) {
            val msg = "[${error.errorCode()}] ${error.description()}"
            Log.e(TAG, "$tag failed: $msg")
            onResult(false, msg)
        }
    }
}
