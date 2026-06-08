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
    onSelectRing: (Int) -> Unit,
    onNudge: (major: Double, minor: Double) -> Unit,
    onHeight: (Double) -> Unit,
    onLockRing: () -> Unit,
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
            }

            CapturePhase.CENTERING -> {
                Text("Flying to center…", color = Color(0xFF80DEEA), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("Hand on controller.", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Chip("ABORT", Color(0xFFFF5252), onAbortCenter, Modifier.fillMaxWidth())
            }

            CapturePhase.EDITING -> {
                RingEditor(rings, selectedRing, onSelectRing, onNudge, onHeight, onLockRing)
                val allLocked = rings.isNotEmpty() && rings.all { it.locked }
                Chip(
                    if (allLocked) "▶ START ORBIT" else "Lock all rings",
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

@Composable
private fun RingEditor(
    rings: List<EditableRing>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onNudge: (Double, Double) -> Unit,
    onHeight: (Double) -> Unit,
    onLock: () -> Unit
) {
    // ring tabs
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rings.forEachIndexed { i, r ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.15f))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("R${i + 1}${if (r.locked) "🔒" else ""}",
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }

    val r = rings.getOrNull(selected) ?: return
    Text("major %.2fm  minor %.2fm".format(r.semiMajorM, r.semiMinorM),
        color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    Text("height %.2fm  gimbal %.0f°".format(r.heightAboveFloorM, r.gimbalPitchDeg),
        color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

    // major axis +/- (also scales minor when circle)
    StepRow("major", { onNudge(-0.25, 0.0) }, { onNudge(0.25, 0.0) })
    StepRow("minor", { onNudge(0.0, -0.25) }, { onNudge(0.0, 0.25) })
    StepRow("both ", { onNudge(-0.25, -0.25) }, { onNudge(0.25, 0.25) })
    StepRow("height", { onHeight(r.heightAboveFloorM - 0.2) }, { onHeight(r.heightAboveFloorM + 0.2) })

    Chip(if (r.locked) "UNLOCK R${selected + 1}" else "LOCK R${selected + 1}",
        if (r.locked) Color(0xFF90CAF9) else Color(0xFF76FF03), onLock, Modifier.fillMaxWidth())
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
