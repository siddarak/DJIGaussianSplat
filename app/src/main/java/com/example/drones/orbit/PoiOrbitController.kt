package com.example.drones.orbit

import com.example.drones.util.FileLogger
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.poi.POIParam
import dji.v5.manager.intelligent.poi.POITarget

/**
 * Outdoor (GPS) orbit using DJI's NATIVE Point-of-Interest mission. DJI flies
 * the circle itself — far more reliable than our virtual-stick orbit — but it
 * requires a GPS location for the target, so it only works outdoors.
 *
 * target = object GPS location (lat/lon/alt), circleSpeed in m/s.
 */
object PoiOrbitController {

    private fun manager() = IntelligentFlightManager.getInstance().poiMissionManager

    fun start(lat: Double, lon: Double, alt: Double, circleSpeedMs: Double,
              onResult: (Boolean, String?) -> Unit) {
        try {
            val target = POITarget().apply {
                targetLocation = LocationCoordinate3D(lat, lon, alt)
                index = 0
            }
            val param = POIParam().apply { circleSpeed = circleSpeedMs }
            manager().startMission(target, param, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    FileLogger.write("POI orbit started @($lat,$lon,$alt) speed=$circleSpeedMs")
                    manager().lockGimbalPitch(true, object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {}
                        override fun onFailure(error: IDJIError) {}
                    })
                    onResult(true, null)
                }
                override fun onFailure(error: IDJIError) {
                    FileLogger.write("POI orbit start FAILED: ${error.description()}")
                    onResult(false, error.description())
                }
            })
        } catch (e: Exception) {
            FileLogger.write("POI orbit exception: ${e.message}")
            onResult(false, e.message)
        }
    }

    fun stop(onResult: (Boolean, String?) -> Unit) {
        try {
            manager().stopMission(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { onResult(true, null) }
                override fun onFailure(error: IDJIError) { onResult(false, error.description()) }
            })
        } catch (e: Exception) { onResult(false, e.message) }
    }
}
