package com.example.drones.orbit

import android.util.Log
import com.example.drones.sdk.GimbalController
import com.example.drones.util.FileLogger
import com.example.drones.waypoints.CaptureWaypoint
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Flies the capture path as a sequence of WAYPOINTS, closed-loop on the markers.
 *
 * Why this works where the open-loop circle didn't: the drone always has a
 * position reference (the marker center offset), so it actively drives itself
 * to each waypoint instead of dead-reckoning.
 *
 * Setup: gimbal straight down, two nudges calibrate the camera→body map.
 * Per waypoint (center = origin):
 *   - target offset = (-x, -y)  (where the center sits relative to the drone
 *     when the drone is AT the waypoint)
 *   - drive measured offset → target using the calibrated map, rotated by the
 *     live heading delta so it stays valid as the drone yaws
 *   - yaw to keep the nose on center; hold the waypoint's height (downward sensor)
 *   - at arrival, tilt the gimbal to the ring pitch to frame the object
 *
 * Heavily logged; abort releases Virtual Stick.
 */
class WaypointFlightExecutor(
    private val waypoints: List<CaptureWaypoint>,
    private val getCenterOffsetM: () -> Pair<Double, Double>?,
    private val getHeadingDeg: () -> Double,
    private val getDroneAlt: () -> Double,
    private val isFlying: () -> Boolean,
    private val onState: (OrbitState) -> Unit
) {
    companion object {
        private const val TAG = "WaypointFlight"
        private const val TICK_MS = 100L
        private const val NUDGE_VEL = 0.2
        private const val NUDGE_TICKS = 8
        private const val MIN_NUDGE = 0.04
        private const val POS_GAIN = 0.4
        private const val MAX_VEL = 0.35
        private const val YAW_GAIN = 50.0
        private const val MAX_YAW = 35.0
        private const val H_GAIN = 0.8
        private const val MAX_VZ = 0.5
        private const val ARRIVE_TOL = 0.25
        private const val ARRIVE_SETTLE = 5
        private const val WP_TIMEOUT_TICKS = 120
        private const val LOST_HOLD_TICKS = 20
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    private var mInv: DoubleArray? = null
    private var calibHeading = 0.0

    fun start() {
        FileLogger.write("WaypointFlight.start isFlying=${isFlying()} wp=${waypoints.size}")
        if (!isFlying()) { onState(OrbitState.Error("Drone must be flying first")); return }
        if (waypoints.isEmpty()) { onState(OrbitState.Error("No waypoints")); return }
        onState(OrbitState.Arming)
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    FileLogger.write("WaypointFlight VS enabled")
                    job = scope.launch { run() }
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("WaypointFlight VS FAILED: ${error.description()}")
                    onState(OrbitState.Error("Virtual stick failed: ${error.description()}"))
                }
            })
        } catch (e: Exception) { onState(OrbitState.Error("VS exception: ${e.message}")) }
    }

    fun abort() {
        aborted = true; job?.cancel()
        scope.launch { send(0.0, 0.0, 0.0, 0.0); disableVs() }
        onState(OrbitState.Aborted); FileLogger.write("WaypointFlight aborted")
    }

    fun cleanup() { job?.cancel(); scope.cancel() }

    private suspend fun run() {
        try {
            GimbalController.setPitch(-90.0)
            delay(400)
            if (!calibrate()) { send(0.0,0.0,0.0,0.0); disableVs(); onState(OrbitState.Error("calibration failed")); return }

            val total = waypoints.size
            waypoints.forEachIndexed { i, wp ->
                if (!alive()) return
                val ringObj = OrbitRing(wp.zM, wp.gimbalPitchDeg, OrbitPhase.EQUATORIAL, "WP")
                onState(OrbitState.Flying(ringObj, i, total, i.toDouble() / total * 360.0))
                flyTo(wp)
            }
            send(0.0,0.0,0.0,0.0); disableVs()
            onState(OrbitState.Done); FileLogger.write("WaypointFlight complete")
        } catch (e: Exception) {
            send(0.0,0.0,0.0,0.0); disableVs()
            FileLogger.write("WaypointFlight error: ${e.message}")
            onState(OrbitState.Error(e.message ?: "error"))
        }
    }

    private fun alive() = scope.isActive && !aborted

    private suspend fun calibrate(): Boolean {
        calibHeading = getHeadingDeg()
        val o0 = waitOffset() ?: return false
        repeat(NUDGE_TICKS) { if (!alive()) return false; send(NUDGE_VEL, 0.0, 0.0, 0.0); delay(TICK_MS) }
        send(0.0,0.0,0.0,0.0); delay(300)
        val oF = waitOffset() ?: return false
        val dF = doubleArrayOf(oF.first - o0.first, oF.second - o0.second)
        repeat(NUDGE_TICKS) { if (!alive()) return false; send(0.0, NUDGE_VEL, 0.0, 0.0); delay(TICK_MS) }
        send(0.0,0.0,0.0,0.0); delay(300)
        val oR = waitOffset() ?: return false
        val dR = doubleArrayOf(oR.first - oF.first, oR.second - oF.second)
        FileLogger.write("WP calib dF=(%.3f,%.3f) dR=(%.3f,%.3f)".format(dF[0],dF[1],dR[0],dR[1]))
        if (hypot(dF[0],dF[1]) < MIN_NUDGE || hypot(dR[0],dR[1]) < MIN_NUDGE) return false
        val det = dF[0]*dR[1] - dR[0]*dF[1]
        if (kotlin.math.abs(det) < 1e-6) return false
        mInv = doubleArrayOf(dR[1]/det, -dR[0]/det, -dF[1]/det, dF[0]/det)
        return true
    }

    private suspend fun flyTo(wp: CaptureWaypoint) {
        val inv = mInv ?: return
        val tx = -wp.xM; val ty = -wp.yM    // target center-offset
        var settle = 0; var lost = 0; var t = 0
        while (alive() && t < WP_TIMEOUT_TICKS) {
            val m = getCenterOffsetM()
            if (m == null) {
                lost++; send(0.0, 0.0, 0.0, heightHold(wp.zM))
                if (lost > LOST_HOLD_TICKS) { FileLogger.write("WP${wp.seq}: markers lost, skipping"); return }
                delay(TICK_MS); t++; continue
            }
            lost = 0
            val ex = tx - m.first; val ey = ty - m.second
            val dist = hypot(ex, ey)
            // body command in calibration frame, then rotate by live heading delta
            val f0 = inv[0]*ex + inv[1]*ey
            val r0 = inv[2]*ex + inv[3]*ey
            val dRad = Math.toRadians(getHeadingDeg() - calibHeading)
            val f = f0*cos(dRad) + r0*sin(dRad)
            val r = -f0*sin(dRad) + r0*cos(dRad)
            // yaw to face center: center dir in current body frame
            val cf = m.first*cos(dRad) + m.second*sin(dRad)
            val cr = -m.first*sin(dRad) + m.second*cos(dRad)
            val yawRate = (-YAW_GAIN * cr).coerceIn(-MAX_YAW, MAX_YAW)
            send(
                forward = (POS_GAIN*f).coerceIn(-MAX_VEL, MAX_VEL),
                right   = (POS_GAIN*r).coerceIn(-MAX_VEL, MAX_VEL),
                yawRate = yawRate,
                vertical = heightHold(wp.zM)
            )
            if (dist < ARRIVE_TOL) { settle++; if (settle >= ARRIVE_SETTLE) break } else settle = 0
            delay(TICK_MS); t++
        }
        // arrived → frame the object at this waypoint's gimbal pitch briefly
        GimbalController.setPitch(wp.gimbalPitchDeg)
        var h = 0
        while (alive() && h < 12) { send(0.0, 0.0, 0.0, heightHold(wp.zM)); delay(TICK_MS); h++ }
        GimbalController.setPitch(-90.0)   // back down for marker positioning to next wp
        FileLogger.write("WP${wp.seq} reached")
    }

    private fun heightHold(targetZ: Double) =
        (H_GAIN * (targetZ - getDroneAlt())).coerceIn(-MAX_VZ, MAX_VZ)

    private suspend fun waitOffset(): Pair<Double, Double>? {
        repeat(15) { val o = getCenterOffsetM(); if (o != null) return o; if (!alive()) return null; delay(TICK_MS) }
        return null
    }

    private fun send(forward: Double, right: Double, yawRate: Double, vertical: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward; roll = right; yaw = yawRate; verticalThrottle = vertical
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) { Log.w(TAG, "stick: ${e.message}") }
    }

    private fun disableVs() {
        try { VirtualStickManager.getInstance().disableVirtualStick(null) } catch (_: Exception) {}
    }
}
