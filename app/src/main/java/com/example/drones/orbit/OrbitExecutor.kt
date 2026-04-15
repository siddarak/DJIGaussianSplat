package com.example.drones.orbit

import android.util.Log
import com.example.drones.sdk.GimbalController
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Executes a hemispherical orbit mission via MSDK V5 Virtual Stick.
 *
 * Coordinate system: NED Earth frame.
 *   pitch          = North velocity (m/s, positive = fly North)
 *   roll           = East velocity  (m/s, positive = fly East)
 *   yaw            = absolute heading (degrees, 0 = North, CW)
 *   verticalThrottle = vertical velocity (m/s, positive = climb)
 *
 * Obstacle avoidance: if forward sensor < SAFETY_CLEARANCE_M, the effective
 * orbit radius is temporarily expanded to push the drone outward.
 * Once clearance is restored, radius smoothly returns to nominal.
 *
 * Auto-land override: landing protection and downward obstacle avoidance
 * are disabled during the low orbit ring, then re-enabled immediately after.
 *
 * Safety hard floor: drone will never be commanded below MIN_ALTITUDE_M
 * regardless of mission altitude.
 */
class OrbitExecutor(
    private val mission: OrbitMission,
    private val rings: List<OrbitRing>,               // from MissionPlanner.executionOrder()
    private val getForwardObstacleDist: () -> Float,  // live sensor reading, meters
    private val getDroneAlt: () -> Double,            // live altitude
    private val onState: (OrbitState) -> Unit
) {

    companion object {
        private const val TAG = "OrbitExecutor"
        private const val TICK_HZ = 10
        private const val TICK_MS = (1000 / TICK_HZ).toLong()
        private const val SAFETY_CLEARANCE_M = 1.5f   // expand radius if obstacle closer than this
        private const val RADIUS_EXPAND_SCALE = 1.5    // how aggressively to push outward
        private const val MIN_ALTITUDE_M = 0.3         // absolute hard floor
        private const val MAX_VELOCITY_MS = 8.0        // MSDK V5 limit
        private const val ALTITUDE_TOLERANCE_M = 0.15  // consider altitude reached within this
        private const val TOP_SHOT_YAW_SPEED = 15.0    // deg/s for overhead rotation
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var missionJob: Job? = null

    // Smoothed effective radius — avoids jitter when obstacle clearance fluctuates
    private var smoothRadius = mission.orbitRadius

    // --- Public API ---

    fun start() {
        onState(OrbitState.Arming)
        enableVirtualStick {
            missionJob = scope.launch { runMission() }
        }
    }

    /**
     * Immediate abort — disables virtual stick, returns control to pilot.
     * Safe to call from any thread.
     */
    fun abort() {
        Log.w(TAG, "Mission aborted")
        missionJob?.cancel()
        scope.launch {
            restoreProtection()
            disableVirtualStick()
            onState(OrbitState.Aborted)
        }
    }

    fun cleanup() {
        missionJob?.cancel()
        scope.cancel()
    }

    // --- Mission execution ---

    private suspend fun runMission() {
        try {
            for ((index, ring) in rings.withIndex()) {
                if (!scope.isActive) return

                // Climb/descend to ring altitude (while continuing to orbit)
                val currentAlt = getDroneAlt()
                if (abs(currentAlt - ring.altitude) > ALTITUDE_TOLERANCE_M) {
                    onState(OrbitState.Climbing(currentAlt, ring.altitude))
                    transitionAltitude(ring)
                }

                // Set gimbal pitch for this ring
                GimbalController.setPitch(ring.gimbalPitch)

                // Handle auto-land override for low ring
                if (ring.phase == OrbitPhase.LOW) {
                    disableLandingProtection()
                }

                if (ring.phase == OrbitPhase.TOP_SHOT) {
                    executeTopShot()
                } else {
                    executeSingleOrbit(ring, index)
                }

                // Re-enable protection after low ring
                if (ring.phase == OrbitPhase.LOW) {
                    restoreProtection()
                }
            }

            disableVirtualStick()
            onState(OrbitState.Done)
            Log.i(TAG, "Mission complete")

        } catch (e: Exception) {
            Log.e(TAG, "Mission error: ${e.message}")
            restoreProtection()
            disableVirtualStick()
            onState(OrbitState.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Fly one complete 360° circle at the ring's altitude.
     * Obstacle avoidance expands radius dynamically.
     */
    private suspend fun executeSingleOrbit(ring: OrbitRing, ringIndex: Int) {
        val startRad = Math.toRadians(mission.startAngleDeg)
        val sign = if (mission.direction == OrbitDirection.CLOCKWISE) 1.0 else -1.0
        val angularVel = mission.speed / mission.orbitRadius  // rad/s
        var angle = startRad
        val endAngle = startRad + sign * 2 * PI
        smoothRadius = mission.orbitRadius

        while (scope.isActive) {
            // Check completion
            val traveled = sign * (angle - startRad)
            if (traveled >= 2 * PI - 0.01) break

            // Obstacle avoidance — expand radius if something is ahead
            val fwdDist = getForwardObstacleDist()
            if (fwdDist > 0 && fwdDist < SAFETY_CLEARANCE_M) {
                val expansion = (SAFETY_CLEARANCE_M - fwdDist) * RADIUS_EXPAND_SCALE
                smoothRadius = mission.orbitRadius + expansion
            } else {
                // Smoothly return to nominal radius
                smoothRadius += (mission.orbitRadius - smoothRadius) * 0.15
            }

            // Velocity in Earth NED frame
            val vNorth = -mission.speed * sin(angle) * sign
            val vEast  =  mission.speed * cos(angle) * sign

            // Yaw: drone faces toward center (angle + 180°)
            val yaw = normalizeDeg(Math.toDegrees(angle) + 180.0)

            sendVirtualStick(vNorth, vEast, yaw, verticalVelocity = 0.0)

            val progressDeg = (traveled / (2 * PI) * 360.0).coerceIn(0.0, 360.0)
            onState(OrbitState.Flying(ring, ringIndex, rings.size, progressDeg))

            angle += sign * angularVel * (TICK_MS / 1000.0)
            delay(TICK_MS)
        }
    }

    /**
     * Transition from current altitude to ring altitude while continuing to orbit.
     * Keeps the object centered during the climb/descent.
     */
    private suspend fun transitionAltitude(targetRing: OrbitRing) {
        val startAlt = getDroneAlt()
        val altDiff = targetRing.altitude - startAlt
        val climbRate = if (altDiff > 0) 0.6 else -0.5   // slower descent for safety
        val ticksNeeded = (abs(altDiff) / abs(climbRate) * TICK_HZ).toInt() + 5
        var angle = Math.toRadians(mission.startAngleDeg)
        val sign = if (mission.direction == OrbitDirection.CLOCKWISE) 1.0 else -1.0
        val angularVel = mission.speed / mission.orbitRadius

        for (i in 0 until ticksNeeded) {
            if (!scope.isActive) return
            val currentAlt = getDroneAlt()
            if (abs(currentAlt - targetRing.altitude) < ALTITUDE_TOLERANCE_M) break

            val vNorth = -mission.speed * sin(angle) * sign
            val vEast  =  mission.speed * cos(angle) * sign
            val yaw = normalizeDeg(Math.toDegrees(angle) + 180.0)
            val vVert = climbRate.coerceIn(-2.0, 2.0)

            sendVirtualStick(vNorth, vEast, yaw, vVert)

            // Interpolate gimbal pitch during transition
            val progress = (i.toDouble() / ticksNeeded).coerceIn(0.0, 1.0)
            val currentPitch = GimbalController.currentPitchEstimate +
                    (targetRing.gimbalPitch - GimbalController.currentPitchEstimate) * progress
            GimbalController.setPitch(currentPitch)

            angle += sign * angularVel * (TICK_MS / 1000.0)
            delay(TICK_MS)
        }
    }

    /**
     * Top shot: fly to overhead position (not an orbit), rotate 360° yawing slowly.
     */
    private suspend fun executeTopShot() {
        onState(OrbitState.TopShot)
        GimbalController.setPitch(-85.0)

        // Hover and rotate 360°
        val totalTicks = (360.0 / TOP_SHOT_YAW_SPEED * TICK_HZ).toInt()
        var yaw = normalizeDeg(Math.toDegrees(Math.toRadians(mission.startAngleDeg)) + 180.0)

        for (i in 0 until totalTicks) {
            if (!scope.isActive) return
            sendVirtualStick(0.0, 0.0, yaw, 0.0)
            yaw = normalizeDeg(yaw + TOP_SHOT_YAW_SPEED / TICK_HZ)
            delay(TICK_MS)
        }
    }

    // --- Virtual Stick ---

    private fun enableVirtualStick(onSuccess: () -> Unit) {
        try {
            VirtualStickManager.getInstance().enableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        // Earth frame, velocity mode for pitch/roll, angle mode for yaw
                        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                        Log.i(TAG, "Virtual stick enabled")
                        onSuccess()
                    }
                    override fun onFailure(error: IDJIError) {
                        onState(OrbitState.Error("Virtual stick failed: ${error.description()}"))
                    }
                }
            )
        } catch (e: Exception) {
            onState(OrbitState.Error("Virtual stick exception: ${e.message}"))
        }
    }

    private fun disableVirtualStick() {
        try {
            VirtualStickManager.getInstance().disableVirtualStick(null)
            Log.i(TAG, "Virtual stick disabled")
        } catch (e: Exception) {
            Log.w(TAG, "disableVirtualStick: ${e.message}")
        }
    }

    private fun sendVirtualStick(
        vNorth: Double, vEast: Double,
        yawDeg: Double, verticalVelocity: Double
    ) {
        // Hard floor — never command descent below minimum
        val safeVertical = if (getDroneAlt() <= MIN_ALTITUDE_M && verticalVelocity < 0)
            0.0 else verticalVelocity

        try {
            val data = VirtualStickFlightControlParam()
            data.pitch = vNorth.coerceIn(-MAX_VELOCITY_MS, MAX_VELOCITY_MS)
            data.roll  = vEast.coerceIn(-MAX_VELOCITY_MS, MAX_VELOCITY_MS)
            data.yaw   = yawDeg
            data.verticalThrottle = safeVertical.coerceIn(-4.0, 4.0)
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(data)
        } catch (e: Exception) {
            Log.w(TAG, "sendVirtualStick: ${e.message}")
        }
    }

    // --- Safety overrides for low orbit ---

    private fun disableLandingProtection() {
        Log.w(TAG, "Disabling landing protection for low orbit")
        try {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightAssistantKey.KeyLandingProtectionEnabled),
                false, null
            )
            KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightAssistantKey.KeyDownwardsAvoidanceEnable),
                false, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "disableLandingProtection: ${e.message}")
        }
    }

    private fun restoreProtection() {
        Log.i(TAG, "Restoring landing protection")
        try {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightAssistantKey.KeyLandingProtectionEnabled),
                true, null
            )
            KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightAssistantKey.KeyDownwardsAvoidanceEnable),
                true, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "restoreProtection: ${e.message}")
        }
    }

    // --- Helpers ---

    private fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
