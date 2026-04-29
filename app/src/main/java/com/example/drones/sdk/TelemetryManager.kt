package com.example.drones.sdk

import android.util.Log
import com.example.drones.util.FileLogger
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.v5.manager.KeyManager
import kotlin.math.sqrt

/**
 * Subscribes to all drone telemetry via MSDK V5 KeyManager.
 *
 * Why KeyManager over direct component access:
 * - Thread-safe by design — callbacks arrive on a dedicated MSDK thread
 * - Consistent API across FC, battery, gimbal, RC
 * - Supports listen (continuous) and getValue (one-shot)
 * - All listeners are cancelled via cancelListen(holder) — no manual tracking needed
 *
 * Usage lifecycle:
 *   startListening() → drone connected
 *   stopListening()  → drone disconnected OR ViewModel cleared
 */
class TelemetryManager(
    private val onTelemetryUpdate: (TelemetryUpdate) -> Unit
) {
    companion object {
        private const val TAG = "TelemetryManager"
    }

    private val keyManager: KeyManager get() = KeyManager.getInstance()

    fun startListening() {
        Log.i(TAG, "Starting telemetry subscriptions")
        listenAltitude()
        listenLocation()
        listenVelocity()
        listenAttitude()
        listenFlyingState()
        listenFlightMode()
        listenSatelliteCount()
        listenBatteryPercent()
        listenBatteryTemp()
        listenGimbal()
        listenSignal()
        listenForwardObstacle()
        listenLandingConfirmation()
    }

    /**
     * Cancel all listeners registered by this instance.
     * Uses 'this' as the holder key — MSDK cancels all keys registered under it.
     */
    fun stopListening() {
        Log.i(TAG, "Stopping all telemetry subscriptions")
        try {
            keyManager.cancelListen(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling listeners: ${e.message}")
        }
    }

    // --- Flight Controller ---

    private fun listenAltitude() = safeSubscribe("altitude") {
        val key = KeyTools.createKey(FlightControllerKey.KeyAltitude)
        keyManager.listen(key, this) { _, v ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.Altitude(it.toDouble())) }
        }
    }

    private fun listenLocation() = safeSubscribe("location") {
        val key = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
        // Seed initial value (listen only fires on CHANGES — drone may already have GPS lock)
        try {
            val current: LocationCoordinate3D? = keyManager.getValue(key)
            if (current != null) {
                FileLogger.write("GPS initial: lat=${current.latitude} lon=${current.longitude} alt=${current.altitude}")
                onTelemetryUpdate(TelemetryUpdate.Location(current.latitude, current.longitude, current.altitude))
            } else {
                FileLogger.write("GPS initial: null (no fix yet)")
            }
        } catch (e: Exception) {
            FileLogger.write("GPS getValue fail: ${e.message}")
        }
        keyManager.listen(key, this) { _, v ->
            v?.let { loc: LocationCoordinate3D ->
                FileLogger.write("GPS update: lat=${loc.latitude} lon=${loc.longitude}")
                onTelemetryUpdate(TelemetryUpdate.Location(loc.latitude, loc.longitude, loc.altitude))
            }
        }
    }

    private fun listenVelocity() = safeSubscribe("velocity") {
        val key = KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)
        keyManager.listen(key, this) { _, v ->
            v?.let { vel: Velocity3D ->
                val horizontal = sqrt(vel.x * vel.x + vel.y * vel.y)
                onTelemetryUpdate(TelemetryUpdate.Velocity(horizontal, vel.z))
            }
        }
    }

    private fun listenAttitude() = safeSubscribe("attitude") {
        val key = KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude)
        keyManager.listen(key, this) { _, v ->
            v?.let { att: Attitude ->
                onTelemetryUpdate(TelemetryUpdate.Heading(att.yaw))
            }
        }
    }

    private fun listenFlyingState() = safeSubscribe("flyingState") {
        val key = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
        keyManager.listen(key, this) { _, v ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.FlyingState(it)) }
        }
    }

    private fun listenFlightMode() = safeSubscribe("flightMode") {
        val key = KeyTools.createKey(FlightControllerKey.KeyFlightMode)
        keyManager.listen(key, this) { _, v ->
            v?.let { mode: FlightMode ->
                onTelemetryUpdate(TelemetryUpdate.FlightModeUpdate(mode.name))
            }
        }
    }

    private fun listenSatelliteCount() = safeSubscribe("satellites") {
        val key = KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)
        try {
            val current: Int? = keyManager.getValue(key)
            FileLogger.write("Satellites initial: $current")
            if (current != null) onTelemetryUpdate(TelemetryUpdate.Satellites(current))
        } catch (e: Exception) {
            FileLogger.write("Satellites getValue fail: ${e.message}")
        }
        keyManager.listen(key, this) { _, v ->
            v?.let {
                FileLogger.write("Satellites update: $it")
                onTelemetryUpdate(TelemetryUpdate.Satellites(it))
            }
        }
    }

    // --- Battery ---

    private fun listenBatteryPercent() = safeSubscribe("batteryPercent") {
        val key = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent)
        keyManager.listen(key, this) { _, v ->
            v?.let { pct: Int ->
                onTelemetryUpdate(TelemetryUpdate.Battery(
                    percent = pct,
                    lowWarning = pct <= 20,
                    criticalWarning = pct <= 10
                ))
            }
        }
    }

    private fun listenBatteryTemp() = safeSubscribe("batteryTemp") {
        val key = KeyTools.createKey(BatteryKey.KeyBatteryTemperature)
        keyManager.listen(key, this) { _, v ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.BatteryTemp(it.toDouble())) }
        }
    }

    // --- Gimbal ---

    private fun listenGimbal() = safeSubscribe("gimbal") {
        val key = KeyTools.createKey(GimbalKey.KeyGimbalAttitude)
        keyManager.listen(key, this) { _, v ->
            v?.let { att: Attitude ->
                onTelemetryUpdate(TelemetryUpdate.GimbalAttitude(att.pitch, att.yaw, att.roll))
            }
        }
    }

    // --- Perception / Obstacle ---

    /**
     * Forward obstacle distance from binocular vision sensors.
     * Mini 4 Pro range: 0.38–20m. Returns 0 when out of range or no obstacle.
     * Key: PerceptionKey.KeyForwardObstacleDistance (verify against SDK jar).
     */
    private fun listenForwardObstacle() = safeSubscribe("forwardObstacle") {
        val key = KeyTools.createKey(FlightAssistantKey.KeyOmniHorizontalRadarDistance)
        keyManager.listen(key, this) { _, v: Double? ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.ForwardObstacle(it.toFloat())) }
        }
    }

    // --- AirLink / Signal ---

    private fun listenLandingConfirmation() = safeSubscribe("landingConfirmation") {
        val key = KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded)
        keyManager.listen(key, this) { _, v ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.LandingConfirmation(it)) }
        }
    }

    private fun listenSignal() = safeSubscribe("signal") {
        val key = KeyTools.createKey(AirLinkKey.KeyUpLinkQuality)
        keyManager.listen(key, this) { _, v ->
            v?.let { onTelemetryUpdate(TelemetryUpdate.Signal(it)) }
        }
    }

    /**
     * Wraps each subscription in try-catch.
     * A failed subscription degrades that one telemetry field but doesn't
     * crash the app or prevent other fields from working.
     */
    private inline fun safeSubscribe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to $name: ${e.message}")
        }
    }
}

/**
 * Sealed class for type-safe telemetry updates.
 * Each variant carries only its changed data — ViewModel does targeted state copies.
 */
sealed class TelemetryUpdate {
    data class Altitude(val meters: Double) : TelemetryUpdate()
    data class Location(val latitude: Double, val longitude: Double, val altitude: Double) : TelemetryUpdate()
    data class Velocity(val horizontal: Double, val vertical: Double) : TelemetryUpdate()
    data class Heading(val degrees: Double) : TelemetryUpdate()
    data class FlyingState(val isFlying: Boolean) : TelemetryUpdate()
    data class FlightModeUpdate(val mode: String) : TelemetryUpdate()
    data class Satellites(val count: Int) : TelemetryUpdate()
    data class Battery(val percent: Int, val lowWarning: Boolean, val criticalWarning: Boolean) : TelemetryUpdate()
    data class BatteryTemp(val celsius: Double) : TelemetryUpdate()
    data class GimbalAttitude(val pitch: Double, val yaw: Double, val roll: Double) : TelemetryUpdate()
    data class Signal(val quality: Int) : TelemetryUpdate()
    data class ForwardObstacle(val distanceM: Float) : TelemetryUpdate()
    data class LandingConfirmation(val needed: Boolean) : TelemetryUpdate()
}
