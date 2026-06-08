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

/**
 * Body-frame orbit for indoor (no-GPS) marker capture. Works on VPS.
 *
 * Per ring: climb/descend to the ring height (closed-loop on the downward
 * altitude sensor), set gimbal, then trace a 360° circle by crabbing sideways
 * at constant speed while yawing to keep the nose toward the center
 * (BODY frame + ANGULAR_VELOCITY yaw). Radius = average of the ring's two axes
 * (v1 flies a circle; elliptical pathing is a later refinement).
 *
 * Safety: requires isFlying; abort disables Virtual Stick; slow speeds.
 */
class MarkerOrbitExecutor(
    private val rings: List<EditableRing>,
    private val getDroneAlt: () -> Double,
    private val isFlying: () -> Boolean,
    private val onState: (OrbitState) -> Unit
) {
    companion object {
        private const val TAG = "MarkerOrbit"
        private const val TICK_HZ = 10
        private const val TICK_MS = (1000 / TICK_HZ).toLong()
        private const val SPEED_MS = 0.5            // crab speed, slow for indoor
        private const val HEIGHT_GAIN = 0.8         // vertical m/s per m error
        private const val MAX_VERT = 0.6
        private const val HEIGHT_TOL = 0.15
        private const val CLIMB_TIMEOUT_TICKS = 200
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    fun start() {
        if (!isFlying()) {
            onState(OrbitState.Error("Drone must be flying first"))
            return
        }
        onState(OrbitState.Arming)
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    job = scope.launch { run() }
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("MarkerOrbit VS enable FAILED: ${error.description()}")
                    onState(OrbitState.Error("Virtual stick failed: ${error.description()}"))
                }
            })
        } catch (e: Exception) {
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
                if (!isActive() || aborted) return
                GimbalController.setPitch(ring.gimbalPitchDeg)
                climbTo(ring.heightAboveFloorM)
                if (!isActive() || aborted) return
                orbitOnce(ring)
            }
            sendStick(0.0, 0.0, 0.0, 0.0)
            disableVs()
            onState(OrbitState.Done)
            FileLogger.write("MarkerOrbit complete")
        } catch (e: Exception) {
            sendStick(0.0, 0.0, 0.0, 0.0); disableVs()
            onState(OrbitState.Error(e.message ?: "orbit error"))
        }
    }

    private fun isActive() = scope.isActive && !aborted

    /** Hold/seek a target height using the downward altitude sensor (closed loop). */
    private suspend fun climbTo(targetH: Double) {
        val from = getDroneAlt()
        onState(OrbitState.Climbing(from, targetH))
        var ticks = 0
        while (isActive() && ticks < CLIMB_TIMEOUT_TICKS) {
            val err = targetH - getDroneAlt()
            if (abs(err) < HEIGHT_TOL) break
            val vz = (HEIGHT_GAIN * err).coerceIn(-MAX_VERT, MAX_VERT)
            sendStick(0.0, 0.0, 0.0, vz)
            ticks++
            delay(TICK_MS)
        }
    }

    /** One 360° circle: crab sideways + yaw to keep facing center, hold height. */
    private suspend fun orbitOnce(ring: EditableRing) {
        val radius = ((ring.semiMajorM + ring.semiMinorM) / 2.0).coerceAtLeast(0.6)
        val omegaDegPerSec = Math.toDegrees(SPEED_MS / radius)   // yaw rate to keep facing center
        val totalTicks = ((360.0 / (omegaDegPerSec)) * TICK_HZ).toInt().coerceAtLeast(40)
        val ringObj = OrbitRing(ring.heightAboveFloorM, ring.gimbalPitchDeg, OrbitPhase.EQUATORIAL, "R${ring.index + 1}")

        for (i in 0 until totalTicks) {
            if (!isActive()) return
            // hold height each tick
            val vz = (HEIGHT_GAIN * (ring.heightAboveFloorM - getDroneAlt())).coerceIn(-MAX_VERT, MAX_VERT)
            // crab right at constant speed, yaw to keep nose toward center
            sendStick(forward = 0.0, right = SPEED_MS, yawRateDeg = omegaDegPerSec, vertical = vz)
            val progress = (i.toDouble() / totalTicks * 360.0)
            onState(OrbitState.Flying(ringObj, ring.index, rings.size, progress))
            delay(TICK_MS)
        }
    }

    private fun sendStick(forward: Double, right: Double, yawRateDeg: Double, vertical: Double) {
        try {
            val data = VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY   // indoor — no GPS
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                pitch = forward
                roll = right
                yaw = yawRateDeg
                verticalThrottle = vertical
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
