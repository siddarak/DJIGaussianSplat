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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Gimbal-locked-on-center orbit (indoor, no GPS, VPS).
 *
 * The camera ALWAYS aims at the center, so the markers around the object stay
 * in frame from any orbit position — fixing the "markers lost at radius" failure
 * proven in the flight log. Control is physically grounded, no nudge calibration:
 *
 *   - getCenterVecCam() = average marker position in the CAMERA frame (x right,
 *     y down, z forward). When the camera faces the center, the center is ~ahead.
 *   - YAW: drive the center's horizontal image offset (x) to 0  → face the center
 *   - FORWARD: hold the target slant distance to center (d → dTarget)  → radius
 *   - RIGHT: constant crab speed  → traverse the circle
 *   - VERTICAL: hold the ring height (downward sensor)
 *   - GIMBAL pitch: aimed at the center each tick from height & radius
 *
 * If markers are briefly lost it holds; if lost too long it stops (safe).
 */
class GimbalCenterOrbitExecutor(
    private val rings: List<EditableRing>,
    private val objectCenterZ: Double,
    private val getCenterVecCam: () -> Triple<Double, Double, Double>?,  // avg marker tvec (m)
    private val getDroneAlt: () -> Double,
    private val isFlying: () -> Boolean,
    private val onState: (OrbitState) -> Unit
) {
    companion object {
        private const val TAG = "GimbalOrbit"
        private const val TICK_HZ = 10
        private const val TICK_MS = (1000 / TICK_HZ).toLong()
        private const val CRAB_MS = 0.35           // tangential speed
        private const val YAW_GAIN = 70.0          // deg/s per meter of horizontal center offset
        private const val MAX_YAW = 40.0
        private const val FWD_GAIN = 0.5           // m/s per meter of distance error
        private const val MAX_FWD = 0.4
        private const val H_GAIN = 0.8
        private const val MAX_VZ = 0.5
        private const val H_TOL = 0.15
        private const val CLIMB_TIMEOUT = 200
        private const val LOST_HOLD_TICKS = 25
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    @Volatile private var aborted = false

    fun start() {
        FileLogger.write("GimbalOrbit.start isFlying=${isFlying()} rings=${rings.size} centerZ=$objectCenterZ")
        if (!isFlying()) { onState(OrbitState.Error("Drone must be flying first")); return }
        if (rings.isEmpty()) { onState(OrbitState.Error("No rings")); return }
        onState(OrbitState.Arming)
        try {
            VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                    FileLogger.write("GimbalOrbit VS enabled")
                    job = scope.launch { run() }
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("GimbalOrbit VS FAILED: ${error.description()}")
                    onState(OrbitState.Error("Virtual stick failed: ${error.description()}"))
                }
            })
        } catch (e: Exception) { onState(OrbitState.Error("VS exception: ${e.message}")) }
    }

    fun abort() {
        aborted = true; job?.cancel()
        scope.launch { send(0.0, 0.0, 0.0, 0.0); disableVs() }
        onState(OrbitState.Aborted); FileLogger.write("GimbalOrbit aborted")
    }

    fun cleanup() { job?.cancel(); scope.cancel() }

    private suspend fun run() {
        try {
            rings.sortedBy { it.heightAboveFloorM }.forEachIndexed { i, ring ->
                if (!alive()) return
                val radius = ((ring.semiMajorM + ring.semiMinorM) / 2.0).coerceAtLeast(0.6)
                aimGimbal(ring.heightAboveFloorM, radius)
                climbTo(ring.heightAboveFloorM, radius)
                if (!alive()) return
                orbitRing(ring, radius, i)
            }
            send(0.0, 0.0, 0.0, 0.0); disableVs()
            onState(OrbitState.Done); FileLogger.write("GimbalOrbit complete")
        } catch (e: Exception) {
            send(0.0, 0.0, 0.0, 0.0); disableVs()
            FileLogger.write("GimbalOrbit error: ${e.message}")
            onState(OrbitState.Error(e.message ?: "error"))
        }
    }

    private fun alive() = scope.isActive && !aborted

    private fun aimGimbal(height: Double, radius: Double) {
        val pitch = -Math.toDegrees(atan2(height - objectCenterZ, radius)).coerceIn(-90.0, 35.0)
        GimbalController.setPitch(pitch)
    }

    private suspend fun climbTo(targetH: Double, radius: Double) {
        onState(OrbitState.Climbing(getDroneAlt(), targetH))
        var t = 0
        while (alive() && t < CLIMB_TIMEOUT) {
            val err = targetH - getDroneAlt()
            if (kotlin.math.abs(err) < H_TOL) break
            aimGimbal(getDroneAlt(), radius)
            // keep facing center while climbing
            val vec = getCenterVecCam()
            val yaw = if (vec != null) (-YAW_GAIN * vec.first).coerceIn(-MAX_YAW, MAX_YAW) else 0.0
            send(0.0, 0.0, yaw, (H_GAIN * err).coerceIn(-MAX_VZ, MAX_VZ))
            t++; delay(TICK_MS)
        }
        FileLogger.write("GimbalOrbit climbTo %.2f done (alt=%.2f)".format(targetH, getDroneAlt()))
    }

    private suspend fun orbitRing(ring: EditableRing, radius: Double, idx: Int) {
        val dTarget = sqrt(radius * radius + (ring.heightAboveFloorM - objectCenterZ).let { it * it })
        // time to complete a circle at crab speed
        val totalTicks = ((2 * Math.PI * radius / CRAB_MS) * TICK_HZ).toInt().coerceIn(60, 1200)
        val ringObj = OrbitRing(ring.heightAboveFloorM, ring.gimbalPitchDeg, OrbitPhase.EQUATORIAL, "R${idx + 1}")
        var lost = 0
        FileLogger.write("GimbalOrbit ring${idx + 1} R=%.2f dTarget=%.2f ticks=%d".format(radius, dTarget, totalTicks))
        for (i in 0 until totalTicks) {
            if (!alive()) return
            aimGimbal(getDroneAlt(), radius)
            val vec = getCenterVecCam()
            val vz = (H_GAIN * (ring.heightAboveFloorM - getDroneAlt())).coerceIn(-MAX_VZ, MAX_VZ)
            if (vec == null) {
                lost++; send(0.0, 0.0, 0.0, vz)
                if (lost > LOST_HOLD_TICKS) { FileLogger.write("ring${idx + 1}: center lost, stopping ring"); return }
                delay(TICK_MS); continue
            }
            lost = 0
            val (cx, cy, cz) = vec
            val d = sqrt(cx * cx + cy * cy + cz * cz)
            val yawRate = (-YAW_GAIN * cx).coerceIn(-MAX_YAW, MAX_YAW)   // face center
            val fwd = (FWD_GAIN * (d - dTarget)).coerceIn(-MAX_FWD, MAX_FWD)  // hold radius
            send(forward = fwd, right = CRAB_MS, yawRate = yawRate, vertical = vz)
            if (i % 15 == 0) FileLogger.write(
                "ring${idx + 1} %d/%d d=%.2f dT=%.2f cx=%.2f fwd=%.2f yaw=%.0f alt=%.2f"
                    .format(i, totalTicks, d, dTarget, cx, fwd, yawRate, getDroneAlt())
            )
            onState(OrbitState.Flying(ringObj, idx, rings.size, i.toDouble() / totalTicks * 360.0))
            delay(TICK_MS)
        }
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
