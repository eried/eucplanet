package com.eried.eucplanet.wear.ui.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/** Dash placeholder used when a metric isn't live. Matches the phone dashboard. */
internal const val DASH = "--"

/**
 * Recomposes the caller every second with the current wall-clock millis.
 * Used to time-check the freshness of the last push from the phone so the
 * battery / connection-state UI flips to "stale" without needing a fresh
 * push to trigger recomposition.
 */
@Composable
internal fun rememberSecondTick(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
/**
 * Speed-number color matched to the phone dashboard's SpeedGauge logic:
 * when the gauge color band is visible we tint by the same orange/red thresholds
 * the user configured; when the band is hidden we stay on the safe-tier green.
 * Project palette (GaugeAccentRed / Orange / Green), not Material 400 swatches,
 * so the watch and phone read as the same hue.
 */
internal fun speedBandColor(
    speedKmh: Float,
    maxSpeedKmh: Float,
    showBand: Boolean,
    orangePct: Int,
    redPct: Int,
    colors: WatchColors
): Color {
    if (!showBand) return colors.gaugeFill
    val orangeFrac = (orangePct / 100f).coerceIn(0.25f, 0.95f)
    val redFrac = (redPct / 100f).coerceIn(orangeFrac + 0.01f, 1f)
    val frac = (speedKmh / maxSpeedKmh).coerceIn(0f, 1f)
    return when {
        frac >= redFrac    -> colors.gaugeDanger
        frac >= orangeFrac -> colors.gaugeWarn
        else               -> colors.gaugeFill
    }
}
internal fun vibrate(context: Context, ms: Long) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator?.hasVibrator() != true) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(ms)
    }
}
