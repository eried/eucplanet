package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits

/**
 * Watch-face minimal: huge clock at the top, speed digit below, two
 * tiny corner badges with battery and PWM. Designed for low-info
 * cruise / commute riding where the rider just wants "what time is
 * it" and "how fast am I going."
 *
 * Clock follows the device 12/24 preference (this is the HUD's own
 * wall clock, not a preset-element clock -- it picks up the helmet
 * rider's preference rather than the preset author's choice).
 */
@Composable
fun BigClockScreen(hud: HudState) {
    val ctx = LocalContext.current
    val use24h = android.text.format.DateFormat.is24HourFormat(ctx)
    val pattern = if (use24h) "HH:mm" else "h:mm"
    val ampmPattern = if (use24h) "" else "a"

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // 15s tick is plenty for a "what time is it now" face --
        // we don't show seconds.
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000L)
        }
    }

    val fmtTime = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(nowMs))
    val fmtAmpm = if (ampmPattern.isEmpty()) ""
        else java.text.SimpleDateFormat(ampmPattern, java.util.Locale.getDefault())
            .format(java.util.Date(nowMs))

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val h = maxHeight.value
            Column(
                modifier = Modifier.fillMaxSize().padding(top = (h * 0.06f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                // Time + AM/PM share a Row, AM/PM baseline-aligned to the
                // BOTTOM of the big time digits so it sits beside the time
                // instead of stacked below. On 24h locales the AM/PM block
                // collapses to nothing and the row degrades cleanly.
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Text(
                        text = fmtTime,
                        color = Color.White,
                        fontSize = (h * 0.42f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    if (fmtAmpm.isNotEmpty()) {
                        androidx.compose.foundation.layout.Spacer(
                            modifier = Modifier.width((h * 0.02f).dp)
                        )
                        // bottom padding lifts the small AM/PM off the very
                        // baseline so it visually sits at ~25 % from the
                        // bottom of the big digits — reads as a subtitle
                        // attached to the time rather than a floating
                        // descender.
                        Text(
                            text = fmtAmpm,
                            color = Color(0xFFB0B0B0),
                            fontSize = (h * 0.07f).sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = (h * 0.06f).dp)
                        )
                    }
                }
                Text(
                    text = "%.0f %s".format(
                        HudUnits.speed(hud.speedKmh, hud.unitSpeed),
                        HudUnits.speedSuffix(hud.unitSpeed)
                    ),
                    color = Color.White,
                    fontSize = (h * 0.16f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        // Corner badges -- same chrome family as the disconnect /
        // wall-clock / version badges so they read as part of the
        // existing overlay vocabulary.
        CornerBadge(
            text = "${hud.batteryPercent}%",
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        )
        CornerBadge(
            text = "PWM ${hud.pwm.toInt()}%",
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
        )
    }
}

@Composable
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6111111))
            .border(1.dp, Color(0xFF6B6B6B), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
