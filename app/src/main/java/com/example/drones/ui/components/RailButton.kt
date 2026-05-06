package com.example.drones.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * One-tap action button on the left rail.
 *
 * Compact vertical icon-style button that fits in a narrow column on the
 * Cockpit's left edge. Always reachable, never moves, mode-agnostic.
 *
 * Visual: 44dp square, monospace single-glyph or 2-letter label,
 * accent color tints the background, white text. Active state uses solid fill.
 */
@Composable
fun RailButton(
    label: String,
    onClick: () -> Unit,
    accent: Color = Color(0xFF90CAF9),
    active: Boolean = false,
    enabled: Boolean = true,
    sublabel: String? = null,
    modifier: Modifier = Modifier
) {
    val bg = when {
        !enabled -> Color.DarkGray.copy(alpha = 0.4f)
        active   -> accent.copy(alpha = 0.85f)
        else     -> accent.copy(alpha = 0.25f)
    }
    val fg = if (active) Color.Black else accent

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
