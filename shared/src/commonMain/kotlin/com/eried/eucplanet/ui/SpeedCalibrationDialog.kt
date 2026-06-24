package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.UnitFormat
import kotlin.math.roundToInt

/**
 * Speed calibration — port of Android's idea: show the wheel speed next to the GPS
 * speed (external box preferred, else phone GPS), plus live G-force, and let the rider
 * apply the difference as the speed-calibration offset so the displayed speed matches GPS.
 */
@Composable
fun SpeedCalibrationDialog(
    wheelKmh: Float,
    phoneGpsKmh: Float,
    externalGpsKmh: Float,
    gForce: Float,
    unitSpeed: String,
    currentPct: Float,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val gps = if (externalGpsKmh > 0.5f) externalGpsKmh else phoneGpsKmh
    val gpsLabel = if (externalGpsKmh > 0.5f) "External GPS" else "Phone GPS"
    // The wheel here is the RAW wheel reading (before the current offset), so a fresh
    // offset can be computed from scratch: rawWheel = displayed / (1 + pct/100).
    val rawWheel = wheelKmh / (1f + currentPct / 100f)
    val canApply = rawWheel > 2f && gps > 2f
    val suggestedPct = if (canApply) ((gps / rawWheel) - 1f) * 100f else currentPct
    val unit = UnitFormat.speedLabel(unitSpeed)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(20.dp),
        ) {
            Text("Calibrate speed with GPS", color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Ride at a steady speed, then apply.", color = c.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            CalRow(c, "Wheel speed", "${UnitFormat.speed(wheelKmh, unitSpeed).roundToInt()} $unit")
            CalRow(c, gpsLabel, if (gps > 0.5f) "${UnitFormat.speed(gps, unitSpeed).roundToInt()} $unit" else "--")
            CalRow(c, "G-force", "${(gForce * 100).roundToInt() / 100f} g")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Current offset", color = c.textSecondary, fontSize = 13.sp)
                Text("${if (currentPct >= 0f) "+" else ""}${(currentPct * 10).roundToInt() / 10f}%", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (canApply) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Suggested offset", color = c.textSecondary, fontSize = 13.sp)
                    Text("${if (suggestedPct >= 0f) "+" else ""}${(suggestedPct * 10).roundToInt() / 10f}%", color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("Move above 2 ${unit} with a GPS fix to calibrate.", color = c.textDisabled, fontSize = 11.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CalButton(c, "Cancel", filled = false, modifier = Modifier.weight(1f)) { onDismiss() }
                CalButton(c, "Apply", filled = canApply, enabled = canApply, modifier = Modifier.weight(1f)) {
                    onApply(suggestedPct.coerceIn(-15f, 15f)); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun CalRow(c: com.eried.eucplanet.ui.theme.AppThemeColors, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textSecondary, fontSize = 14.sp)
        Text(value, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CalButton(c: com.eried.eucplanet.ui.theme.AppThemeColors, label: String, filled: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = if (filled) c.onPrimary else c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(if (filled) c.primary else c.surfaceVariant)
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 11.dp),
    )
}
