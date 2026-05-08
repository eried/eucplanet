package com.eried.eucplanet.wear.ui

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
 * Two-page horizontal pager: page 0 is the dashboard (gauge + horn/light);
 * page 1 is the detail strip (voltage / current / PWM / temp / trip / torque).
 */
@Composable
fun WatchApp() {
    val state by WatchStateRepository.state.collectAsStateWithLifecycle()
    val accent = accentColorFor(state.accentKey)
    MaterialTheme {
        Scaffold(timeText = {}) {
            // Horizontal pager: dashboard <-> details. Each page is itself a
            // vertical pager so the dashboard can swipe up to the GPS-speed
            // page without the details screen having a sibling-vertical-pager
            // it doesn't need.
            val hPager = rememberPagerState(pageCount = { 2 })
            HorizontalPager(state = hPager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> MainPaged(state, accent)
                    1 -> DetailsScreen(state, accent)
                }
            }
        }
    }
}

/**
 * Vertical pager wrapping the dashboard so the user can swipe up for the GPS
 * speed page when the option is enabled. When disabled it collapses to just
 * the dashboard so accidental vertical drags don't reveal an empty screen.
 */
@Composable
private fun MainPaged(state: WatchState, accent: Color) {
    if (!state.gpsSpeedEnabled) {
        MainScreen(state, accent)
        return
    }
    val vPager = rememberPagerState(pageCount = { 2 })
    VerticalPager(state = vPager, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            0 -> MainScreen(state, accent)
            1 -> GpsSpeedScreen(state, accent)
        }
    }
}

@Composable
private fun MainScreen(state: WatchState, accent: Color) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!state.connected) {
            DisconnectedPlaceholder()
            return@BoxWithConstraints
        }
        val sw = maxWidth.value
        val batteryFontSp = (sw * 0.034f).coerceIn(9f, 12f).sp
        val batteryIconDp = (sw * 0.038f).coerceIn(10f, 14f).dp
        val batterySpacingDp = (sw * 0.018f).coerceIn(5f, 9f).dp
        // ~10% bigger than the previous 0.115f / 0.055f values.
        val buttonDp = (sw * 0.127f).coerceIn(37f, 55f).dp
        val buttonIconDp = (sw * 0.060f).coerceIn(18f, 26f).dp
        // Speed gets all the visual weight in the centre now that the unit
        // label is gone (swipe to page 2 to see km/h vs mph).
        val speedFontSp = (sw * 0.245f).coerceIn(60f, 92f).sp
        val maxSpeed = if (state.maxSpeedKmh > 0f) state.maxSpeedKmh else 70f
        val loadBarWidth = (sw * 0.30f).coerceIn(82f, 130f).dp
        val loadBarHeight = (sw * 0.018f).coerceIn(5f, 8f).dp
        val centerOffsetY = -(sw * 0.06f).coerceIn(18f, 28f).dp
        val watchPercent = rememberWatchBatteryPercent()

        // Full-bleed dial as the background frame.
        SpeedGauge(
            speed = state.speedKmh,
            maxSpeed = maxSpeed,
            imperial = state.imperialUnits,
            accent = accent,
            showColorBand = true,
            fullBleed = true,
            drawSpeedText = false,
            modifier = Modifier.fillMaxSize()
        )

        // Speed number + optional unit + PWM cluster, slightly above center so
        // the bottom cluster (batteries + buttons) has more room.
        val unitSp = (sw * 0.045f).coerceIn(13f, 18f).sp
        val pwmNumberSp = (sw * 0.040f).coerceIn(11f, 15f).sp
        val showBar = state.pwmDisplay == "BAR" || state.pwmDisplay == "BOTH"
        val showPwmNumber = state.pwmDisplay == "NUMBERS" || state.pwmDisplay == "BOTH"
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = centerOffsetY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%.0f".format(WatchUnits.speed(state.speedKmh, state.imperialUnits)),
                    fontSize = speedFontSp,
                    fontWeight = FontWeight.Bold,
                    color = speedTierColor(state.speedKmh, maxSpeed)
                )
                if (state.showSpeedUnit) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = WatchUnits.speedUnit(state.imperialUnits),
                        fontSize = unitSp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFB0B0B0),
                        modifier = Modifier.padding(bottom = (sw * 0.04f).coerceIn(10f, 16f).dp)
                    )
                }
            }
            Spacer(Modifier.height(1.dp))
            if (showBar) {
                LoadBar(
                    percent = state.pwmPercent,
                    modifier = Modifier
                        .offset(y = -(sw * 0.025f).coerceIn(8f, 14f).dp)
                        .width(loadBarWidth)
                        .height(loadBarHeight)
                )
            }
            if (showPwmNumber) {
                Text(
                    text = "%.0f%%".format(state.pwmPercent),
                    fontSize = pwmNumberSp,
                    fontWeight = FontWeight.Medium,
                    color = pwmTierColor(state.pwmPercent),
                    modifier = Modifier.offset(y = if (showBar) -(sw * 0.020f).coerceIn(6f, 10f).dp else -(sw * 0.025f).coerceIn(8f, 14f).dp)
                )
            }
        }

        // Bottom cluster: batteries directly above the action buttons, both
        // pushed near the bottom of the dial.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (sw * 0.07f).coerceIn(20f, 34f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((sw * 0.018f).coerceIn(5f, 9f).dp)
        ) {
            BatteryRow(
                wheelPercent = state.batteryPercent.takeIf { state.showWheelBattery },
                phonePercent = state.phoneBatteryPercent.takeIf { state.showPhoneBattery },
                watchPercent = watchPercent.takeIf { state.showWatchBattery },
                fontSize = batteryFontSp,
                iconSize = batteryIconDp,
                spacing = batterySpacingDp
            )
            ActionRow(state, accent, buttonDp, buttonIconDp)
        }
    }
}

private fun speedTierColor(speedKmh: Float, maxSpeedKmh: Float): Color = when {
    speedKmh > maxSpeedKmh * 0.85f -> Color(0xFFEF5350)
    speedKmh > maxSpeedKmh * 0.65f -> Color(0xFFFFA726)
    speedKmh > maxSpeedKmh * 0.4f -> Color(0xFFFFCA28)
    else -> Color(0xFF66BB6A)
}

/**
 * Compact PWM-as-load progress bar. Track is dim grey; fill colour shifts
 * from green to red as the wheel approaches its torque limit, so a glance
 * tells you how hard the motor is working.
 */
@Composable
private fun LoadBar(percent: Float, modifier: Modifier = Modifier) {
    val pct = percent.coerceIn(0f, 100f)
    val fillColor = when {
        pct > 80f -> Color(0xFFEF5350)
        pct > 60f -> Color(0xFFFFA726)
        pct > 40f -> Color(0xFFFFCA28)
        else -> Color(0xFF66BB6A)
    }
    val trackColor = Color(0xFF333333)
    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius)
        )
        if (pct > 0f) {
            drawRoundRect(
                color = fillColor,
                size = Size(size.width * pct / 100f, size.height),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}

@Composable
private fun DisconnectedPlaceholder() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val sw = maxWidth.value
        val iconDp = (sw * 0.18f).coerceIn(36f, 56f).dp
        val textSp = (sw * 0.038f).coerceIn(12f, 15f).sp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = Color(0xFF9AA0A6),
                modifier = Modifier.size(iconDp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.watch_waiting_phone),
                fontSize = textSp,
                color = Color(0xFFB0B0B0),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun BatteryRow(
    wheelPercent: Int?,
    phonePercent: Int?,
    watchPercent: Int?,
    fontSize: TextUnit,
    iconSize: Dp,
    spacing: Dp
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (wheelPercent != null) BatteryChip(Icons.Filled.ElectricScooter, wheelPercent, fontSize, iconSize)
        if (phonePercent != null) BatteryChip(Icons.Filled.PhoneAndroid, phonePercent, fontSize, iconSize)
        if (watchPercent != null) BatteryChip(Icons.Filled.Watch, watchPercent, fontSize, iconSize)
    }
}

private fun pwmTierColor(percent: Float): Color = when {
    percent > 80f -> Color(0xFFEF5350)
    percent > 60f -> Color(0xFFFFA726)
    percent > 40f -> Color(0xFFFFCA28)
    else -> Color(0xFF66BB6A)
}

@Composable
private fun BatteryChip(icon: ImageVector, percent: Int, fontSize: TextUnit, iconSize: Dp) {
    val tint = batteryTint(percent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = "$percent",
            fontSize = fontSize,
            color = tint,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun batteryTint(percent: Int): Color = when {
    percent <= 0 -> Color(0xFF606060)
    percent < 15 -> Color(0xFFE53935)
    percent < 30 -> Color(0xFFFFB300)
    else -> Color(0xFF66BB6A)
}

@Composable
private fun ActionRow(state: WatchState, accent: Color, buttonSize: Dp, iconSize: Dp) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.hasHorn) {
            Button(
                onClick = { WatchStateRepository.sendControl(context, WatchControl.HORN) },
                colors = ButtonDefaults.primaryButtonColors(
                    backgroundColor = accent,
                    contentColor = Color.Black
                ),
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    imageVector = Icons.Filled.Campaign,
                    contentDescription = stringResource(R.string.watch_horn),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
        if (state.hasLight) {
            Button(
                onClick = {
                    val intent = if (state.lightOn) WatchControl.LIGHT_OFF else WatchControl.LIGHT_ON
                    WatchStateRepository.sendControl(context, intent)
                },
                colors = ButtonDefaults.secondaryButtonColors(
                    backgroundColor = if (state.lightOn) accent.copy(alpha = 0.30f) else Color(0xFF2A2A2A),
                    contentColor = if (state.lightOn) accent else Color(0xFFB0B0B0)
                ),
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    imageVector = Icons.Filled.FlashOn,
                    contentDescription = stringResource(R.string.watch_light),
                    tint = if (state.lightOn) Color(0xFFFFC107) else Color(0xFF606060),
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun DetailsScreen(state: WatchState, accent: Color) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!state.connected) {
            DisconnectedPlaceholder()
            return@BoxWithConstraints
        }
        val sw = maxWidth.value
        val labelSp = (sw * 0.034f).coerceIn(10f, 13f).sp
        val valueSp = (sw * 0.038f).coerceIn(11f, 14f).sp
        val headerSp = (sw * 0.040f).coerceIn(12f, 15f).sp
        val speedSp = (sw * 0.060f).coerceIn(18f, 26f).sp
        val labelWidth = (sw * 0.22f).coerceIn(60f, 90f).dp
        val valueWidth = (sw * 0.28f).coerceIn(75f, 110f).dp

        val imperial = state.imperialUnits
        val distUnit = WatchUnits.distanceUnit(imperial)
        val tempUnit = WatchUnits.tempUnit(imperial)
        val speedUnit = WatchUnits.speedUnit(imperial)
        val tripDisplay = WatchUnits.distance(state.tripKm, imperial)
        val tempDisplay = WatchUnits.temperature(state.temperatureC, imperial)
        val speedDisplay = WatchUnits.speed(state.speedKmh, imperial)
        val powerW = state.voltage * state.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (state.wheelName.isNotBlank()) {
                Text(
                    text = state.wheelName,
                    fontSize = headerSp,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "%.0f %s".format(speedDisplay, speedUnit),
                fontSize = speedSp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            DetailRow(R.string.watch_voltage_label, "%.1f V".format(state.voltage), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_current_label, "%.1f A".format(state.current), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_power_label, "%.0f W".format(powerW), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_pwm_label, "%.0f %%".format(state.pwmPercent), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_temp_label, "%.0f %s".format(tempDisplay, tempUnit), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_torque_label, "%.1f".format(state.torque), labelSp, valueSp, labelWidth, valueWidth)
            DetailRow(R.string.watch_trip_label, "%.2f %s".format(tripDisplay, distUnit), labelSp, valueSp, labelWidth, valueWidth)
        }
    }
}

@Composable
private fun DetailRow(
    labelRes: Int,
    value: String,
    labelSp: TextUnit,
    valueSp: TextUnit,
    labelWidth: Dp,
    valueWidth: Dp
) {
    // Fixed-width label + fixed-width value gives a tabular alignment so
    // values stack vertically across rows. The whole row is centred by the
    // Column's CenterHorizontally alignment.
    Row(
        modifier = Modifier.wrapContentWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = labelSp,
            color = Color(0xFF9AA0A6),
            textAlign = TextAlign.End,
            modifier = Modifier.width(labelWidth)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = valueSp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(valueWidth)
        )
    }
}

/**
 * Reads the watch's own battery via the sticky ACTION_BATTERY_CHANGED broadcast.
 * `BATTERY_PROPERTY_CAPACITY` is unreliable on some Wear OS skins, so the
 * receiver picks up the regular OS broadcasts and unregisters with the
 * composition.
 */
@Composable
private fun rememberWatchBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(initialBatteryPercent(context)) }
    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    percent = (level * 100 / scale).coerceIn(0, 100)
                }
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return percent
}

private fun initialBatteryPercent(context: android.content.Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
}
