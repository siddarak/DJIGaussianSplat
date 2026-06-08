package com.example.drones.orbit

import android.util.Log
import com.example.drones.util.FileLogger
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Flies the drone (BODY-frame velocity, works indoors on VPS — no GPS) so the
 * marker-derived center ends up directly below it.
 *
 * Input each tick: the center's horizontal offset from the drone in meters
 * (camera/ground frame, gimbal pointing straight down). We drive that to zero
 * with a slow proportional controller.
 *
 * SIGN/AXIS MAPPING NOTE: how camera-frame (x,y) maps to body forward/right
 * depends on the gimbal/camera mounting. Verify on first flight — if the drone
 * moves the WRONG way, flip AXIS_SIGN_* below. Kept very slow for safety.
 */
class AutoCenterController(
    private val getOffsetM: () -> Pair<Double, Double>?,   // (offsetRight, offsetForward) meters, null if lost
    private val onSettled: () -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        private const val TAG = "AutoCenter"
        private const val TICK_MS = 100L
        private const val GAIN = 0.6              // m/s per meter of offset
        private const val MAX_VEL = 0.4           // m/s hard cap (slow, indoor)
        private const val TOLERANCE_M = 0.15      // considered centered
        private const val SETTLE_TICKS = 8        // must stay within tolerance this many ticks
        private const val TIMEOUT_MS = 12000L
        private const val LOST_TIMEOUT_MS = 1500L

        // Flip these if the drone moves the wrong way during field test.
        private const val AXIS_SIGN_RIGHT = 1.0
        private const val AXIS_SIGN_FWD = 1.0
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    fun start() {
        if (job != null) return
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { Log.i(TAG, "VS enabled") }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("AutoCenter VS enable FAILED: ${error.description()}")
                    onState("VS failed: ${error.description()}")
                }
            })
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
        } catch (e: Exception) {
            onState("VS exception: ${e.message}"); return
        }

        onState("centering…")
        FileLogger.write("AutoCenter start")
        job = scope.launch {
            val startMs = System.currentTimeMillis()
            var lastSeenMs = System.currentTimeMillis()
            var settle = 0
            while (isActive && !aborted) {
                val now = System.currentTimeMillis()
                if (now - startMs > TIMEOUT_MS) { onState("center timeout"); break }

                val off = getOffsetM()
                if (off == null) {
                    if (now - lastSeenMs > LOST_TIMEOUT_MS) { sendStick(0.0, 0.0); onState("markers lost") ; break }
                    sendStick(0.0, 0.0); delay(TICK_MS); continue
                }
                lastSeenMs = now
                val (offRight, offFwd) = off
                val dist = hypot(offRight, offFwd)

                if (dist <= TOLERANCE_M) {
                    settle++
                    sendStick(0.0, 0.0)
                    if (settle >= SETTLE_TICKS) { FileLogger.write("AutoCenter settled (%.2fm)".format(dist)); onSettledInternal(); return@launch }
                } else {
                    settle = 0
                    val vRight = (AXIS_SIGN_RIGHT * GAIN * offRight).coerceIn(-MAX_VEL, MAX_VEL)
                    val vFwd   = (AXIS_SIGN_FWD   * GAIN * offFwd).coerceIn(-MAX_VEL, MAX_VEL)
                    sendStick(forward = vFwd, right = vRight)
                }
                delay(TICK_MS)
            }
            sendStick(0.0, 0.0)
        }
    }

    private fun onSettledInternal() {
        sendStick(0.0, 0.0)
        onState("centered")
        onSettled()
    }

    fun abort() {
        aborted = true
        job?.cancel()
        scope.launch { sendStick(0.0, 0.0) }
        FileLogger.write("AutoCenter aborted")
    }

    private fun sendStick(forward: Double, right: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY   // indoor: no GPS needed
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward.coerceIn(-MAX_VEL, MAX_VEL)   // body forward
                roll = right.coerceIn(-MAX_VEL, MAX_VEL)      // body right
                yaw = 0.0
                verticalThrottle = 0.0
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) {
            Log.w(TAG, "stick: ${e.message}")
        }
    }
}
