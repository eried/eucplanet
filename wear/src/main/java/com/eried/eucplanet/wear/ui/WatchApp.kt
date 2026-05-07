package com.eried.eucplanet.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.bridge.WatchControl
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository

/**
 * Two-page horizontal pager: page 0 is the dashboard the user sees first
 * (speed, battery, horn, light); page 1 is the detail strip (voltage,
 * current, PWM, temperature, trip, torque). Round-display friendly because
 * everything stays in the centre rectangle.
 */
@Composable
fun WatchApp() {
    val state by WatchStateRepository.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Scaffold(timeText = {}) {
            val pagerState = rememberPagerState(pageCount = { 2 })
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> MainScreen(state)
                    1 -> DetailsScreen(state)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(state: WatchState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!state.connected) {
            DisconnectedPlaceholder()
            return@Box
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                text = "%.1f".format(state.speedKmh),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = speedColor(state.speedKmh, state.maxSpeedKmh),
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.watch_speed_unit),
                fontSize = 12.sp,
                color = Color(0xFFB0B0B0)
            )
            Spacer(Modifier.height(6.dp))
            BatteryRow(state.batteryPercent)
            Spacer(Modifier.height(8.dp))
            ActionRow(state)
        }
    }
}

@Composable
private fun DisconnectedPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFB00020))
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.watch_waiting_phone),
            fontSize = 14.sp,
            color = Color(0xFFE0E0E0),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BatteryRow(percent: Int) {
    val tint = when {
        percent < 15 -> Color(0xFFE53935)
        percent < 30 -> Color(0xFFFFB300)
        else -> Color(0xFF66BB6A)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(text = " $percent%", fontSize = 14.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionRow(state: WatchState) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.hasHorn) {
            Button(
                onClick = { WatchStateRepository.sendControl(context, WatchControl.HORN) },
                colors = ButtonDefaults.primaryButtonColors(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Campaign,
                    contentDescription = stringResource(R.string.watch_horn),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (state.hasLight) {
            Button(
                onClick = {
                    val intent = if (state.lightOn) WatchControl.LIGHT_OFF else WatchControl.LIGHT_ON
                    WatchStateRepository.sendControl(context, intent)
                },
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FlashOn,
                    contentDescription = stringResource(R.string.watch_light),
                    tint = if (state.lightOn) Color(0xFFFFC107) else Color(0xFF606060),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailsScreen(state: WatchState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!state.connected) {
            DisconnectedPlaceholder()
            return@Box
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            DetailRow(R.string.watch_voltage_label, "%.1f V".format(state.voltage))
            DetailRow(R.string.watch_current_label, "%.1f A".format(state.current))
            DetailRow(R.string.watch_pwm_label, "%.0f %%".format(state.pwmPercent))
            DetailRow(R.string.watch_temp_label, "%.0f °C".format(state.temperatureC))
            DetailRow(R.string.watch_torque_label, "%.1f".format(state.torque))
            DetailRow(R.string.watch_trip_label, "%.2f km".format(state.tripKm))
        }
    }
}

@Composable
private fun DetailRow(labelRes: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            fontSize = 12.sp,
            color = Color(0xFF9AA0A6),
            modifier = Modifier.width(72.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = value, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

private fun speedColor(speed: Float, max: Float): Color {
    if (max <= 0f) return Color(0xFF66BB6A)
    val ratio = (speed / max).coerceIn(0f, 1f)
    return when {
        ratio < 0.6f -> Color(0xFF66BB6A)
        ratio < 0.85f -> Color(0xFFFFB300)
        else -> Color(0xFFE53935)
    }
}
