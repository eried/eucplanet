package com.eried.eucplanet.ui.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import kotlin.math.abs

/**
 * The plain picture-in-picture face: four readings, as large as they will go.
 *
 * Modelled on the watch's info screen, and for the same reason. A PIP window is
 * glanced at from a bar, a pocket check, the corner of the eye while a map
 * fills the rest of the screen. At that size a gauge and six metrics is a
 * picture of a dashboard rather than something anyone reads, so this drops to
 * the four a rider actually checks mid-ride: how fast, how much battery, how
 * hard the wheel is working, how far.
 *
 * The richer face is [PipDashboard]; the rider picks between them.
 */
@Composable
fun PipSimple(
    data: WheelData,
    connected: Boolean,
    speedUnit: String,
    distanceUnit: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val speed = Units.speed(data.speed, speedUnit)
    val trip = Units.distance(data.tripDistance, distanceUnit)

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigStat(
                value = if (connected) "%.0f".format(speed) else "--",
                label = Units.speedUnit(androidx.compose.ui.platform.LocalContext.current, speedUnit),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            BigStat(
                value = if (connected) "${data.batteryPercent}" else "--",
                label = "%",
                // The one reading that carries a warning: a rider glancing at a
                // small window should see low battery without reading it.
                color = when {
                    !connected -> colors.textSecondary
                    data.batteryPercent <= 15 -> colors.statusDanger
                    data.batteryPercent <= 30 -> colors.statusWarn
                    else -> colors.textPrimary
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pwm = abs(data.pwm)
            BigStat(
                value = if (!connected || data.pwm.isNaN()) "--" else "%.0f".format(pwm),
                label = "pwm %",
                color = when {
                    !connected || data.pwm.isNaN() -> colors.textSecondary
                    pwm >= 85f -> colors.statusDanger
                    pwm >= 65f -> colors.statusWarn
                    else -> colors.textPrimary
                },
                modifier = Modifier.weight(1f),
            )
            BigStat(
                value = if (connected) "%.1f".format(trip) else "--",
                label = Units.distanceUnit(distanceUnit),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigStat(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = color,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            color = MaterialTheme.appColors.textSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
