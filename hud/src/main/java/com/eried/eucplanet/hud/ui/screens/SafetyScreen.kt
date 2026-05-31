package com.eried.eucplanet.hud.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
 * Three big tiles, colour-graded by their own thresholds:
 *   PWM           green/orange/red against the rider's gauge thresholds
 *   TEMP          green up to ~60 deg, orange to ~70, red 80+
 *   VOLTAGE SAG   current vs highest seen this session, expressed as
 *                 ΔV, red if > SAG_RED_V (8 V default, reasonable for
 *                 ~84 V packs).
 */
@Composable
fun SafetyScreen(hud: HudState, session: HudSessionState) {
    // Session-high voltage lives in HudSessionState so it survives
    // screen switches; a HUD reboot is the only thing that resets it.
    val maxVoltage = session.maxVoltage
    val sagV = if (maxVoltage > 1f) (maxVoltage - hud.voltage).coerceAtLeast(0f) else 0f

    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SafetyTile(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "PWM",
                value = "${hud.pwm.toInt()}%",
                color = pwmColor(hud.pwm, hud.gaugeOrangeThresholdPct, hud.gaugeRedThresholdPct)
            )
            SafetyTile(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "TEMP",
                value = "${HudUnits.temperature(hud.temperatureC, hud.unitTemp).toInt()}°${hud.unitTemp}",
                color = tempColor(hud.temperatureC)
            )
            SafetyTile(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "SAG  (${hud.voltage.toInt()} V of ${maxVoltage.toInt()} V)",
                value = "-%.1f V".format(sagV),
                color = sagColor(sagV)
            )
        }
    }
}

@Composable
private fun SafetyTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RectangleShape)
            .background(Color.Black)
            .border(2.dp, color, RectangleShape)
            .padding(14.dp)
    ) {
        // Per-tile font sizing: scale by the tile's own height (not the
        // whole screen), so three stacked tiles each get a value that
        // fills the tile without overflowing. Label takes ~14% of tile
        // height; value takes ~52% leaving slack for the descender on
        // characters like 'V' and the round caps on '0'.
        val tileH = maxHeight.value
        Column {
            Text(
                text = label,
                color = color,
                fontSize = (tileH * 0.14f).coerceIn(10f, 22f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = value,
                color = Color.White,
                fontSize = (tileH * 0.52f).coerceIn(20f, 96f).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

private fun pwmColor(pwm: Float, orange: Int, red: Int): Color = when {
    pwm >= red -> Color(0xFFE53935)
    pwm >= orange -> Color(0xFFFF6F00)
    else -> Color(0xFF4CAF50)
}

private fun tempColor(tempC: Float): Color = when {
    tempC >= 80f -> Color(0xFFE53935)
    tempC >= 65f -> Color(0xFFFF6F00)
    else -> Color(0xFF4CAF50)
}

private fun sagColor(sagV: Float): Color = when {
    sagV >= SAG_RED_V -> Color(0xFFE53935)
    sagV >= SAG_ORANGE_V -> Color(0xFFFF6F00)
    else -> Color(0xFF4CAF50)
}

private const val SAG_ORANGE_V: Float = 5f
private const val SAG_RED_V: Float = 8f
