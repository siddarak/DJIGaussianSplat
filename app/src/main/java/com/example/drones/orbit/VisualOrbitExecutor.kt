package com.example.drones.orbit

import android.graphics.RectF
import android.util.Log
import com.example.drones.detection.DetectionResult
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Visual orbit — for indoor / no-GPS use.
 *
 * Closed-loop body-frame virtual stick. Three controllers:
 *   yawRate     ← keep box centered horizontally  (P controller)
 *   forwardVel  ← keep box size constant (radius)  (P controller)
 *   rightVel    ← constant lateral velocity (orbit motion)
 *
 * Tracks the target by closest matching label between frames.
 * If detection drops for > LOST_TIMEOUT_MS, drone holds position.
 *
 * UNSAFE without object lock — only aborts on detection loss, doesn't
 * know absolute orbit progress. Best for short demos / indoor testing.
 */
class VisualOrbitExecutor(
    private val targetLabel: String,
    initialBox: RectF,
    private val getDetections: () -> List<DetectionResult>,
    private val onState: (OrbitState) -> Unit
) {
    companion object {
        private const val TAG = "VisualOrbitExecutor"
        private const val TICK_HZ = 10
        private const val TICK_MS = (1000 / TICK_HZ).toLong()
        private const val LOST_TIMEOUT_MS = 1500L

        // Tunable controller gains
        private const val K_YAW = 60.0      // deg/sec per unit horizontal error
        private const val K_FWD = 1.5       // m/sec per unit size error
        private const val ORBIT_LATERAL_MS = 0.5  // crab speed
        private const val MAX_VEL = 1.0     // safety clamp

        private const val TARGET_BOX_SIZE = 0.30f  // box width as fraction of frame
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false
    private var lastBox: RectF = initialBox
    private var lastSeenMs: Long = System.currentTimeMillis()
    private val visualRing = OrbitRing(0.0, 0.0, OrbitPhase.EQUATORIAL, "VISUAL")

    fun start() {
        if (job != null) return
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
            Log.e(TAG, "VS init: ${e.message}")
            onState(OrbitState.Error("VS enable failed"))
            return
        }

        onState(OrbitState.Arming)

        job = scope.launch {
            onState(OrbitState.Flying(visualRing, 0, 1, 0.0))
            val startMs = System.currentTimeMillis()

            while (isActive && !aborted) {
                val now = System.currentTimeMillis()
                val box = pickTargetBox()
                if (box != null) {
                    lastBox = box
                    lastSeenMs = now
                }

                if (now - lastSeenMs > LOST_TIMEOUT_MS) {
                    // Lost target — hold position
                    sendStick(0.0, 0.0, 0.0, 0.0)
                    delay(TICK_MS)
                    continue
                }

                val xErr = ((lastBox.left + lastBox.right) / 2f - 0.5f).toDouble()
                val sizeErr = (lastBox.width() - TARGET_BOX_SIZE).toDouble()

                val yawRateDeg = clamp(-K_YAW * xErr, -45.0, 45.0)
                val fwdVel     = clamp(-K_FWD * sizeErr, -MAX_VEL, MAX_VEL)
                val rightVel   = ORBIT_LATERAL_MS

                sendStick(forward = fwdVel, right = rightVel, up = 0.0, yawRate = yawRateDeg)

                val elapsedSec = (now - startMs) / 1000.0
                val progressDeg = (elapsedSec * 30.0).coerceAtMost(360.0)  // rough estimate
                onState(OrbitState.Flying(visualRing, 0, 1, progressDeg))

                if (progressDeg >= 360.0) break
                delay(TICK_MS)
            }
            sendStick(0.0, 0.0, 0.0, 0.0)
            onState(if (aborted) OrbitState.Aborted else OrbitState.Done)
        }
    }

    fun abort() {
        aborted = true
        job?.cancel()
        scope.launch { sendStick(0.0, 0.0, 0.0, 0.0) }
    }

    /** Pick best matching box (same label, closest to last position). */
    private fun pickTargetBox(): RectF? {
        val dets = getDetections().filter { it.label == targetLabel }
        if (dets.isEmpty()) return null
        val cx0 = (lastBox.left + lastBox.right) / 2f
        val cy0 = (lastBox.top + lastBox.bottom) / 2f
        return dets.minByOrNull { d ->
            val cx = (d.boxNorm.left + d.boxNorm.right) / 2f
            val cy = (d.boxNorm.top + d.boxNorm.bottom) / 2f
            (cx - cx0) * (cx - cx0) + (cy - cy0) * (cy - cy0)
        }?.boxNorm
    }

    private fun sendStick(forward: Double, right: Double, up: Double, yawRate: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward.coerceIn(-MAX_VEL, MAX_VEL)
                roll = right.coerceIn(-MAX_VEL, MAX_VEL)
                yaw = yawRate
                verticalThrottle = up.coerceIn(-MAX_VEL, MAX_VEL)
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) {
            Log.w(TAG, "stick send fail: ${e.message}")
        }
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))
}
