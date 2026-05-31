package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
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
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudSessionState
import com.eried.eucplanet.hud.ui.HudUnits

/**
 * Session-level trip stats: four big tiles for DIST / TIME / AVG / MAX.
 *
 * Resets only on HUD reboot (the buffer is held by remember at the
 * activity-scoped composable level). A 5 s WiFi blip won't wipe a
 * 60 km tally.
 */
@Composable
fun TripStatsScreen(hud: HudState, session: HudSessionState) {
    // All accumulators live in HudSessionState so the values survive
    // screen switches and represent the whole session, not "since I
    // last looked at this screen."
    val nowTs = if (hud.timestampMs > 0L) hud.timestampMs else System.currentTimeMillis()
    val elapsedMs = if (session.firstFrameMs > 0L)
        (nowTs - session.firstFrameMs).coerceAtLeast(0L) else 0L
    val avgSpeed = session.avgSpeedKmh.let { if (it.isNaN()) 0f else it }
    val sessionDistKm = if (session.tripStartKm.isFinite())
        (hud.tripKm - session.tripStartKm).coerceAtLeast(0f)
        else 0f
    val maxSpeed = session.maxSpeedKmh

    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = maxHeight.value
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = "DIST",
                        value = "%.1f".format(HudUnits.distance(sessionDistKm, hud.unitDistance)),
                        unit = HudUnits.distanceSuffix(hud.unitDistance),
                        sizeRefDp = h
                    )
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = "TIME",
                        value = formatDuration(elapsedMs),
                        unit = "",
                        sizeRefDp = h
                    )
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = "AVG",
                        value = "%.0f".format(HudUnits.speed(avgSpeed, hud.unitSpeed)),
                        unit = HudUnits.speedSuffix(hud.unitSpeed),
                        sizeRefDp = h
                    )
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = "MAX",
                        value = "%.0f".format(HudUnits.speed(maxSpeed, hud.unitSpeed)),
                        unit = HudUnits.speedSuffix(hud.unitSpeed),
                        sizeRefDp = h
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    sizeRefDp: Float
) {
    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(Color.Black)
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = label,
                color = Color(0xFF8B949E),
                fontSize = (sizeRefDp * 0.05f).sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = (sizeRefDp * 0.22f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = " $unit",
                        color = Color(0xFFB0B0B0),
                        fontSize = (sizeRefDp * 0.06f).sp,
                        modifier = Modifier.padding(bottom = (sizeRefDp * 0.03f).dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
