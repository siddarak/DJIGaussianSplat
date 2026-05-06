package com.example.drones.localization

import com.example.drones.util.FileLogger
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import kotlin.math.sqrt

/**
 * Manages the marker-survey workflow.
 *
 * State machine:
 *   IDLE → user taps SURV → DETECTING
 *   DETECTING + no captures: any visible marker is a candidate (becomes origin if added)
 *   DETECTING + ≥1 capture: candidate must be co-visible with a locked marker
 *   User taps "ADD WP N" → marker added to MarkerMap, world position computed
 *   User taps "DONE" → survey complete, geofence built
 *
 * Co-observation math:
 *   Given marker A locked at world position P_A, and same frame contains both
 *   A and a candidate B, compute:
 *
 *     T_camFromA = (R_A | t_A)        (from solvePnP for A)
 *     T_camFromB = (R_B | t_B)
 *     T_AfromB   = T_camFromA^-1 · T_camFromB    (A-frame coords of B's origin)
 *
 *   World position of B = world position of A + (rotation translating A-frame to world) · t_AfromB
 *   Approximation here: we treat A's frame as already aligned with world axes
 *   (markers placed flat on ground, all facing up). So P_B = P_A + t_AfromB.
 */
class SurveyController(
    private val map: MarkerMap = MarkerMap()
) {
    val markerMap: MarkerMap get() = map

    /** Compute candidate marker info given current frame's observations. */
    fun resolveCandidate(observations: List<MarkerObservation>): Candidate? {
        // Pick markers not yet locked
        val unlocked = observations.filter { !map.isLocked(it.id) }
        if (unlocked.isEmpty()) return null

        // No captures yet → any unlocked marker can be origin
        if (map.size == 0) {
            val first = unlocked.first()
            return Candidate(
                markerId = first.id,
                proposedWorld = Triple(0.0, 0.0, 0.0),
                referenceLockedId = null
            )
        }

        // Need co-observation: pick best pair (locked + unlocked)
        val locked = observations.filter { map.isLocked(it.id) }
        if (locked.isEmpty()) return null

        // Pick locked marker with closest distance for stability, paired with first unlocked
        val refLocked = locked.minByOrNull { it.distanceM } ?: return null
        val candidate = unlocked.first()
        val refMapEntry = map.get(refLocked.id) ?: return null

        val world = computeWorldPosition(refLocked, candidate, refMapEntry)
        return Candidate(
            markerId = candidate.id,
            proposedWorld = world,
            referenceLockedId = refLocked.id
        )
    }

    fun commit(candidate: Candidate): Boolean {
        val ok = map.add(
            candidate.markerId,
            candidate.proposedWorld.first,
            candidate.proposedWorld.second,
            candidate.proposedWorld.third
        )
        if (ok) {
            FileLogger.write(
                "Waypoint added: id=${candidate.markerId} seq=${map.size - 1} " +
                "pos=(%.2f, %.2f, %.2f)".format(candidate.proposedWorld.first, candidate.proposedWorld.second, candidate.proposedWorld.third) +
                " refId=${candidate.referenceLockedId}"
            )
        }
        return ok
    }

    fun reset() {
        map.reset()
        FileLogger.write("Survey reset")
    }

    /**
     * Compute candidate's world position from co-observation with reference marker.
     *
     * @param ref      observation of already-locked reference marker in current frame
     * @param cand     observation of unlocked candidate marker in same frame
     * @param refWorld world position of reference marker (from MarkerMap)
     * @return         candidate's estimated world position
     */
    private fun computeWorldPosition(
        ref: MarkerObservation,
        cand: MarkerObservation,
        refWorld: WaypointEntry
    ): Triple<Double, Double, Double> {
        // T_camFromRef and T_camFromCand are the camera-to-marker transforms from solvePnP.
        // We need t_RefFromCand = position of cand origin expressed in ref's frame.
        //   T_RefFromCand = T_camFromRef^-1 · T_camFromCand
        // Then world position of cand ≈ refWorld + R_world_from_ref · t_RefFromCand.
        // Assumption: ref marker's frame axes are aligned with world axes (markers flat on ground).

        val rRef = rodriguesToMat3(ref.rvec)
        val rCand = rodriguesToMat3(cand.rvec)
        val tRef = ref.tvec   // double[3]
        val tCand = cand.tvec

        // t_RefFromCand = R_Ref^T · (t_Cand - t_Ref)
        val diff = doubleArrayOf(
            tCand[0] - tRef[0],
            tCand[1] - tRef[1],
            tCand[2] - tRef[2]
        )
        val tRefFromCand = mulMatT(rRef, diff)   // R_Ref^T · diff

        // Map from marker frame (X right, Y down, Z forward — OpenCV camera convention transformed)
        // to ground world frame (X east, Y north, Z up).
        // Because markers lie flat with normal up, marker-frame's local X-Y plane ≈ ground plane.
        // We adopt: world X = marker-frame X, world Y = -marker-frame Z (because marker Z faces camera which is above).
        // This is a simplification; for high-accuracy mapping we'd track each marker's heading too.
        val dxWorld = tRefFromCand[0]
        val dyWorld = -tRefFromCand[2]
        val dzWorld = -tRefFromCand[1]

        return Triple(
            refWorld.x + dxWorld,
            refWorld.y + dyWorld,
            refWorld.z + dzWorld
        )
    }

    private fun rodriguesToMat3(rvec: DoubleArray): Mat {
        val src = Mat(3, 1, org.opencv.core.CvType.CV_64F).apply {
            put(0, 0, rvec[0]); put(1, 0, rvec[1]); put(2, 0, rvec[2])
        }
        val dst = Mat(3, 3, org.opencv.core.CvType.CV_64F)
        Calib3d.Rodrigues(src, dst)
        src.release()
        return dst
    }

    /** R^T · v  (3x3 transpose times length-3 vector). */
    private fun mulMatT(r: Mat, v: DoubleArray): DoubleArray {
        val out = DoubleArray(3)
        for (col in 0 until 3) {
            // (R^T)[col, *] · v   =  R[*, col] · v
            out[col] = r[0, col][0] * v[0] + r[1, col][0] * v[1] + r[2, col][0] * v[2]
        }
        return out
    }

    data class Candidate(
        val markerId: Int,
        val proposedWorld: Triple<Double, Double, Double>,
        val referenceLockedId: Int?
    )
}
