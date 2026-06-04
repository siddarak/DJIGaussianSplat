package com.example.drones.localization

import com.example.drones.util.FileLogger
import kotlin.math.sqrt

/** One marker resolved to a ground position (meters) in the scan frame. */
data class ScannedMarker(
    val id: Int,
    val role: MarkerRole,
    val xM: Double,
    val yM: Double
)

/**
 * Result of one top-down scan: all visible markers placed on the ground plane,
 * plus the geometric center and the keep-out / outer radii used for planning.
 */
data class TopScanResult(
    val markers: List<ScannedMarker>,
    val centerXM: Double,
    val centerYM: Double,
    val cameraHeightM: Double,     // camera height above the marker/floor plane at scan time
    val rKeepoutM: Double,         // must orbit OUTSIDE this (inner perimeter + margin)
    val rOuterM: Double,           // soft outer perimeter (may overfly)
    val seenIds: List<Int>,
    val source: Source             // where positions came from
) {
    enum class Source { MEASURED, SCAN_DERIVED }
}

/**
 * Builds a ground-plane marker map from a single top-down frame in which all
 * markers are visible. Replaces the old chained co-observation survey.
 *
 * Two position sources:
 *   - MEASURED: if the MarkerLayout has measured (x,y) for every seen marker,
 *     use those (your ground truth, consistent frame).
 *   - SCAN_DERIVED: otherwise place each marker from its solvePnP translation.
 *     With the gimbal pointing straight down, a marker's camera-frame (x, y)
 *     are its horizontal ground offsets and z is the height above the floor.
 *
 * Center = centroid of INNER markers (object lives inside the inner perimeter).
 */
object TopScanLocalizer {

    private const val KEEPOUT_MARGIN_M = 0.30

    fun localize(observations: List<MarkerObservation>, layout: MarkerLayout): TopScanResult? {
        if (observations.isEmpty()) {
            FileLogger.write("TopScan: no markers in frame")
            return null
        }

        val useMeasured = observations.all { layout.spec(it.id)?.hasMeasuredPos == true }
        val source = if (useMeasured) TopScanResult.Source.MEASURED else TopScanResult.Source.SCAN_DERIVED

        val markers = observations.map { obs ->
            val role = layout.role(obs.id)
            val (x, y) = if (useMeasured) {
                val s = layout.spec(obs.id)!!
                s.xM!! to s.yM!!
            } else {
                // camera looking straight down: tvec.x / tvec.y are ground offsets
                obs.tvec[0] to obs.tvec[1]
            }
            ScannedMarker(obs.id, role, x, y)
        }

        val cameraHeight = observations.map { it.tvec[2] }.average()

        // Center = centroid of inner markers; fall back to all if no inner seen.
        val inner = markers.filter { it.role == MarkerRole.INNER }
        val basis = if (inner.isNotEmpty()) inner else markers
        val cx = basis.map { it.xM }.average()
        val cy = basis.map { it.yM }.average()

        fun dist(m: ScannedMarker) = sqrt((m.xM - cx) * (m.xM - cx) + (m.yM - cy) * (m.yM - cy))

        val rKeepout = (inner.maxOfOrNull { dist(it) } ?: 0.0) + KEEPOUT_MARGIN_M
        val outer = markers.filter { it.role == MarkerRole.OUTER }
        val rOuter = outer.maxOfOrNull { dist(it) } ?: rKeepout

        val result = TopScanResult(
            markers = markers,
            centerXM = cx, centerYM = cy,
            cameraHeightM = cameraHeight,
            rKeepoutM = rKeepout,
            rOuterM = rOuter,
            seenIds = markers.map { it.id }.sorted(),
            source = source
        )
        FileLogger.write(
            "TopScan: ${markers.size} markers (${source}) center=(%.2f,%.2f) camH=%.2fm keepout=%.2fm outer=%.2fm ids=%s"
                .format(cx, cy, cameraHeight, rKeepout, rOuter, result.seenIds.toString())
        )
        return result
    }
}
