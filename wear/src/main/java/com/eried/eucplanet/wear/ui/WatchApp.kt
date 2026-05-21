package com.eried.eucplanet.wear.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.bridge.WatchControl
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository

/** Em-dash placeholder used when a metric isn't live. Matches the phone dashboard. */
private const val DASH = "—"

/**
 * Recomposes the caller every second with the current wall-clock millis.
 * Used to time-check the freshness of the last push from the phone so the
 * battery / connection-state UI flips to "stale" without needing a fresh
 * push to trigger recomposition.
 */
@Composable
private fun rememberSecondTick(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

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
            Box(modifier = Modifier.fillMaxSize()) {
                val hPager = rememberPagerState(pageCount = { 2 })
                HorizontalPager(state = hPager, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> MainScreen(state, accent)
                        1 -> DetailsScreen(state, accent)
                    }
                }
                // Navigation popup mirrors the phone: shown only while the phone
                // popup is up. It fades in and out so cues are not jarring.
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.navActive,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut()
                ) {
                    NavWatchOverlay(state, accent)
                }
            }
        }
    }
}

/**
 * The watch's simplified navigation popup: a full-screen card with the big
 * direction arrow and two text lines pushed down from the phone. The wrist
 * buzzes whenever the instruction changes.
 */
@Composable
private fun NavWatchOverlay(state: WatchState, accent: Color) {
    val context = LocalContext.current
    val angle by animateFloatAsState(
        targetValue = if (state.navArrived) 0f else state.navAngle,
        animationSpec = tween(durationMillis = 350),
        label = "navArrow"
    )
    var lastPrimary by remember { mutableStateOf("") }
    var lastVibrateAt by remember { mutableStateOf(0L) }
    LaunchedEffect(state.navPrimary) {
        val now = System.currentTimeMillis()
        if (state.navPrimary.isNotBlank() && lastPrimary.isNotBlank() &&
            state.navPrimary != lastPrimary && now - lastVibrateAt > 1500L
        ) {
            vibrate(context, 160L)
            lastVibrateAt = now
        }
        lastPrimary = state.navPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Consume every gesture so a swipe can't reach the pager behind
            // the popup while navigation is showing.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Navigation,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(74.dp)
                    .rotate(angle)
            )
            Spacer(Modifier.height(8.dp))
            if (state.navPrimary.isNotBlank()) {
                Text(
                    text = state.navPrimary,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontSize = 17.sp,
                    maxLines = 2
                )
            }
            if (state.navDistance.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.navDistance,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }
        }
    }
}

@Composable
private fun MainScreen(state: WatchState, accent: Color) {
    // Smooth the virtual rotation so re-anchoring from a setting change or a
    // post-disconnect snap doesn't pop. Single fixed 1 s ease-in-out covers
    // every angle delta with no per-frame math.
    val animatedRotation by animateFloatAsState(
        targetValue = state.dialRotationDeg.toFloat(),
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "dialRotation"
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Virtual orientation lets the rider tilt the dial in software when the wheel
            // is in motion — applied to the first screen only so the details screen stays
            // in its canonical orientation. Clamped on the phone side to [-90..90].
            .rotate(animatedRotation),
        contentAlignment = Alignment.Center
    ) {
        // The disconnected state is rendered by zeroing every metric and
        // graying the action buttons rather than swapping in a placeholder
        // screen — the rider always sees the same layout, and the second
        // page (DetailsScreen) is where the explicit "Disconnected" hint
        // lives. Mirrors the phone dashboard's behaviour.
        val sw = maxWidth.value
        val batteryFontSp = (sw * 0.034f).coerceIn(9f, 12f).sp
        val batteryIconDp = (sw * 0.038f).coerceIn(10f, 14f).dp
        val batterySpacingDp = (sw * 0.018f).coerceIn(5f, 9f).dp
        // ~10% bigger than the previous 0.115f / 0.055f values.
        val buttonDp = (sw * 0.127f).coerceIn(37f, 55f).dp
        val buttonIconDp = (sw * 0.060f).coerceIn(18f, 26f).dp
        // "Prioritize PWM" inverts the size hierarchy — when on, speed shrinks
        // by ~35% and PWM (bar + number) grows by ~60% so cutout-margin becomes
        // the dominant glance signal.
        val prioritizePwm = state.prioritizePwm
        val speedFontSp = (sw * if (prioritizePwm) 0.16f else 0.245f).coerceIn(40f, 92f).sp
        val maxSpeed = if (state.maxSpeedKmh > 0f) state.maxSpeedKmh else 70f
        val loadBarWidth = (sw * if (prioritizePwm) 0.55f else 0.30f).coerceIn(82f, 240f).dp
        // In Prioritize PWM mode the bar becomes the dominant glance — go thicker
        // than the previous 0.030f (10-14 dp) and ride the new 5-22 dp band so it
        // reads as the focal indicator even on smaller round faces.
        val loadBarHeight = (sw * if (prioritizePwm) 0.060f else 0.018f).coerceIn(5f, 22f).dp
        val centerOffsetY = -(sw * 0.06f).coerceIn(18f, 28f).dp
        val watchPercent = rememberWatchBatteryPercent()

        // Telemetry is "live" only while the phone has pushed something in
        // the last 3 s. If the phone app dies (process kill, BT range, user
        // force-stop) the last DataMap stays in memory forever; without this
        // gate the watch dial reads the last received speed indefinitely,
        // which is dangerous on a moving rider's wrist.
        val lastPushTop by WatchStateRepository.lastPushAtMs.collectAsStateWithLifecycle()
        val nowTickTop = rememberSecondTick()
        val phoneAlive = lastPushTop > 0 && (nowTickTop - lastPushTop) < 3000L

        // Smoothly glide the displayed speed between telemetry pushes (~4-5 Hz)
        // so the dial arc and the speed number track continuously instead of
        // snapping. ~250 ms linear ≈ one update interval: no lag, no stutter.
        // When telemetry goes stale the target drops to 0 and the animation to
        // zero is fine — we never animate on top of a frozen frame.
        val animatedSpeed by animateFloatAsState(
            targetValue = if (phoneAlive) state.speedKmh else 0f,
            animationSpec = tween(durationMillis = 250, easing = LinearEasing),
            label = "speed"
        )

        // Full-bleed dial as the background frame. Color-band visibility and
        // its orange/red thresholds follow the phone's Display settings so the
        // two surfaces always agree. Arc collapses to zero when telemetry is
        // stale so a frozen "23 km/h" can't deceive a stopped rider.
        SpeedGauge(
            speed = animatedSpeed,
            maxSpeed = maxSpeed,
            speedUnit = state.speedUnit,
            accent = accent,
            useAccent = state.accentKey != "default",
            showColorBand = state.showGaugeBand,
            orangeThresholdPct = state.gaugeOrangeThresholdPct,
            redThresholdPct = state.gaugeRedThresholdPct,
            fullBleed = true,
            drawSpeedText = false,
            gpsSpeedKmh = if (phoneAlive) state.gpsSpeedKmh else Float.NaN,
            gpsDotColor = gpsSourceColor(state.gpsSource),
            modifier = Modifier.fillMaxSize()
        )

        // Speed number + optional unit + PWM cluster, slightly above center so
        // the bottom cluster (batteries + buttons) has more room.
        // The unit is tiny and rendered with an invisible mirror on the left
        // so the speed glyph stays visually centred — the row is symmetric
        // around the speed even when the unit is shown.
        val unitSp = (sw * 0.030f).coerceIn(9f, 12f).sp
        // Prioritize PWM blows up the number — was 0.075f / 28sp cap, now 0.11f / 40sp cap
        // so on a round 454-px Wear face the PWM glyph reads as the dial's headline.
        val pwmNumberSp = (sw * if (prioritizePwm) 0.110f else 0.038f).coerceIn(11f, 40f).sp
        val showBar = state.pwmDisplay == "BAR" || state.pwmDisplay == "BOTH"
        val showPwmNumber = state.pwmDisplay == "NUMBERS" || state.pwmDisplay == "BOTH"
        // In "BOTH" mode the bar shares a row with the percent number, so we shrink the
        // bar width so a 100% reading doesn't run into the number cluster on either side.
        val showingBothInline = showBar && showPwmNumber
        val barWidthShrink = if (showingBothInline) 0.66f else 1f
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = centerOffsetY),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Don't reveal a unit label until the phone has sent us its unit
                // codes — otherwise a watch booted before the phone app would
                // default to km/h and confuse an mph user. Once phoneSynced flips
                // true, the unit re-appears (subject to the showSpeedUnit setting).
                // Hide the unit also when phone is stale — without telemetry
                // we can't be honest about the value, so the label would lie.
                val showUnit = state.showSpeedUnit && state.phoneSynced && phoneAlive
                if (showUnit) {
                    // Invisible mirror keeps the speed glyph centred.
                    Text(
                        text = WatchUnits.speedUnit(LocalContext.current, state.speedUnit),
                        fontSize = unitSp,
                        color = Color.Transparent,
                        modifier = Modifier.padding(bottom = (sw * 0.05f).coerceIn(12f, 18f).dp)
                    )
                    Spacer(Modifier.width(3.dp))
                }
                val useAccent = state.accentKey != "default"
                // Band ON → band tier wins (even over custom accent — it's a safety signal).
                // Band OFF → custom accent wins, else safe-green.
                // Use the animated speed so the number stays in lockstep with
                // the gliding gauge arc above.
                val speedTextColor = if (state.showGaugeBand) {
                    speedBandColor(
                        animatedSpeed, maxSpeed,
                        showBand = true,
                        state.gaugeOrangeThresholdPct,
                        state.gaugeRedThresholdPct
                    )
                } else if (useAccent) accent else GaugeAccentGreen
                // Stale → dash so the rider sees "no signal" rather than a
                // frozen number that might match their current speed by chance.
                Text(
                    text = if (phoneAlive)
                        "%.0f".format(WatchUnits.speed(animatedSpeed, state.speedUnit))
                    else DASH,
                    fontSize = speedFontSp,
                    fontWeight = FontWeight.Bold,
                    color = if (phoneAlive) speedTextColor else Color(0xFF606060)
                )
                if (showUnit) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = WatchUnits.speedUnit(LocalContext.current, state.speedUnit),
                        fontSize = unitSp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF9AA0A6),
                        modifier = Modifier.padding(bottom = (sw * 0.05f).coerceIn(12f, 18f).dp)
                    )
                }
            }
            Spacer(Modifier.height(1.dp))
            // PWM cluster: bar and % live on the same horizontal row when both
            // are enabled, so the % sits to the right of the bar instead of
            // below it. Each side falls back to its solo layout when only one
            // is enabled.
            if (showBar || showPwmNumber) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.offset(y = -(sw * 0.020f).coerceIn(6f, 12f).dp)
                ) {
                    // PWM safe-zone colour matches the phone dashboard: accent
                    // when a non-default accent is picked, else safe green. The
                    // ≥60 / ≥80 tiers always override with orange / red so the
                    // danger signal can't be hidden by the accent.
                    val pwmAccent = state.accentKey != "default"
                    val pwmSafeColor = if (pwmAccent) accent else GaugeAccentGreen
                    // pwmLive treats a stale phone the same as a disconnected
                    // wheel — without a fresh push we don't know whether the
                    // motor is still hot.
                    val pwmLive = phoneAlive && state.connected
                    if (showBar) {
                        LoadBar(
                            percent = if (pwmLive) state.pwmPercent else 0f,
                            safeColor = pwmSafeColor,
                            modifier = Modifier
                                .width(loadBarWidth * barWidthShrink)
                                .height(loadBarHeight)
                        )
                    }
                    if (showBar && showPwmNumber) Spacer(Modifier.width(6.dp))
                    if (showPwmNumber) {
                        val pwmNumberColor = when {
                            !pwmLive -> pwmSafeColor
                            state.pwmPercent >= 80f -> GaugeAccentRed
                            state.pwmPercent >= 60f -> GaugeAccentOrange
                            else -> pwmSafeColor
                        }
                        val numberText = if (pwmLive) "%.0f%%".format(state.pwmPercent) else DASH
                        if (showBar) {
                            // Bar alongside → just the tier-coloured "N%".
                            Text(
                                text = numberText,
                                fontSize = pwmNumberSp,
                                fontWeight = FontWeight.Medium,
                                color = pwmNumberColor
                            )
                        } else {
                            // Text-only → "PWM:" prefix stays muted grey (caption
                            // colour) so only the live percent reflects load tier.
                            // Always use the short "PWM:" — it's the standard EUC
                            // term and the long-form "Load (PWM):" wasted glance time.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "PWM: ",
                                    fontSize = pwmNumberSp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF9AA0A6)
                                )
                                Text(
                                    text = numberText,
                                    fontSize = pwmNumberSp,
                                    fontWeight = FontWeight.Medium,
                                    color = pwmNumberColor
                                )
                            }
                        }
                    }
                }
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
            // phoneAlive is computed at the top of MainScreen so the whole
            // dashboard shares one freshness check. Wheel battery and phone
            // battery both go stale when phone is silent for >3 s.
            BatteryRow(
                wheelPercent = state.batteryPercent.takeIf {
                    state.showWheelBattery && state.connected && phoneAlive
                },
                phonePercent = if (state.showPhoneBattery && phoneAlive)
                    state.phoneBatteryPercent else null,
                watchPercent = watchPercent.takeIf { state.showWatchBattery },
                showWheelChip = state.showWheelBattery,
                showPhoneChip = state.showPhoneBattery,
                accent = accent,
                useAccentTint = state.accentKey != "default",
                fontSize = batteryFontSp,
                iconSize = batteryIconDp,
                spacing = batterySpacingDp
            )
            ActionRow(state, accent, buttonDp, buttonIconDp)
        }
    }
}

/**
 * Speed-number color matched to the phone dashboard's SpeedGauge logic:
 * when the gauge color band is visible we tint by the same orange/red thresholds
 * the user configured; when the band is hidden we stay on the safe-tier green.
 * Project palette (GaugeAccentRed / Orange / Green) — not Material 400 swatches —
 * so the watch and phone read as the same hue.
 */
private fun speedBandColor(
    speedKmh: Float,
    maxSpeedKmh: Float,
    showBand: Boolean,
    orangePct: Int,
    redPct: Int
): Color {
    if (!showBand) return GaugeAccentGreen
    val orangeFrac = (orangePct / 100f).coerceIn(0.25f, 0.95f)
    val redFrac = (redPct / 100f).coerceIn(orangeFrac + 0.01f, 1f)
    val frac = (speedKmh / maxSpeedKmh).coerceIn(0f, 1f)
    return when {
        frac >= redFrac    -> GaugeAccentRed
        frac >= orangeFrac -> GaugeAccentOrange
        else               -> GaugeAccentGreen
    }
}

@Deprecated("Use speedBandColor; kept for compatibility.")
private fun speedTierColor(speedKmh: Float, maxSpeedKmh: Float): Color =
    speedBandColor(speedKmh, maxSpeedKmh, showBand = true, orangePct = 65, redPct = 85)

/**
 * Compact PWM-as-load progress bar. Track is dim grey; fill colour shifts
 * from green to red as the wheel approaches its torque limit, so a glance
 * tells you how hard the motor is working.
 */
@Composable
private fun LoadBar(
    percent: Float,
    /** Safe-zone fill colour. Matches the phone's PWM rule: accent (if a
     *  non-default accent is picked) or green; the >=60/>=80 tiers always
     *  override with orange/red. */
    safeColor: Color = Color(0xFF66BB6A),
    modifier: Modifier = Modifier
) {
    val pct = percent.coerceIn(0f, 100f)
    val fillColor = when {
        pct >= 80f -> Color(0xFFEF5350)
        pct >= 60f -> Color(0xFFFFA726)
        else -> safeColor
    }
    val trackColor = Color(0xFF333333)
    // Glide the bar fill between telemetry pushes (~4-5 Hz) so it grows and
    // shrinks smoothly instead of jumping. ~250 ms linear matches the speed
    // gauge animation; the colour tier still flips instantly off the raw pct.
    val animatedPct by animateFloatAsState(
        targetValue = pct,
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "pwmBar"
    )
    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius)
        )
        if (animatedPct > 0f) {
            drawRoundRect(
                color = fillColor,
                size = Size(size.width * animatedPct / 100f, size.height),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}

@Composable
private fun BatteryRow(
    wheelPercent: Int?,
    phonePercent: Int?,
    watchPercent: Int?,
    showWheelChip: Boolean,
    showPhoneChip: Boolean,
    accent: Color,
    useAccentTint: Boolean,
    fontSize: TextUnit,
    iconSize: Dp,
    spacing: Dp
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Each chip stays in the row when the user enabled it; the percent
        // becomes null + dash whenever its source isn't live, so the layout
        // doesn't reflow when a wheel disconnects or the phone hasn't pushed.
        if (showWheelChip) {
            BatteryChip(Icons.Filled.ElectricScooter, wheelPercent, accent, useAccentTint, fontSize, iconSize)
        }
        if (showPhoneChip) {
            BatteryChip(Icons.Filled.PhoneAndroid, phonePercent, accent, useAccentTint, fontSize, iconSize)
        }
        watchPercent?.let { BatteryChip(Icons.Filled.Watch, it, accent, useAccentTint, fontSize, iconSize) }
    }
}

private fun pwmTierColor(percent: Float): Color = when {
    percent > 80f -> Color(0xFFEF5350)
    percent > 60f -> Color(0xFFFFA726)
    percent > 40f -> Color(0xFFFFCA28)
    else -> Color(0xFF66BB6A)
}

@Composable
private fun BatteryChip(
    icon: ImageVector,
    percent: Int?,
    accent: Color,
    useAccentTint: Boolean,
    fontSize: TextUnit,
    iconSize: Dp
) {
    // Default accent → tier coloring (green / amber / red) so the rider
    // gets a glanceable sense of remaining range. Any other accent →
    // the chip wears the accent so the watch identity stays consistent.
    // Disconnected (percent == null) falls back to the safe-tier green
    // so the dash matches the speed glyph above it instead of dimming
    // out — same logic the dashboard uses for the speed reading.
    val tint = when {
        useAccentTint -> accent
        percent == null -> GaugeAccentGreen
        else -> batteryTint(percent)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = percent?.toString() ?: DASH,
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
    val live = state.connected
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ConfigurableActionButton(
            context = context,
            state = state,
            accent = accent,
            buttonSize = buttonSize,
            iconSize = iconSize,
            clickAction = state.screen1Click,
            holdAction = state.screen1Hold,
            // Button 1 keeps the primary (accent) styling that the Horn button
            // had, so the default Horn binding still POPs visually.
            stylePrimary = true,
            live = live
        )
        ConfigurableActionButton(
            context = context,
            state = state,
            accent = accent,
            buttonSize = buttonSize,
            iconSize = iconSize,
            clickAction = state.screen2Click,
            holdAction = state.screen2Hold,
            stylePrimary = false,
            live = live
        )
    }
}

/**
 * Renders one of the two on-screen watch buttons keyed off the user's chosen
 * click + hold actions. Tap fires the click action, long-press fires the hold
 * action with a brief toast confirming what triggered (so a buried hold
 * gesture isn't ambiguous). Optional haptic vibrate is gated by the user's
 * `watchHapticOnAction` setting.
 *
 * If both click and hold are NONE, the button isn't drawn — the row collapses
 * so the user can hide a button entirely if they want a single-button layout.
 */
@Composable
private fun ConfigurableActionButton(
    context: Context,
    state: WatchState,
    accent: Color,
    buttonSize: Dp,
    iconSize: Dp,
    clickAction: String,
    holdAction: String,
    stylePrimary: Boolean,
    live: Boolean
) {
    if (clickAction == "NONE" && holdAction == "NONE") return
    val icon = iconForAction(clickAction)
        ?: iconForAction(holdAction)
        ?: return

    // Stateful tinting for actions whose UI usually reflects on/off — light,
    // lock, recording. Other actions render with the neutral palette.
    val isLightOn = clickAction == "LIGHT_TOGGLE" && state.lightOn
    val disabledBg = Color(0xFF1A1A1A)
    val disabledFg = Color(0xFF555555)
    val backgroundColor = when {
        !live -> disabledBg
        stylePrimary -> accent
        isLightOn -> accent.copy(alpha = 0.30f)
        else -> Color(0xFF2A2A2A)
    }
    val contentColor = when {
        !live -> disabledFg
        stylePrimary -> Color.Black
        isLightOn -> accent
        else -> Color(0xFFB0B0B0)
    }
    val tintColor = when {
        !live -> disabledFg
        stylePrimary -> Color.Black
        // Light-on uses the accent (matches the phone dashboard's tile rule).
        // Default accent falls back to yellow so the "torch is on" cue still
        // reads at a glance.
        clickAction == "LIGHT_TOGGLE" && state.lightOn ->
            if (state.accentKey != "default") accent else Color(0xFFFFC107)
        clickAction == "LIGHT_TOGGLE" -> Color(0xFF606060)
        else -> contentColor
    }

    // pointerInput + detectTapGestures is the battle-tested combo for tap +
    // long-press on Compose. The earlier `combinedClickable` setup crashed on
    // Wear emulator (likely interactionSource/indication mismatch in the
    // current Compose foundation version on Wear).
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(clickAction, holdAction, state.lightOn) {
                // Fire regardless of `live`: media keys, trip recording, and
                // voice announce all work without a connected wheel. The
                // wheel-dependent actions (Horn/Light/Lock/Legal) silently
                // no-op on the phone side when there's nothing to talk to,
                // so blocking the click here just makes the watch feel
                // unresponsive.
                detectTapGestures(
                    onTap = {
                        if (clickAction == "NONE" && holdAction != "NONE") {
                            val label = labelForAction(context, holdAction) ?: holdAction
                            Toast.makeText(
                                context,
                                context.getString(R.string.watch_action_long_press_hint, label),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            fireAction(context, state, clickAction, showToast = false)
                        }
                    },
                    onLongPress = if (holdAction != "NONE") {
                        {
                            fireAction(context, state, holdAction, showToast = true)
                        }
                    } else null
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = labelForAction(context, clickAction)
                ?: labelForAction(context, holdAction)
                ?: "",
            tint = tintColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Dispatches a button-bound action to the phone via `/euc/control`. Horn and
 * light keep their dedicated control strings (back-compat with prior watch
 * builds and the phone's PhoneWearListenerService quick path). Everything else
 * uses the `action:<FlicAction>` prefix that the phone routes through
 * FlicManager.dispatchActionByName.
 */
private fun fireAction(
    context: Context,
    state: WatchState,
    action: String,
    showToast: Boolean
) {
    if (action == "NONE") return
    val payload = when (action) {
        "HORN" -> WatchControl.HORN
        "LIGHT_TOGGLE" -> if (state.lightOn) WatchControl.LIGHT_OFF else WatchControl.LIGHT_ON
        else -> WatchControl.ACTION_PREFIX + action
    }
    com.eried.eucplanet.wear.bridge.WatchStateRepository.sendControl(context, payload)
    if (state.hapticOnAction) vibrate(context, 50L)
    if (showToast) {
        val label = labelForAction(context, action) ?: action
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
    }
}

private fun vibrate(context: Context, ms: Long) {
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

@Composable
private fun DetailsScreen(state: WatchState, accent: Color) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val sw = maxWidth.value
        val labelSp = (sw * 0.034f).coerceIn(10f, 13f).sp
        val valueSp = (sw * 0.038f).coerceIn(11f, 14f).sp
        // Header (wheel name / "Disconnected") is meant to be a quiet caption,
        // not a heading — it shrinks under the speed which carries the visual
        // weight on this page.
        val headerSp = (sw * 0.030f).coerceIn(9f, 12f).sp
        val speedSp = (sw * 0.060f).coerceIn(18f, 26f).sp
        val speedUnitSp = (sw * 0.030f).coerceIn(9f, 12f).sp
        val labelWidth = (sw * 0.22f).coerceIn(60f, 90f).dp
        val valueWidth = (sw * 0.28f).coerceIn(75f, 110f).dp

        // Hide all unit labels until the phone has actually told us which unit
        // system the rider uses — otherwise an mph user sees km/h on launch and
        // assumes the wrong thing.
        val unitsKnown = state.phoneSynced
        // phoneAlive mirrors the main-screen check: 3 s without a push from
        // the phone means we're showing stale data. Without this every row
        // and the headline speed keep displaying their last live values
        // forever (until the watch app process is killed).
        val lastPush by WatchStateRepository.lastPushAtMs.collectAsStateWithLifecycle()
        val nowTick = rememberSecondTick()
        val phoneAlive = lastPush > 0 && (nowTick - lastPush) < 3000L
        val distUnit = if (unitsKnown && phoneAlive) WatchUnits.distanceUnit(state.distanceUnit) else ""
        val tempUnit = if (unitsKnown && phoneAlive) WatchUnits.tempUnit(state.tempUnit) else ""
        val speedUnit = if (unitsKnown && phoneAlive) WatchUnits.speedUnit(LocalContext.current, state.speedUnit) else ""
        val tripDisplay = WatchUnits.distance(state.tripKm, state.distanceUnit)
        val tempDisplay = WatchUnits.temperature(state.temperatureC, state.tempUnit)
        val speedDisplay = WatchUnits.speed(state.speedKmh, state.speedUnit)
        val powerW = state.voltage * state.current

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Wheel-name and Disconnected header both wear the same muted
            // caption tint as the phone dashboard's status row — the speed
            // is the page's visual focus, the header is just context.
            // Stale phone counts as disconnected from the rider's POV.
            if (!state.connected || !phoneAlive) {
                Text(
                    text = stringResource(R.string.watch_disconnected),
                    fontSize = headerSp,
                    color = Color(0xFF9AA0A6),
                    fontWeight = FontWeight.SemiBold
                )
            } else if (state.wheelName.isNotBlank()) {
                Text(
                    text = state.wheelName,
                    fontSize = headerSp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Speed number colours per main-screen rules; the km/h unit
            // stays the muted grey from the dashboard's small label.
            val useAccent = state.accentKey != "default"
            val detailMaxSpeed = if (state.maxSpeedKmh > 0f) state.maxSpeedKmh else 70f
            val speedColor = if (useAccent) accent else speedTierColor(state.speedKmh, detailMaxSpeed)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    // Headline speed dashes out the same way the main-screen
                    // glyph does. Without this the page keeps showing the
                    // last reading forever after the phone app dies.
                    text = if (phoneAlive) "%.0f".format(speedDisplay) else DASH,
                    fontSize = speedSp,
                    fontWeight = FontWeight.Bold,
                    color = if (phoneAlive) speedColor else Color(0xFF606060)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = speedUnit,
                    fontSize = speedUnitSp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF9AA0A6),
                    modifier = Modifier.padding(bottom = (speedSp.value * 0.18f).dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            // A live row needs BOTH a connected wheel and a phone that's
            // still pushing — otherwise we dash the whole strip.
            val live = state.connected && phoneAlive
            // Per-metric coloring on default accent mirrors the phone
            // dashboard: voltage / current / power / trip / torque get the
            // accent (which IS cyan when accent=default), temp and PWM use
            // tiered green / amber / red thresholds, battery in BatteryRow
            // is already tier-coloured. Custom accent collapses everything
            // to the picked accent so the watch wears one identity colour.
            // Disconnected dashes wear the same colour the live value
            // would at zero (green safe-tier or accent) so the row reads
            // continuous with the speed glyph at the top of the page.
            val cyanColor = accent
            val tempColor = when {
                useAccent -> accent
                !live -> GaugeAccentGreen
                state.temperatureC > 60f -> GaugeAccentRed
                state.temperatureC > 45f -> GaugeAccentOrange
                else -> GaugeAccentGreen
            }
            val pwmColor = when {
                useAccent -> accent
                !live -> GaugeAccentGreen
                state.pwmPercent > 80f -> GaugeAccentRed
                state.pwmPercent > 60f -> GaugeAccentOrange
                else -> GaugeAccentGreen
            }
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
            color = valueColor,
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
