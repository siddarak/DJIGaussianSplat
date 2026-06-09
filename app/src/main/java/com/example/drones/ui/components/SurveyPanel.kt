package com.example.drones.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.localization.MarkerObservation
import com.example.drones.localization.TopScanResult
import com.example.drones.orbit.CapturePhase
import com.example.drones.orbit.EditableRing

/**
 * Phase-aware capture panel:
 *   SCANNING/CENTER_READY → marker count + AUTO-CENTER
 *   CENTERING             → status + ABORT
 *   EDITING               → ring tabs + axis/height sliders + fine +/- + lock + START ORBIT
 *   ORBITING              → progress + ABORT
 */
@Composable
fun SurveyPanel(
    phase: CapturePhase,
    liveObservations: List<MarkerObservation>,
    scan: TopScanResult?,
    rings: List<EditableRing>,
    selectedRing: Int,
    orbitStatus: String,
    onAutoCenter: () -> Unit,
    onAbortCenter: () -> Unit,
    onConfirmCenter: () -> Unit,
    onSelectRing: (Int) -> Unit,
    onNudge: (major: Double, minor: Double) -> Unit,
    onHeight: (Double) -> Unit,
    onLockRing: () -> Unit,
    onLockAll: () -> Unit,
    onStartOrbit: () -> Unit,
    onAbortOrbit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(210.dp)
            .heightIn(max = 360.dp)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val markers = scan?.markers?.size ?: 0
        Text(
            text = "PHASE: ${phase.name}",
            color = Color(0xFFFFD600), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
        )

        when (phase) {
            CapturePhase.SCANNING, CapturePhase.CENTER_READY -> {
                Text("$markers markers in view", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    if (markers >= 4) "Center ready." else "Need ≥4 markers (you have $markers).",
                    color = if (markers >= 4) Color(0xFF76FF03) else Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                Chip("AUTO-CENTER", if (markers >= 4) Color(0xFF00E676) else Color.DarkGray, onAutoCenter, Modifier.fillMaxWidth())
                // Skip auto-fly: accept the current position as center and edit rings.
                Chip("USE THIS CENTER", if (markers >= 1) Color(0xFFFFD600) else Color.DarkGray, onConfirmCenter, Modifier.fillMaxWidth())
            }

            CapturePhase.CENTERING -> {
                Text("Flying to center…", color = Color(0xFF80DEEA), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("If it keeps toggling, tap USE CURRENT.", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Chip("✓ USE CURRENT CENTER", Color(0xFFFFD600), onConfirmCenter, Modifier.fillMaxWidth())
                Chip("ABORT", Color(0xFFFF5252), onAbortCenter, Modifier.fillMaxWidth())
            }

            CapturePhase.EDITING -> {
                RingEditor(rings, selectedRing, onSelectRing, onNudge, onHeight, onLockRing)
                val allLocked = rings.isNotEmpty() && rings.all { it.locked }
                if (!allLocked) {
                    Chip("LOCK ALL RINGS", Color(0xFF76FF03), onLockAll, Modifier.fillMaxWidth())
                }
                Chip(
                    if (allLocked) "▶ START ORBIT" else "…lock rings to fly",
                    if (allLocked) Color(0xFF00E676) else Color.DarkGray,
                    onStartOrbit, Modifier.fillMaxWidth()
                )
            }

            CapturePhase.ORBITING -> {
                Text(orbitStatus, color = Color(0xFFFFD600), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Chip("ABORT", Color(0xFFFF5252), onAbortOrbit, Modifier.fillMaxWidth())
            }

            else -> {}
        }
    }
}

// Same palette + order as PathProjector.RING_COLORS so panel ↔ overlay match.
private val RING_COLORS = listOf(
    Color(0xFF00E5FF), Color(0xFF76FF03), Color(0xFFFFD600), Color(0xFFE040FB), Color(0xFFFF6D00)
)
private fun ringColor(i: Int) = RING_COLORS[i % RING_COLORS.size]

@Composable
private fun RingEditor(
    rings: List<EditableRing>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onNudge: (Double, Double) -> Unit,
    onHeight: (Double) -> Unit,
    onLock: () -> Unit
) {
    // ring tabs — each tinted its own colour; ✓ when locked
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rings.forEachIndexed { i, r ->
            val sel = i == selected
            val c = ringColor(i)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel) c else c.copy(alpha = 0.22f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text("R${i + 1}${if (r.locked) " ✓" else ""}",
                    color = if (sel) Color.Black else c,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }

    val r = rings.getOrNull(selected) ?: return
    val c = ringColor(selected)
    // selected-ring header strip in its colour
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text("Ring ${selected + 1}${if (r.locked) "  LOCKED" else ""}",
            color = c, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
    Text("major %.2f · minor %.2f m".format(r.semiMajorM, r.semiMinorM),
        color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    Text("height %.2f m · gimbal %.0f°".format(r.heightAboveFloorM, r.gimbalPitchDeg),
        color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

    StepRow("major", { onNudge(-0.25, 0.0) }, { onNudge(0.25, 0.0) })
    StepRow("minor", { onNudge(0.0, -0.25) }, { onNudge(0.0, 0.25) })
    StepRow("size",  { onNudge(-0.25, -0.25) }, { onNudge(0.25, 0.25) })
    StepRow("height",{ onHeight(r.heightAboveFloorM - 0.2) }, { onHeight(r.heightAboveFloorM + 0.2) })

    Chip(if (r.locked) "UNLOCK RING ${selected + 1}" else "LOCK RING ${selected + 1}",
        if (r.locked) Color(0xFF90CAF9) else c, onLock, Modifier.fillMaxWidth())
}

@Composable
private fun StepRow(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
        StepBtn("−", onMinus)
        StepBtn("+", onPlus)
    }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.9f))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.Black, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
