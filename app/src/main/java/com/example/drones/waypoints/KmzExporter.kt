package com.example.drones.waypoints

import android.content.Context
import android.util.Log
import com.example.drones.util.FileLogger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exports a marker-local WaypointPath to a DJI WPMZ ".kmz" wayline file so an
 * OUTDOOR (GPS) flight can run on DJI's native waypoint engine.
 *
 * Marker-local meters are converted to GPS using a supplied anchor:
 *   originLat/originLon = GPS of the marker-frame origin (the scan center),
 *   frameYawDeg         = rotation of the marker X axis relative to true north.
 *
 * EXPERIMENTAL: the WPMZ schema is DJI-specific; validate the generated .kmz in
 * DJI Pilot/Fly before flying it. Generating the file is harmless (no flight).
 */
object KmzExporter {

    private const val WPML_NS = "http://www.dji.com/wpmz/1.0.2"
    private const val KML_NS = "http://www.opengis.net/kml/2.2"

    fun export(
        context: Context,
        path: WaypointPath,
        originLat: Double,
        originLon: Double,
        frameYawDeg: Double,
        autoSpeedMs: Double = 2.0
    ): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "waypoints/kmz").apply { mkdirs() }
            val out = File(dir, "${path.id}.kmz")
            val yaw = Math.toRadians(frameYawDeg)
            val cosL = cos(Math.toRadians(originLat))

            fun toGps(xM: Double, yM: Double): Pair<Double, Double> {
                val east = xM * cos(yaw) - yM * sin(yaw)
                val north = xM * sin(yaw) + yM * cos(yaw)
                val lat = originLat + north / 111111.0
                val lon = originLon + east / (111111.0 * cosL)
                return lat to lon
            }

            val placemarks = StringBuilder()
            path.waypoints.forEach { w ->
                val (lat, lon) = toGps(w.xM, w.yM)
                placemarks.append(
                    """
        <Placemark>
          <Point><coordinates>$lon,$lat</coordinates></Point>
          <wpml:index>${w.seq}</wpml:index>
          <wpml:executeHeight>${"%.2f".format(w.zM)}</wpml:executeHeight>
          <wpml:waypointSpeed>${"%.2f".format(autoSpeedMs)}</wpml:waypointSpeed>
          <wpml:waypointHeadingParam>
            <wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode>
            <wpml:waypointHeadingAngle>${"%.0f".format(w.yawDeg)}</wpml:waypointHeadingAngle>
          </wpml:waypointHeadingParam>
          <wpml:waypointGimbalHeadingParam>
            <wpml:waypointGimbalPitchAngle>${"%.0f".format(w.gimbalPitchDeg)}</wpml:waypointGimbalPitchAngle>
          </wpml:waypointGimbalHeadingParam>
        </Placemark>"""
                )
            }

            val waylines = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="$KML_NS" xmlns:wpml="$WPML_NS">
  <Document>
    <wpml:missionConfig>
      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
      <wpml:finishAction>goHome</wpml:finishAction>
      <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>
      <wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>
      <wpml:takeOffSecurityHeight>2</wpml:takeOffSecurityHeight>
      <wpml:globalTransitionalSpeed>${"%.1f".format(autoSpeedMs)}</wpml:globalTransitionalSpeed>
    </wpml:missionConfig>
    <Folder>
      <wpml:templateId>0</wpml:templateId>
      <wpml:waylineId>0</wpml:waylineId>
      <wpml:autoFlightSpeed>${"%.1f".format(autoSpeedMs)}</wpml:autoFlightSpeed>
      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>$placemarks
    </Folder>
  </Document>
</kml>"""

            ZipOutputStream(out.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
                zip.write(waylines.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("wpmz/template.kml"))
                zip.write(waylines.toByteArray()); zip.closeEntry()   // minimal: reuse as template
            }
            FileLogger.write("KMZ exported: ${out.absolutePath} (${path.waypoints.size} wp) [experimental]")
            out.absolutePath
        } catch (e: Exception) {
            Log.e("KmzExporter", "export fail: ${e.message}"); null
        }
    }
}
