package com.example.drones.sdk

import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Gimbal control via MSDK V5 KeyManager.
 *
 * Keys verified against dji-sdk-v5-aircraft-provided-5.17.0.jar:
 *   KeyRotateByAngle  — DJIActionKeyInfo<GimbalAngleRotation, EmptyMsg>
 *   KeyGimbalReset    — DJIActionKeyInfo<GimbalResetType, EmptyMsg>
 *
 * Lock behaviour: we send an ABSOLUTE_ANGLE command to the current pitch.
 * The RC-N3 scroll wheel can still override — pilot always has final authority.
 */
object GimbalController {

    private const val TAG = "GimbalController"

    /**
     * Set gimbal to an absolute pitch angle.
     * @param pitchDegrees  Target degrees. Mini 4 Pro range: ~-90 (down) to +35 (up).
     * @param durationSec   Rotation duration in seconds. 0 = snap immediately.
     */
    fun setPitch(
        pitchDegrees: Double,
        durationSec: Double = 0.5,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        try {
            val rotation = GimbalAngleRotation().apply {
                mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
                pitch = pitchDegrees
                pitchIgnored = false
                rollIgnored = true
                yawIgnored = true
                duration = durationSec
            }
            KeyManager.getInstance().performAction(
                KeyTools.createKey(GimbalKey.KeyRotateByAngle),
                rotation,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        Log.i(TAG, "Gimbal pitch set to ${pitchDegrees}°")
                        onResult?.invoke(true, null)
                    }
                    override fun onFailure(error: IDJIError) {
                        val msg = "[${error.errorCode()}] ${error.description()}"
                        Log.e(TAG, "setPitch failed: $msg")
                        onResult?.invoke(false, msg)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "setPitch exception: ${e.message}")
            onResult?.invoke(false, e.message)
        }
    }

    /** Lock gimbal at its current angle by commanding it to stay there. */
    fun lockAtCurrentAngle(
        currentPitch: Double,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) = setPitch(currentPitch, durationSec = 0.0, onResult = onResult)

    /** Reset gimbal to level (0° pitch). */
    fun resetToLevel(onResult: ((Boolean, String?) -> Unit)? = null) {
        try {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(GimbalKey.KeyGimbalReset),
                GimbalResetType.PITCH_YAW,
                object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        Log.i(TAG, "Gimbal reset to level")
                        onResult?.invoke(true, null)
                    }
                    override fun onFailure(error: IDJIError) {
                        val msg = "[${error.errorCode()}] ${error.description()}"
                        Log.e(TAG, "resetToLevel failed: $msg")
                        onResult?.invoke(false, msg)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "resetToLevel exception: ${e.message}")
            onResult?.invoke(false, e.message)
        }
    }

    /** Point gimbal straight down (-90°). */
    fun pointDown(onResult: ((Boolean, String?) -> Unit)? = null) =
        setPitch(-90.0, durationSec = 1.0, onResult = onResult)
}
