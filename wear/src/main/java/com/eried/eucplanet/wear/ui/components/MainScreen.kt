package com.eried.eucplanet.wear.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.ui.WatchNotifier
import com.eried.eucplanet.wear.bridge.WatchControl
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.components.preview.WatchLargeRoundPreview
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewData
import com.eried.eucplanet.wear.ui.components.preview.WatchPreviewParameterProvider
import com.eried.eucplanet.wear.ui.components.preview.WatchSmallRoundPreview
import com.eried.eucplanet.wear.ui.iconForAction
import com.eried.eucplanet.wear.ui.labelForAction
import com.eried.eucplanet.wear.ui.utils.DASH
import com.eried.eucplanet.wear.ui.utils.LocalWatchColors
import com.eried.eucplanet.wear.ui.utils.WatchColors
import com.eried.eucplanet.wear.ui.utils.WatchTheme
import com.eried.eucplanet.wear.ui.utils.WatchUnits
import com.eried.eucplanet.wear.ui.utils.rememberSecondTick
import com.eried.eucplanet.wear.ui.utils.speedBandColor
import com.eried.eucplanet.wear.ui.utils.vibrate

@WatchLargeRoundPreview
@WatchSmallRoundPreview
@Composable
internal fun MainScreenPreview(
    @PreviewParameter(WatchPreviewParameterProvider::class) data: WatchPreviewData,
) {
    WatchTheme(data.state) { accent ->
        MainScreenContent(
            state = data.state,
            accent = accent,
            lastPushAtMs = data.lastPushAtMs,
            nowMs = data.nowMs,
            watchBatteryPercent = data.watchBatteryPercent,
            onButtonTap = { _, _ -> },
            onButtonLongPress = {},
        )
    }
}
@Composable
internal fun MainScreen(state: WatchState, accent: Color) {
    val context = LocalContext.current
    val lastPushAtMs by WatchStateRepository.lastPushAtMs.collectAsStateWithLifecycle()
    val nowMs = rememberSecondTick()
    val watchBatteryPercent = rememberWatchBatteryPercent()
    MainScreenContent(
        state = state,
        accent = accent,
        lastPushAtMs = lastPushAtMs,
        nowMs = nowMs,
        watchBatteryPercent = watchBatteryPercent,
        onButtonTap = { clickAction, holdAction ->
            if (clickAction == "NONE" && holdAction != "NONE") {
                val label = labelForAction(context, holdAction) ?: holdAction
                WatchNotifier.post(context.getString(R.string.watch_action_long_press_hint, label))
            } else {
                sendDebugEvent(context, "tapButton act=$clickAction")
                fireAction(context, state, clickAction, announce = false)
            }
        },
        onButtonLongPress = { action ->
            sendDebugEvent(context, "holdButton act=$action")
            fireAction(context, state, action, announce = true)
        },
    )
}

@Composable
private fun MainScreenContent(
    state: WatchState,
    accent: Color,
    lastPushAtMs: Long,
    nowMs: Long,
    watchBatteryPercent: Int,
    onButtonTap: (clickAction: String, holdAction: String) -> Unit,
    onButtonLongPress: (action: String) -> Unit,
) {
    val colors = LocalWatchColors.current
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
            .background(colors.background)
            // Virtual orientation lets the rider tilt the dial in software when the wheel
            // is in motion, applied to the first screen only so the details screen stays
            // in its canonical orientation. Clamped on the phone side to [-90..90].
            .rotate(animatedRotation),
        contentAlignment = Alignment.Center
    ) {
        // The disconnected state is rendered by zeroing every metric and
        // graying the action buttons rather than swapping in a placeholder
        // screen; the rider always sees the same layout, and the second
        // page (DetailsScreen) is where the explicit "Disconnected" hint
        // lives. Mirrors the phone dashboard's behaviour.
        val sw = maxWidth.value
        // Min clamps are the size users actually see on every Wear OS device:
        // sw is ~160-225 dp across the device range, so the scale-factor side
        // of these formulas never wins. Mins have to be readable on their own.
        val batteryFontSp = (sw * 0.034f).coerceIn(11f, 14f).sp
        val batteryIconDp = (sw * 0.038f).coerceIn(12f, 15f).dp
        val batterySpacingDp = (sw * 0.018f).coerceIn(5f, 9f).dp
        val buttonDp = (sw * 0.127f).coerceIn(40f, 56f).dp
        val buttonIconDp = (sw * 0.060f).coerceIn(20f, 26f).dp
        // "Prioritize PWM" inverts the size hierarchy: when on, speed shrinks
        // by ~35% and PWM (bar + number) grows by ~60% so cutout-margin becomes
        // the dominant glance signal.
        val prioritizePwm = state.prioritizePwm
        val speedFontSp = (sw * if (prioritizePwm) 0.16f else 0.245f).coerceIn(40f, 92f).sp
        val maxSpeed = if (state.maxSpeedKmh > 0f) state.maxSpeedKmh else 70f
        val loadBarWidth = (sw * if (prioritizePwm) 0.55f else 0.30f).coerceIn(82f, 240f).dp
        // In Prioritize PWM mode the bar becomes the dominant glance, go thicker
        // than the previous 0.030f (10-14 dp) and ride the new 5-22 dp band so it
        // reads as the focal indicator even on smaller round faces.
        val loadBarHeight = (sw * if (prioritizePwm) 0.060f else 0.018f).coerceIn(5f, 22f).dp
        val centerOffsetY = -(sw * 0.06f).coerceIn(18f, 28f).dp
        val watchPercent = watchBatteryPercent


        // Telemetry is "live" only while the phone has pushed something in
        // the last 3 s. If the phone app dies (process kill, BT range, user
        // force-stop) the last DataMap stays in memory forever; without this
        // gate the watch dial reads the last received speed indefinitely,
        // which is dangerous on a moving rider's wrist.
        val phoneAlive = lastPushAtMs > 0L && (nowMs - lastPushAtMs) < 3_000L

        // Smoothly glide the displayed speed between telemetry pushes (~4-5 Hz)
        // so the dial arc and the speed number track continuously instead of
        // snapping. ~250 ms linear ≈ one update interval: no lag, no stutter.
        // When telemetry goes stale the target drops to 0 and the animation to
        // zero is fine; we never animate on top of a frozen frame.
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
            fillColor = colors.gaugeFill,
            warnColor = colors.gaugeWarn,
            dangerColor = colors.gaugeDanger,
            trackColor = colors.gaugeTrack,
            dimColor = colors.textSecondary,
            fullBleed = true,
            drawSpeedText = false,
            gpsSpeedKmh = if (phoneAlive) state.gpsSpeedKmh else Float.NaN,
            gpsDotColor = if (state.gpsSource == "EXTERNAL") colors.gpsExternal else colors.gpsPhone,
            modifier = Modifier.fillMaxSize()
        )

        // Speed number + optional unit + PWM cluster, slightly above center so
        // the bottom cluster (batteries + buttons) has more room.
        // The unit is tiny and rendered with an invisible mirror on the left
        // so the speed glyph stays visually centred; the row is symmetric
        // around the speed even when the unit is shown.
        val unitSp = (sw * 0.030f).coerceIn(11f, 14f).sp
        // Prioritize PWM blows up the number; was 0.075f / 28sp cap, now 0.11f / 40sp cap
        // so on a round 454-px Wear face the PWM glyph reads as the dial's headline.
        val pwmNumberSp = (sw * if (prioritizePwm) 0.110f else 0.038f).coerceIn(12f, 40f).sp
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
                // codes; otherwise a watch booted before the phone app would
                // default to km/h and confuse an mph user. Once phoneSynced flips
                // true, the unit re-appears (subject to the showSpeedUnit setting).
                // Hide the unit also when phone is stale; without telemetry
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
                // Band ON → band tier wins (even over custom accent; it's a safety signal).
                // Band OFF → custom accent wins, else safe-green.
                // Use the animated speed so the number stays in lockstep with
                // the gliding gauge arc above.
                val speedTextColor = if (state.showGaugeBand) {
                    speedBandColor(
                        animatedSpeed, maxSpeed,
                        showBand = true,
                        state.gaugeOrangeThresholdPct,
                        state.gaugeRedThresholdPct,
                        colors
                    )
                } else if (useAccent) accent else colors.gaugeFill
                // Stale → dash so the rider sees "no signal" rather than a
                // frozen number that might match their current speed by chance.
                Text(
                    text = if (phoneAlive)
                        "%.0f".format(WatchUnits.speed(animatedSpeed, state.speedUnit))
                    else DASH,
                    fontSize = speedFontSp,
                    fontWeight = FontWeight.Bold,
                    color = if (phoneAlive) speedTextColor else colors.textDisabled
                )
                if (showUnit) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = WatchUnits.speedUnit(LocalContext.current, state.speedUnit),
                        fontSize = unitSp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
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
                    val pwmSafeColor = if (pwmAccent) accent else colors.gaugeFill
                    // pwmLive treats a stale phone the same as a disconnected
                    // wheel; without a fresh push we don't know whether the
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
                            state.pwmPercent >= 80f -> colors.gaugeDanger
                            state.pwmPercent >= 60f -> colors.gaugeWarn
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
                            // Always use the short "PWM:": it's the standard EUC
                            // term and the long-form "Load (PWM):" wasted glance time.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.watch_pwm_prefix),
                                    fontSize = pwmNumberSp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary
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
            ActionRow(
                state = state,
                accent = accent,
                buttonSize = buttonDp,
                iconSize = buttonIconDp,
                onButtonTap = onButtonTap,
                onButtonLongPress = onButtonLongPress,
            )
        }
    }
}
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
    val colors = LocalWatchColors.current
    val pct = percent.coerceIn(0f, 100f)
    val fillColor = when {
        pct >= 80f -> colors.gaugeDanger
        pct >= 60f -> colors.gaugeWarn
        else -> safeColor
    }
    val trackColor = colors.gaugeTrack
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
    // out, same logic the dashboard uses for the speed reading.
    val colors = LocalWatchColors.current
    val tint = when {
        useAccentTint -> accent
        percent == null -> colors.safe
        else -> batteryTint(percent, colors)
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

private fun batteryTint(percent: Int, colors: WatchColors): Color = when {
    percent <= 0 -> colors.textDisabled
    percent < 15 -> colors.batteryLow
    percent < 30 -> colors.gaugeWarn
    else -> colors.battery
}

@Composable
private fun ActionRow(
    state: WatchState,
    accent: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onButtonTap: (clickAction: String, holdAction: String) -> Unit,
    onButtonLongPress: (action: String) -> Unit,
) {
    val live = state.connected
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ConfigurableActionButton(
            state = state,
            accent = accent,
            buttonSize = buttonSize,
            iconSize = iconSize,
            clickAction = state.screen1Click,
            holdAction = state.screen1Hold,
            onTap = { onButtonTap(state.screen1Click, state.screen1Hold) },
            onLongPress = state.screen1Hold.takeUnless { it == "NONE" }?.let {
                { onButtonLongPress(it) }
            },
            // Button 1 keeps the primary (accent) styling that the Horn button
            // had, so the default Horn binding still POPs visually.
            stylePrimary = true,
            live = live,
        )
        ConfigurableActionButton(
            state = state,
            accent = accent,
            buttonSize = buttonSize,
            iconSize = iconSize,
            clickAction = state.screen2Click,
            holdAction = state.screen2Hold,
            onTap = { onButtonTap(state.screen2Click, state.screen2Hold) },
            onLongPress = state.screen2Hold.takeUnless { it == "NONE" }?.let {
                { onButtonLongPress(it) }
            },
            stylePrimary = false,
            live = live,
        )
    }
}
@Composable
private fun ConfigurableActionButton(
    state: WatchState,
    accent: Color,
    buttonSize: Dp,
    iconSize: Dp,
    clickAction: String,
    holdAction: String,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    stylePrimary: Boolean,
    live: Boolean,
) {
    val context = LocalContext.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    if (clickAction == "NONE" && holdAction == "NONE") return
    val icon = iconForAction(clickAction)
        ?: iconForAction(holdAction)
        ?: return

    // Stateful tinting for actions whose UI usually reflects on/off: light,
    // lock, recording. Other actions render with the neutral palette.
    val colors = LocalWatchColors.current
    val isLightOn = clickAction == "LIGHT_TOGGLE" && state.lightOn
    val disabledBg = colors.surface
    val disabledFg = colors.textDisabled
    val backgroundColor = when {
        !live -> disabledBg
        stylePrimary -> accent
        isLightOn -> accent.copy(alpha = 0.30f)
        else -> colors.surfaceVariant
    }
    val contentColor = when {
        !live -> disabledFg
        stylePrimary -> colors.onAccent
        isLightOn -> accent
        else -> colors.textSecondary
    }
    val tintColor = when {
        !live -> disabledFg
        stylePrimary -> colors.onAccent
        // Light-on uses the accent (matches the phone dashboard's tile rule).
        // Default accent falls back to the warn hue so the "torch is on" cue
        // still reads at a glance.
        clickAction == "LIGHT_TOGGLE" && state.lightOn ->
            if (state.accentKey != "default") accent else colors.gaugeWarn
        clickAction == "LIGHT_TOGGLE" -> colors.textDisabled
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
                    onTap = { currentOnTap() },
                    onLongPress = if (holdAction != "NONE") {
                        { currentOnLongPress?.invoke() }
                    } else null,
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
/**
 * Input-event report for the phone's Service Mode Wearables tab. No-op
 * unless the phone flagged diag recording in the state frames, so a normal
 * ride sends nothing extra over the Data Layer.
 *
 * Reads the LIVE repository state, not a composable-captured snapshot: the
 * tap handlers' pointerInput closure only restarts on the binding keys, so
 * a captured state would still hold diagOn=false from before Service Mode
 * was switched on (found by the emulator gate test).
 */
private fun sendDebugEvent(context: Context, msg: String) {
    if (!WatchStateRepository.state.value.diagOn) return
    WatchStateRepository.sendControl(
        context, WatchControl.DEBUG_PREFIX + msg
    )
}

private fun fireAction(
    context: Context,
    state: WatchState,
    action: String,
    announce: Boolean
) {
    if (action == "NONE") return
    val payload = when (action) {
        "HORN" -> WatchControl.HORN
        "LIGHT_TOGGLE" -> if (state.lightOn) WatchControl.LIGHT_OFF else WatchControl.LIGHT_ON
        else -> WatchControl.ACTION_PREFIX + action
    }
    WatchStateRepository.sendControl(context, payload)
    if (state.hapticOnAction) vibrate(context, 50L)
    if (announce) {
        WatchNotifier.post(labelForAction(context, action) ?: action)
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
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
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

private fun initialBatteryPercent(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
}
