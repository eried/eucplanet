package com.eried.eucplanet.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.wear.compose.material.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.components.preview.WatchLargeRoundPreview
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewData
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewParameterProvider
import com.eried.eucplanet.wear.ui.components.preview.WatchSmallRoundPreview
import com.eried.eucplanet.wear.ui.utils.DASH
import com.eried.eucplanet.wear.ui.utils.LocalWatchColors
import com.eried.eucplanet.wear.ui.utils.WatchTheme
import com.eried.eucplanet.wear.ui.utils.WatchUnits
import com.eried.eucplanet.wear.ui.utils.rememberSecondTick
import com.eried.eucplanet.wear.ui.utils.speedBandColor

@WatchLargeRoundPreview
@WatchSmallRoundPreview
@Composable
internal fun DetailsScreenPreview(
    @PreviewParameter(WatchPreviewParameterProvider::class) data: WatchPreviewData,
) {
    WatchTheme(data.state) { accent ->
        DetailsScreenContent(
            state = data.state,
            accent = accent,
            lastPushAtMs = data.lastPushAtMs,
            nowMs = data.nowMs,
        )
    }
}
@Composable
internal fun DetailsScreen(state: WatchState, accent: Color) {
    val lastPushAtMs by WatchStateRepository.lastPushAtMs.collectAsStateWithLifecycle()
    val nowMs = rememberSecondTick()
    DetailsScreenContent(
        state = state,
        accent = accent,
        lastPushAtMs = lastPushAtMs,
        nowMs = nowMs,
    )
}

@Composable
private fun DetailsScreenContent(
    state: WatchState,
    accent: Color,
    lastPushAtMs: Long,
    nowMs: Long,
) {
    val colors = LocalWatchColors.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center
    ) {
        val sw = maxWidth.value
        // See MainScreen comment: min clamps drive the rendered size on every
        // Wear OS form factor, so bump them to readable values.
        val labelSp = (sw * 0.034f).coerceIn(12f, 15f).sp
        val valueSp = (sw * 0.038f).coerceIn(13f, 16f).sp
        val headerSp = (sw * 0.030f).coerceIn(11f, 13f).sp
        val speedSp = (sw * 0.060f).coerceIn(22f, 30f).sp
        val speedUnitSp = (sw * 0.030f).coerceIn(11f, 13f).sp
        // Label / value widths grow with the new minimum font sizes so the
        // longer labels ("Voltage", "Current") still fit on a single line.
        val labelWidth = (sw * 0.22f).coerceIn(68f, 96f).dp
        val valueWidth = (sw * 0.28f).coerceIn(84f, 114f).dp

        // Hide all unit labels until the phone has actually told us which unit
        // system the rider uses; otherwise an mph user sees km/h on launch and
        // assumes the wrong thing.
        val unitsKnown = state.phoneSynced
        // phoneAlive mirrors the main-screen check: 3 s without a push from
        // the phone means we're showing stale data. Without this every row
        // and the headline speed keep displaying their last live values
        // forever (until the watch app process is killed).
        val phoneAlive = lastPushAtMs > 0L && (nowMs - lastPushAtMs) < 3_000L
        val distUnit = if (unitsKnown && phoneAlive) WatchUnits.distanceUnit(state.distanceUnit) else ""
        val tempUnit = if (unitsKnown && phoneAlive) WatchUnits.tempUnit(state.tempUnit) else ""
        val speedUnit = if (unitsKnown && phoneAlive) WatchUnits.speedUnit(LocalContext.current, state.speedUnit) else ""
        val tripDisplay = WatchUnits.distance(state.tripKm, state.distanceUnit)
        val tempDisplay = WatchUnits.temperature(state.temperatureC, state.tempUnit)
        val speedDisplay = WatchUnits.speed(state.speedKmh, state.speedUnit)
        val powerW = state.voltage * state.current

        val useAccent = state.accentKey != "default"
        val detailMaxSpeed = if (state.maxSpeedKmh > 0f) state.maxSpeedKmh else 70f
        val speedColor = if (useAccent) accent
            else speedBandColor(
            state.speedKmh, detailMaxSpeed, true,
            state.gaugeOrangeThresholdPct, state.gaugeRedThresholdPct, colors
        )
        val live = state.connected && phoneAlive
        val cyanColor = accent
        val tempColor = when {
            useAccent -> accent
            !live -> colors.gaugeFill
            state.temperatureC > 60f -> colors.gaugeDanger
            state.temperatureC > 45f -> colors.gaugeWarn
            else -> colors.gaugeFill
        }
        val pwmColor = when {
            useAccent -> accent
            !live -> colors.gaugeFill
            state.pwmPercent > 80f -> colors.gaugeDanger
            state.pwmPercent > 60f -> colors.gaugeWarn
            else -> colors.gaugeFill
        }

        // Scrollable column rather than ScalingLazyColumn: the wear-compose
        // ScalingLazyColumn currently crashes on Android 15+ trying to read
        // the restricted Settings.Global.reduce_motion key when targetSdk
        // is above 34 (a known library bug fixed only in newer wear-compose
        // versions). Plain Column + verticalScroll gives us scrolling without
        // the system-settings dependency; the rider drags to see anything
        // pushed off the round display by the bigger fonts. Top + bottom
        // padding scale with display width so the round bezel doesn't clip
        // the first or last row when scrolled to the extremes.
        val edgePad = (sw * 0.14f).coerceIn(20f, 40f).dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = edgePad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (!state.connected || !phoneAlive) {
                Text(
                    text = stringResource(R.string.watch_disconnected),
                    fontSize = headerSp,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            } else if (state.wheelName.isNotBlank()) {
                Text(
                    text = state.wheelName,
                    fontSize = headerSp,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (phoneAlive) "%.0f".format(speedDisplay) else DASH,
                    fontSize = speedSp,
                    fontWeight = FontWeight.Bold,
                    color = if (phoneAlive) speedColor else colors.textDisabled
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = speedUnit,
                    fontSize = speedUnitSp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = (speedSp.value * 0.18f).dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            DetailRow(R.string.watch_voltage_label, if (live) "%.1f V".format(state.voltage) else DASH, labelSp, valueSp, labelWidth, valueWidth, cyanColor)
            DetailRow(R.string.watch_current_label, if (live) "%.1f A".format(state.current) else DASH, labelSp, valueSp, labelWidth, valueWidth, cyanColor)
            DetailRow(R.string.watch_power_label, if (live) "%.0f W".format(powerW) else DASH, labelSp, valueSp, labelWidth, valueWidth, cyanColor)
            DetailRow(R.string.watch_pwm_label, if (live) "%.0f %%".format(state.pwmPercent) else DASH, labelSp, valueSp, labelWidth, valueWidth, pwmColor)
            DetailRow(R.string.watch_temp_label, if (live) "%.0f %s".format(tempDisplay, tempUnit) else DASH, labelSp, valueSp, labelWidth, valueWidth, tempColor)
            DetailRow(R.string.watch_torque_label, if (live) "%.1f".format(state.torque) else DASH, labelSp, valueSp, labelWidth, valueWidth, cyanColor)
            DetailRow(R.string.watch_trip_label, if (live) "%.2f %s".format(tripDisplay, distUnit) else DASH, labelSp, valueSp, labelWidth, valueWidth, cyanColor)
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
    valueWidth: Dp,
    valueColor: Color = Color.White
) {
    val colors = LocalWatchColors.current
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
            color = colors.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(labelWidth)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = valueSp,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(valueWidth)
        )
    }
}
