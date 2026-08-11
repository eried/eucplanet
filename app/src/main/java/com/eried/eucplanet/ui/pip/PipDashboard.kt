package com.eried.eucplanet.ui.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.dashboard.SpeedGauge
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import kotlin.math.abs

/**
 * The dashboard, shrunk into the system's picture-in-picture window.
 *
 * The rider's own gauge next to the metrics they already chose for the
 * dashboard, so this is the same information in the same order rather than a
 * second thing to configure.
 *
 * ## Why the metrics are drawn here rather than reusing the dashboard's chip
 *
 * A dashboard chip can carry a background sparkline and min/max figures. That
 * is right at full size and unreadable at a quarter of the screen: a graph two
 * pixels tall says nothing while stealing the room the number needs. So a chip
 * here is a label and a value, nothing else. Same metric, same value, no
 * decoration that cannot survive the size.
 *
 * Nothing is interactive. PIP hands touches to the system for move, dismiss and
 * expand, so a control drawn here could never be pressed.
 */
@Composable
fun PipDashboard(
    data: WheelData,
    connected: Boolean,
    /** CSV of metric keys, straight from the rider's dashboard order. */
    metricOrder: String,
    maxSpeed: Float,
    speedUnit: String,
    distanceUnit: String,
    tempUnit: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val keys = rememberMetricKeys(metricOrder)

    Row(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedGauge(
            speed = data.speed,
            maxSpeed = maxSpeed,
            speedUnit = speedUnit,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
        )
        // Two columns of three. Three columns at PIP width leaves each value
        // about six characters wide, which "1234 W" already overruns.
        Column(
            Modifier
                .fillMaxHeight()
                .weight(1.15f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            keys.chunked(2).forEach { pair ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pair.forEach { key ->
                        PipMetric(
                            key = key,
                            data = data,
                            connected = connected,
                            distanceUnit = distanceUnit,
                            tempUnit = tempUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last row aligned with the ones above when the
                    // rider picked an odd number of metrics.
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun rememberMetricKeys(order: String): List<String> =
    androidx.compose.runtime.remember(order) {
        order.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
    }

@Composable
private fun PipMetric(
    key: String,
    data: WheelData,
    connected: Boolean,
    distanceUnit: String,
    tempUnit: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val (value, unit) = pipMetricValue(key, data, distanceUnit, tempUnit)
    Column(
        modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (connected) value else "--",
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = if (unit.isBlank()) key.lowercase() else "${key.lowercase()} $unit",
            color = colors.textSecondary,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Value and unit for one dashboard metric key.
 *
 * Deliberately a small local mapping rather than a call into the dashboard's
 * renderer: that one returns decorated chips, and everything past the number is
 * dropped here anyway.
 */
private fun pipMetricValue(
    key: String,
    d: WheelData,
    distanceUnit: String,
    tempUnit: String,
): Pair<String, String> = when (key.uppercase()) {
    "BATTERY" -> "${d.batteryPercent}" to "%"
    "TEMPERATURE" -> "%.0f".format(Units.temperature(d.maxTemperature, tempUnit)) to
        Units.tempUnit(tempUnit)
    "VOLTAGE" -> "%.0f".format(d.voltage) to "V"
    "CURRENT" -> "%.0f".format(abs(d.current)) to "A"
    "LOAD", "PWM" -> (if (d.pwm.isNaN()) "--" else "%.0f".format(abs(d.pwm))) to "%"
    "TRIP" -> "%.1f".format(Units.distance(d.tripDistance, distanceUnit)) to
        Units.distanceUnit(distanceUnit)
    "ODO", "TOTAL" -> "%.0f".format(Units.distance(d.totalDistance, distanceUnit)) to
        Units.distanceUnit(distanceUnit)
    "POWER" -> "%.0f".format(abs(d.voltage * d.current)) to "W"
    else -> "--" to ""
}
