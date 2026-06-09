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
 * Flies the drone (BODY-frame velocity, indoor/VPS, no GPS) so the marker
 * center ends up directly below it.
 *
 * The hard part is that the live offset is in the CAMERA frame and we must
 * command motion in the DRONE BODY frame; the rotation between them is fixed
 * by the gimbal mount but unknown ahead of time. So we CALIBRATE it once:
 * nudge body-forward, then body-right, measure how the camera offset moved,
 * and build the 2x2 map. After that, centering is exact — no guessed signs.
 *
 * getOffsetM() returns the center's offset from the drone in CAMERA-frame
 * meters (x, y), or null when markers aren't visible.
 */
class AutoCenterController(
    private val getOffsetM: () -> Pair<Double, Double>?,
    private val onSettled: () -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        private const val TAG = "AutoCenter"
        private const val TICK_MS = 100L
        private const val NUDGE_VEL = 0.2          // m/s calibration nudge
        private const val NUDGE_TICKS = 8          // ~0.8s ≈ 0.16 m
        private const val GAIN = 0.6               // m/s per meter of remaining offset
        private const val MAX_VEL = 0.4
        private const val TOLERANCE_M = 0.15
        private const val SETTLE_TICKS = 8
        private const val TIMEOUT_MS = 15000L
        private const val LOST_TIMEOUT_MS = 1500L
        private const val MIN_NUDGE_RESPONSE = 0.04  // m; below this calibration is unreliable
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    // Inverse of the camera→body map (2x2). bodyMove = Minv · desiredCameraChange.
    private var mInv: DoubleArray? = null   // [a,b,c,d] row-major

    fun start() {
        if (job != null) return
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    FileLogger.write("AutoCenter VS enabled")
                    job = scope.launch { runAll() }
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("AutoCenter VS enable FAILED: ${error.description()}")
                    onState("VS failed: ${error.description()}")
                }
            })
        } catch (e: Exception) { onState("VS exception: ${e.message}") }
    }

    private suspend fun runAll() {
        onState("calibrating…")
        if (!calibrate()) { onState("calibration failed"); sendStick(0.0, 0.0); return }
        onState("centering…")
        center()
    }

    /** Two nudges (forward, right) → build camera→body inverse map. */
    private suspend fun calibrate(): Boolean {
        val o0 = waitOffset() ?: return false
        // forward nudge
        repeat(NUDGE_TICKS) { if (!alive()) return false; sendStick(NUDGE_VEL, 0.0); delay(TICK_MS) }
        sendStick(0.0, 0.0); delay(300)
        val oF = waitOffset() ?: return false
        val dF = Pair(oF.first - o0.first, oF.second - o0.second)
        // right nudge
        val o1 = oF
        repeat(NUDGE_TICKS) { if (!alive()) return false; sendStick(0.0, NUDGE_VEL); delay(TICK_MS) }
        sendStick(0.0, 0.0); delay(300)
        val oR = waitOffset() ?: return false
        val dR = Pair(oR.first - o1.first, oR.second - o1.second)

        FileLogger.write("Calib dF=(%.3f,%.3f) dR=(%.3f,%.3f)".format(dF.first, dF.second, dR.first, dR.second))
        if (hypot(dF.first, dF.second) < MIN_NUDGE_RESPONSE || hypot(dR.first, dR.second) < MIN_NUDGE_RESPONSE) {
            FileLogger.write("Calib response too small — markers may be too far / not moving"); return false
        }
        // M columns = camera-offset change per body-forward / body-right move.
        val m = doubleArrayOf(dF.first, dR.first, dF.second, dR.second) // [[dFx,dRx],[dFy,dRy]]
        val det = m[0] * m[3] - m[1] * m[2]
        if (abs(det) < 1e-6) { FileLogger.write("Calib singular"); return false }
        mInv = doubleArrayOf(m[3] / det, -m[1] / det, -m[2] / det, m[0] / det)
        FileLogger.write("Calib OK det=%.4f".format(det))
        return true
    }

    private suspend fun center() {
        val startMs = System.currentTimeMillis()
        var lastSeen = System.currentTimeMillis()
        var settle = 0
        val inv = mInv ?: return
        while (alive()) {
            val now = System.currentTimeMillis()
            if (now - startMs > TIMEOUT_MS) { onState("center timeout"); break }
            val off = getOffsetM()
            if (off == null) {
                if (now - lastSeen > LOST_TIMEOUT_MS) { sendStick(0.0, 0.0); onState("markers lost"); break }
                sendStick(0.0, 0.0); delay(TICK_MS); continue
            }
            lastSeen = now
            val dist = hypot(off.first, off.second)
            if (dist <= TOLERANCE_M) {
                settle++; sendStick(0.0, 0.0)
                if (settle >= SETTLE_TICKS) { FileLogger.write("Centered (%.2fm)".format(dist)); sendStick(0.0,0.0); onState("centered"); onSettled(); return }
            } else {
                settle = 0
                // want camera change = -offset → bodyMove = Minv·(-offset)
                val tx = -off.first; val ty = -off.second
                val bf = inv[0] * tx + inv[1] * ty   // forward (meters)
                val br = inv[2] * tx + inv[3] * ty   // right (meters)
                sendStick(
                    forward = (GAIN * bf).coerceIn(-MAX_VEL, MAX_VEL),
                    right = (GAIN * br).coerceIn(-MAX_VEL, MAX_VEL)
                )
            }
            delay(TICK_MS)
        }
        sendStick(0.0, 0.0)
    }

    private suspend fun waitOffset(): Pair<Double, Double>? {
        repeat(15) { val o = getOffsetM(); if (o != null) return o; if (!alive()) return null; delay(TICK_MS) }
        return null
    }

    fun abort() {
        aborted = true; job?.cancel()
        scope.launch { sendStick(0.0, 0.0) }
        FileLogger.write("AutoCenter aborted")
    }

    private fun alive() = scope.isActive && !aborted

    private fun sendStick(forward: Double, right: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward.coerceIn(-MAX_VEL, MAX_VEL)
                roll = right.coerceIn(-MAX_VEL, MAX_VEL)
                yaw = 0.0
                verticalThrottle = 0.0
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) { Log.w(TAG, "stick: ${e.message}") }
    }
}
