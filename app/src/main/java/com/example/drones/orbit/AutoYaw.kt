package com.example.drones.orbit

import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Rotates the drone so its nose points at a tapped detection box.
 *
 * Geometry: a box centered at xNorm = 0.5 sits straight ahead.
 * xNorm = 0.0 → at left edge of frame → bearing offset = -HFOV/2.
 * xNorm = 1.0 → at right edge → bearing offset = +HFOV/2.
 *
 * Mini 4 Pro horizontal FOV ≈ 73° (computed from 82.1° diagonal at 16:9).
 *
 * Caller must ensure drone is flying. Virtual stick is enabled here and
 * left enabled — orbit can take over directly.
 */
object AutoYaw {

    private const val TAG = "AutoYaw"
    private const val HFOV_DEG = 73.0
    private const val SETTLE_TOLERANCE_DEG = 3.0
    private const val TIMEOUT_MS = 3000L
    private const val TICK_MS = 100L

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * @param boxCenterXNorm horizontal center of detection box in [0, 1]
     * @param currentHeading drone's current compass heading, degrees
     * @param onComplete called with achieved heading when settled or timed out
     */
    fun yawToBox(
        boxCenterXNorm: Float,
        currentHeading: Double,
        getCurrentHeading: () -> Double,
        onComplete: (Double) -> Unit = {}
    ) {
        cancel()

        val xOffset = (boxCenterXNorm - 0.5f) * HFOV_DEG.toFloat()
        val targetHeading = normalize(currentHeading + xOffset)

        Log.i(TAG, "yaw: xNorm=$boxCenterXNorm offset=$xOffset° from=$currentHeading° → target=$targetHeading°")

        try {
            VirtualStickManager.getInstance().enableVirtualStick(object :
                dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.i(TAG, "VS enabled") }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.w(TAG, "VS enable failed: ${error.description()}")
                }
            })
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
        } catch (e: Exception) {
            Log.e(TAG, "VS init exception: ${e.message}")
            onComplete(currentHeading)
            return
        }

        job = scope.launch {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < TIMEOUT_MS) {
                val cur = getCurrentHeading()
                val err = shortestAngleDelta(cur, targetHeading)
                if (abs(err) <= SETTLE_TOLERANCE_DEG) break

                sendStickYawOnly(targetHeading)
                delay(TICK_MS)
            }
            // Final command to stop motion
            sendStickYawOnly(targetHeading)
            delay(100L)
            onComplete(getCurrentHeading())
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun sendStickYawOnly(targetYawDeg: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
                yawControlMode = YawControlMode.ANGLE
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = 0.0
                roll = 0.0
                yaw = targetYawDeg
                verticalThrottle = 0.0
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) {
            Log.w(TAG, "stick send fail: ${e.message}")
        }
    }

    private fun normalize(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun shortestAngleDelta(from: Double, to: Double): Double = normalize(to - from)
}
