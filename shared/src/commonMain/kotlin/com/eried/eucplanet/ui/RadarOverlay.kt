package com.eried.eucplanet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.data.RadarThreat
import com.eried.eucplanet.data.ThreatLevel
import com.eried.eucplanet.ui.theme.AppThemeColors

/**
 * Rear-view radar lane overlay (Garmin Varia) — a narrow vertical strip showing
 * tracked vehicles by distance (rider at the bottom, far cars near the top),
 * dot-colored by threat level. Mirrors Android's DashboardRadarMini.
 */
@Composable
internal fun RadarOverlay(threats: List<RadarThreat>, c: AppThemeColors, modifier: Modifier) {
    Box(modifier.width(30.dp).clip(RoundedCornerShape(15.dp)).background(c.surface)) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val maxM = 140f
            val cx = size.width / 2f
            // Rider marker at the bottom.
            drawCircle(c.primary, radius = 4f, center = Offset(cx, size.height - 4f))
            threats.forEach { t ->
                val frac = (t.distanceM.coerceIn(0, maxM.toInt()) / maxM)
                val y = (size.height - 8f) * (1f - frac) + 4f
                val color = when (t.threatLevel) {
                    ThreatLevel.FAST_APPROACH -> c.statusDanger
                    ThreatLevel.APPROACHING -> c.gaugeWarn
                    ThreatLevel.NONE -> c.textDisabled
                }
                drawCircle(color, radius = 7f, center = Offset(cx, y))
            }
        }
    }
}
