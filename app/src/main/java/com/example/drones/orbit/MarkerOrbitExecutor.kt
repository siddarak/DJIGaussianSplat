package com.example.drones.orbit

import android.util.Log
import com.example.drones.sdk.GimbalController
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Body-frame orbit for indoor (no-GPS) marker capture. Works on VPS.
 *
 * After auto-center the drone hovers ABOVE the center. For each ring we:
 *  1) gimbal straight down (markers visible), climb to ring height
 *  2) face center + fly out to the ring radius — CLOSED-LOOP on the live
 *     marker-center offset (this is what was missing before)
 *  3) tilt gimbal to the ring pitch (frames the object), then orbit 360° by
 *     crabbing while yawing to keep the nose on center
 *  4) next ring: gimbal back down, re-anchor from markers
 *
 * Heavily logged so a failed run shows exactly where it stopped.
 */
class MarkerOrbitExecutor(
    private val rings: List<EditableRing>,
    private val getDroneAlt: () -> Double,
    private val isFlying: () -> Boolean,
    private val getCenterOffsetM: () -> Pair<Double, Double>?,   // (right, forward) meters, null if markers lost
    private val onState: (OrbitState) -> Unit
) {
    companion object {
        private const val TAG = "MarkerOrbit"
        private const val TICK_HZ = 10
        private const val TICK_MS = (1000 / TICK_HZ).toLong()
        private const val SPEED_MS = 0.4
        private const val HEIGHT_GAIN = 0.8
        private const val MAX_VERT = 0.5
        private const val HEIGHT_TOL = 0.15
        private const val POS_GAIN = 0.5
        private const val MAX_HORIZ = 0.4
        private const val YAW_GAIN = 60.0          // deg/s per meter of lateral offset
        private const val RADIUS_TOL = 0.2
        private const val SETUP_TIMEOUT_TICKS = 250
        private const val CLIMB_TIMEOUT_TICKS = 200
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    fun start() {
        FileLogger.write("MarkerOrbit.start isFlying=${isFlying()} rings=${rings.size}")
        if (!isFlying()) { onState(OrbitState.Error("Drone must be flying first")); return }
        onState(OrbitState.Arming)
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    FileLogger.write("MarkerOrbit VS enabled OK")
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    job = scope.launch { run() }
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("MarkerOrbit VS enable FAILED: ${error.description()}")
                    onState(OrbitState.Error("Virtual stick failed: ${error.description()}"))
                }
            })
        } catch (e: Exception) {
            FileLogger.write("MarkerOrbit VS exception: ${e.message}")
            onState(OrbitState.Error("VS exception: ${e.message}"))
        }
    }

    fun abort() {
        aborted = true
        job?.cancel()
        scope.launch { sendStick(0.0, 0.0, 0.0, 0.0); disableVs() }
        onState(OrbitState.Aborted)
        FileLogger.write("MarkerOrbit aborted")
    }

    fun cleanup() { job?.cancel(); scope.cancel() }

    private suspend fun run() {
        try {
            for (ring in rings.sortedBy { it.heightAboveFloorM }) {
                if (!alive()) return
                val radius = ((ring.semiMajorM + ring.semiMinorM) / 2.0).coerceAtLeast(0.6)
                FileLogger.write("Ring ${ring.index + 1}: radius=%.2f h=%.2f".format(radius, ring.heightAboveFloorM))

                GimbalController.setPitch(-90.0)                 // markers visible for setup
                climbTo(ring.heightAboveFloorM)
                if (!alive()) return

                val positioned = faceCenterAndGoToRadius(radius)
                if (!alive()) return
                if (!positioned) { FileLogger.write("Ring ${ring.index + 1}: setup timed out — skipping"); continue }

                GimbalController.setPitch(ring.gimbalPitchDeg)   // frame the object
                orbitOnce(ring, radius)
            }
            sendStick(0.0, 0.0, 0.0, 0.0); disableVs()
            onState(OrbitState.Done)
            FileLogger.write("MarkerOrbit complete")
        } catch (e: Exception) {
            sendStick(0.0, 0.0, 0.0, 0.0); disableVs()
            FileLogger.write("MarkerOrbit error: ${e.message}")
            onState(OrbitState.Error(e.message ?: "orbit error"))
        }
    }

    private fun alive() = scope.isActive && !aborted

    private suspend fun climbTo(targetH: Double) {
        onState(OrbitState.Climbing(getDroneAlt(), targetH))
        var t = 0
        while (alive() && t < CLIMB_TIMEOUT_TICKS) {
            val err = targetH - getDroneAlt()
            if (abs(err) < HEIGHT_TOL) break
            sendStick(0.0, 0.0, 0.0, (HEIGHT_GAIN * err).coerceIn(-MAX_VERT, MAX_VERT))
            t++; delay(TICK_MS)
        }
        FileLogger.write("climbTo %.2f done (alt=%.2f)".format(targetH, getDroneAlt()))
    }

    /**
     * Closed-loop on the marker center offset (gimbal down): yaw so center is
     * straight ahead, then move forward/back until distance == radius.
     * Returns true when positioned, false on timeout / markers lost.
     */
    private suspend fun faceCenterAndGoToRadius(radius: Double): Boolean {
        var t = 0
        var settle = 0
        while (alive() && t < SETUP_TIMEOUT_TICKS) {
            val off = getCenterOffsetM()
            if (off == null) { sendStick(0.0, 0.0, 0.0, 0.0); t++; delay(TICK_MS); continue }
            val (right, fwd) = off
            val dist = hypot(right, fwd)
            // yaw to bring center straight ahead (drive lateral offset 'right' to 0)
            val yawRate = (-YAW_GAIN * right).coerceIn(-45.0, 45.0)
            // move along forward axis so distance -> radius (facing center: +fwd reduces dist)
            val distErr = dist - radius
            val vFwd = (POS_GAIN * distErr).coerceIn(-MAX_HORIZ, MAX_HORIZ)
            sendStick(forward = vFwd, right = 0.0, yawRateDeg = yawRate, vertical = 0.0)
            if (abs(distErr) < RADIUS_TOL && abs(right) < RADIUS_TOL) {
                settle++; if (settle > 6) { FileLogger.write("positioned at radius (dist=%.2f)".format(dist)); return true }
            } else settle = 0
            t++; delay(TICK_MS)
        }
        return false
    }

    /** 360° circle: crab sideways at constant speed, yaw to keep facing center, hold height. */
    private suspend fun orbitOnce(ring: EditableRing, radius: Double) {
        val omegaDegPerSec = Math.toDegrees(SPEED_MS / radius)
        val totalTicks = ((360.0 / omegaDegPerSec) * TICK_HZ).toInt().coerceAtLeast(40)
        val ringObj = OrbitRing(ring.heightAboveFloorM, ring.gimbalPitchDeg, OrbitPhase.EQUATORIAL, "R${ring.index + 1}")
        FileLogger.write("orbitOnce ring${ring.index + 1} ticks=$totalTicks omega=%.1f/s".format(omegaDegPerSec))
        for (i in 0 until totalTicks) {
            if (!alive()) return
            val vz = (HEIGHT_GAIN * (ring.heightAboveFloorM - getDroneAlt())).coerceIn(-MAX_VERT, MAX_VERT)
            sendStick(forward = 0.0, right = SPEED_MS, yawRateDeg = omegaDegPerSec, vertical = vz)
            onState(OrbitState.Flying(ringObj, ring.index, rings.size, i.toDouble() / totalTicks * 360.0))
            delay(TICK_MS)
        }
    }

    private fun sendStick(forward: Double, right: Double, yawRateDeg: Double, vertical: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward; roll = right; yaw = yawRateDeg; verticalThrottle = vertical
            }
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) {
            Log.w(TAG, "stick: ${e.message}")
        }
    }

    private fun disableVs() {
        try { VirtualStickManager.getInstance().disableVirtualStick(null) }
        catch (e: Exception) { Log.w(TAG, "disableVs: ${e.message}") }
    }
}
