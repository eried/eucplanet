package com.eried.eucplanet.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.model.SparklineStyle
import com.eried.eucplanet.platform.Math
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.remap
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The dashboard's drawing pieces, shared by the Android and iOS dashboards.

// --- Speed gauge: thick arc dial, no needle, centered speed ---

@Composable
fun SpeedGauge(
    speed: Float,
    maxSpeed: Float,
    speedUnit: String,
    showColorBand: Boolean = false,
    orangeThresholdPct: Int = 65,
    redThresholdPct: Int = 85,
    safeBandColor: Color = MaterialTheme.appColors.gaugeFill,
    /** External GPS speed in km/h, or null when no external GPS box is connected.
     *  Drives a small marker on the dial in [externalAccentColor]. */
    externalSpeed: Float? = null,
    externalAccentColor: Color = MaterialTheme.appColors.metricPosition,
    modifier: Modifier = Modifier
) {
    // Speed-arc + speed-number colour rule (phone & watch share this rule):
    //  - Color band ON  → band tier wins (safe / orange / red).
    //  - Color band OFF → the gaugeFill token.
    val orangeFrac = (orangeThresholdPct / 100f).coerceIn(0.05f, 0.95f)
    val redFrac = (redThresholdPct / 100f).coerceIn(orangeFrac + 0.04f, 0.95f)
    val speedFraction = (speed / maxSpeed).coerceIn(0f, 1f)
    // Gauge band tier colors. Captured into vals here (composable scope) so the
    // Canvas DrawScope below, which can't read MaterialTheme, can still use them.
    // The "orange" approaching tier maps to statusWarn (defaults to AccentOrange,
    // pixel-identical); the "red" tier maps to gaugeDanger.
    val bandWarnColor = MaterialTheme.appColors.statusWarn
    val bandDangerColor = MaterialTheme.appColors.gaugeDanger
    val speedColor = when {
        showColorBand && speedFraction >= redFrac    -> bandDangerColor
        showColorBand && speedFraction >= orangeFrac -> bandWarnColor
        showColorBand                                -> safeBandColor
        else                                         -> safeBandColor
    }
    val trackColor = MaterialTheme.appColors.gaugeTrack
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val displaySpeed = com.eried.eucplanet.util.Units.speed(speed, speedUnit)
    val displayMax = com.eried.eucplanet.util.Units.speed(maxSpeed, speedUnit)
    val maxInt = displayMax.toInt()
    val step = (maxInt / 3f).toInt().coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, maxInt)
    Canvas(modifier = modifier) {
        val dim = size.minDimension
        val arcThickness = dim * 0.07f
        val arcRadius = dim / 2f - arcThickness - dim * 0.06f
        val center = Offset(size.width / 2f, size.height * 0.52f)

        val startAngle = 140f
        val sweepTotal = 260f
        // speedFraction is computed at the top of the composable (for speedColor),
        // so the band-drawing branch reuses it.
        val speedSweep = sweepTotal * speedFraction

        // Background arc
        drawArc(
            color = trackColor,
            startAngle = startAngle, sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = arcThickness, cap = StrokeCap.Round)
        )

        // Thin color band behind the arc: safe (accent/blue) > orange > red.
        if (showColorBand) {
            val bandThickness = arcThickness * 0.35f
            val bandRadius = arcRadius + arcThickness * 0.55f + bandThickness * 0.5f
            val bandAlpha = 0.65f
            // orangeFrac / redFrac are computed at the top of the composable (for
            // speedColor), reuse so the band and number can never drift apart.
            val orangeStart = startAngle + sweepTotal * orangeFrac
            val orangeSweep = sweepTotal * (redFrac - orangeFrac)
            val redStart = startAngle + sweepTotal * redFrac
            val redSweep = sweepTotal * (1f - redFrac)
            val bandTopLeft = Offset(center.x - bandRadius, center.y - bandRadius)
            val bandSize = Size(bandRadius * 2, bandRadius * 2)
            // Safe zone: entire arc from 0 up to the orange handle.
            drawArc(color = safeBandColor.copy(alpha = bandAlpha),
                startAngle = startAngle, sweepAngle = sweepTotal * orangeFrac,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = bandWarnColor.copy(alpha = bandAlpha),
                startAngle = orangeStart, sweepAngle = orangeSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = bandDangerColor.copy(alpha = bandAlpha),
                startAngle = redStart, sweepAngle = redSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
        }

        // Speed arc
        if (speedSweep > 0.5f) {
            drawArc(
                color = speedColor,
                startAngle = startAngle, sweepAngle = speedSweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = arcThickness, cap = StrokeCap.Round)
            )
        }

        // Tick marks on outside of arc
        val tickOuter = arcRadius + arcThickness * 0.7f
        val tickInner = arcRadius + arcThickness * 0.1f
        for (i in 0..24) {
            val angle = startAngle + (sweepTotal * i / 24f)
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = i % 8 == 0
            val isMinor = i % 4 == 0
            if (!isMajor && !isMinor) continue
            drawLine(
                color = if (isMajor) dimColor else dimColor.copy(alpha = 0.35f),
                start = Offset(center.x + tickInner * cos(rad).toFloat(), center.y + tickInner * sin(rad).toFloat()),
                end = Offset(center.x + tickOuter * cos(rad).toFloat(), center.y + tickOuter * sin(rad).toFloat()),
                strokeWidth = if (isMajor) 2.5f else 1.2f
            )
        }

        // Scale labels outside ticks, pushed outward so digits aren't hugging the dial.
        val labelRadius = arcRadius + arcThickness + size.minDimension * 0.08f
        for ((idx, label) in scaleLabels.withIndex()) {
            val angle = startAngle + (sweepTotal * idx / (scaleLabels.size - 1).toFloat())
            val rad = Math.toRadians(angle.toDouble())
            val measured = textMeasurer.measure(
                "$label",
                style = TextStyle(fontSize = (size.minDimension * 0.05f).sp, color = dimColor)
            )
            drawText(
                measured,
                topLeft = Offset(
                    center.x + labelRadius * cos(rad).toFloat() - measured.size.width / 2f,
                    center.y + labelRadius * sin(rad).toFloat() - measured.size.height / 2f
                )
            )
        }

        // Speed number, dead center of the arc circle.
        // Dynamically clamp text width so digits keep a visible horizontal margin from the arc.
        val speedText = "%.0f".format(displaySpeed)
        val baseFactor = if (speedText.length >= 3) 0.17f else 0.2f
        val innerRadius = arcRadius - arcThickness * 0.5f
        val maxTextHalfWidth = innerRadius * 0.72f  // leaves ~28% clearance each side
        var speedFontFactor = baseFactor
        var speedMeasured = textMeasurer.measure(
            speedText,
            style = TextStyle(
                fontSize = (size.minDimension * speedFontFactor).sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
        )
        if (speedMeasured.size.width / 2f > maxTextHalfWidth) {
            val scale = maxTextHalfWidth / (speedMeasured.size.width / 2f)
            speedFontFactor *= scale
            speedMeasured = textMeasurer.measure(
                speedText,
                style = TextStyle(
                    fontSize = (size.minDimension * speedFontFactor).sp,
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )
            )
        }
        // Speed text is centered on its own measured height, so a
        // width-clamped number still looks centered.
        drawText(
            speedMeasured,
            topLeft = Offset(
                center.x - speedMeasured.size.width / 2f,
                center.y - speedMeasured.size.height / 2f - size.minDimension * 0.03f
            )
        )

        // Speed unit (mph / km/h) is drawn OUTSIDE this canvas by the
        // dashboard layout, aligned to the same bottom as the Map / Camera
        // glyphs in the gauge container. Drawing it here used to push it
        // onto the cards row when the experimental banner squeezed the
        // gauge vertically (Pixel 6 Pro / Begode Race).

        // External GPS marker: a dot on the arc at the external speed.
        // Drawn last so it sits on top of the speed arc.
        if (externalSpeed != null) {
            val extFraction = (externalSpeed / maxSpeed).coerceIn(0f, 1f)
            val extAngle = startAngle + sweepTotal * extFraction
            val extRad = Math.toRadians(extAngle.toDouble())
            val dotRadius = arcThickness * 0.45f
            // Sit the dot on the centerline of the speed arc so it visually
            // tracks the speed sweep. Drawing last means it sits on top of
            // the arc colour band at the same angle.
            val dotDistance = arcRadius
            val dotCenter = Offset(
                center.x + dotDistance * cos(extRad).toFloat(),
                center.y + dotDistance * sin(extRad).toFloat()
            )
            // Halo for contrast against any arc colour.
            drawCircle(
                color = androidx.compose.ui.graphics.Color.Black,
                radius = dotRadius * 1.45f,
                center = dotCenter
            )
            drawCircle(
                color = externalAccentColor,
                radius = dotRadius,
                center = dotCenter
            )
        }
    }
}

// Car-dashboard style status indicator: dim (off) / lit (on).
@Composable
fun DashIndicatorLetter(
    letter: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    Text(
        text = letter,
        // Fixed width, matches DashIndicatorIcon and the map glyph, so the
        // left-corner cluster lines up on both edges, like the right corner.
        modifier = modifier.width(32.dp),
        textAlign = TextAlign.Center,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = if (active) activeColor else dim
    )
}

@Composable
fun DashIndicatorIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (active) activeColor else dim,
        modifier = modifier.size(32.dp)
    )
}

// --- Bottom info box: ODO + firmware ---

@Composable
fun WheelInfoBox(
    odoKm: Float,
    distanceUnit: String,
    firmwareVersion: String?,
    versionName: String,
    diagnosticsActive: Boolean,
    onVersionClick: () -> Unit,
    versionModifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Left: ODO
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val odoValue = com.eried.eucplanet.util.Units.distance(odoKm, distanceUnit)
            val odoUnit = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
            Text(stringResource(Res.string.stat_odo), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            // The gap is layout, not the string: stat_odo is "ODO " in the
            // resources, but unquoted trailing spaces are trimmed at build
            // time on both phones, which left "ODO0 km".
            Spacer(Modifier.width(4.dp))
            Text("%.0f %s".format(odoValue, odoUnit), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
        }

        // Center: firmware version (the wheel name is shown in the top bar)
        val infoText = firmwareVersion?.let { "v$it" }.orEmpty()
        if (infoText.isNotBlank()) {
            Text(
                infoText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Right: app version (eased red glow when Service Mode is recording)
        val versionColor = if (diagnosticsActive) {
            val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "diagGlow")
            val alpha by infinite.animateFloat(
                initialValue = 1f,
                targetValue = 0.45f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 1000,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "diagGlowAlpha"
            )
            Color.Red.copy(alpha = alpha)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        Text(
            text = versionName,
            fontSize = 10.sp,
            color = versionColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(versionModifier)
                .clickable(onClick = onVersionClick)
        )
    }
}

// --- Helpers ---

@Composable
fun FlicIndicator(
    hasFlic: Boolean,
    flashAt: Long,
    onClick: () -> Unit
) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        flashAlpha.snapTo(1f)
        flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 1200))
    }
    // Always visible. Filled circle when paired, hollow ring when not. Flash turns it green on action.
    val baseAlpha = if (hasFlic) 1f else 0.55f
    val alpha = (baseAlpha + flashAlpha.value * (1f - baseAlpha)).coerceAtMost(1f)
    val tint = if (flashAlpha.value > 0f) MaterialTheme.appColors.statusGood
               else MaterialTheme.colorScheme.onSurface
    val icon = if (hasFlic || flashAlpha.value > 0f) Icons.Default.RadioButtonChecked
               else Icons.Default.RadioButtonUnchecked
    IconButton(
        onClick = onClick,
        modifier = Modifier.alpha(alpha)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(Res.string.flic_button),
            tint = tint
        )
    }
}

@Composable
fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.appColors.connectionActive
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING -> MaterialTheme.appColors.statusWarn
        ConnectionState.DISCONNECTED -> MaterialTheme.appColors.statusDanger
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * PopupPositionProvider that surfaces a popup just above the anchor
 * with a slight overlap (top 25% of anchor sits under the popup) and a
 * horizontal shift that depends on the anchor's column position:
 *
 *   - Left column (anchor in left third of window):
 *       popup.left aligns to (anchor.left + 25% of anchor.width),
 *       so the leftmost 25% of the anchor stays visible.
 *   - Center column:
 *       popup centers horizontally on the anchor.
 *   - Right column (anchor in right third):
 *       popup.right aligns to (anchor.right - 25% of anchor.width),
 *       so the rightmost 25% of the anchor stays visible.
 *
 * The 25% slivers let the rider see where the popup came from while
 * keeping the popup body close to the finger position.
 */
private class AboveAnchorPositionProvider : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val overlapFraction = 0.25f
        // Vertical overlap: pull the popup's bottom edge 25% of the
        // anchor's height INTO the anchor, so the popup overlaps the
        // top of the button.
        val verticalOverlap = (anchorBounds.height * overlapFraction).toInt()
        val y = (anchorBounds.top - popupContentSize.height + verticalOverlap)
            .coerceAtLeast(8)

        // Horizontal: figure out which third of the window the anchor
        // sits in, then anchor the popup's left/center/right
        // accordingly so the rider gets a 25% "sliver" visible on the
        // far side from the screen center.
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        val leftThird = windowSize.width / 3
        val rightThird = windowSize.width * 2 / 3
        val edgeSliver = (anchorBounds.width * overlapFraction).toInt()
        val rawX = when {
            anchorCenterX < leftThird ->
                anchorBounds.left + edgeSliver
            anchorCenterX > rightThird ->
                anchorBounds.right - edgeSliver - popupContentSize.width
            else ->
                anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        }
        val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)
        val clampedX = rawX.coerceIn(8, maxX)
        return androidx.compose.ui.unit.IntOffset(clampedX, y)
    }
}

/**
 * Floating popover that renders an action group's sub-actions as compact
 * tile-shaped buttons, surfacing ABOVE the tapped tile so the popup never
 * lands under the rider's finger. Layout adapts to the number of actions:
 *   - 1 action  → single tile
 *   - 2 actions → one row of 2
 *   - 3 actions → one row of 3
 *   - 4 actions → 2x2 grid
 *
 * Tap any tile → [onPick] fires (which dispatches the sub-action) and
 * the parent closes the popover. Tapping outside dismisses without
 * firing anything.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun ActionGroupPopover(
    actions: List<String>,
    anchorSizePx: androidx.compose.ui.unit.IntSize,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Convert the anchor tile's measured size to dp so each sub-action
    // button can render at the EXACT same width and height. Fallback to
    // sensible defaults if the anchor hasn't measured yet (first frame).
    val buttonWidthDp = with(density) {
        if (anchorSizePx.width > 0) anchorSizePx.width.toDp() else 104.dp
    }
    val buttonHeightDp = with(density) {
        if (anchorSizePx.height > 0) anchorSizePx.height.toDp() else 104.dp
    }

    // Animation: scale-in + fade-in from the bottom-center so the popup
    // visibly pops out of the button the rider tapped. Trigger by
    // flipping `visible` to true on first composition.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = AboveAnchorPositionProvider(),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.scaleIn(
                initialScale = 0.85f,
                animationSpec = androidx.compose.animation.core.tween(180),
                // Pivot the scale-in from the bottom of the popup so it
                // visibly "grows out" of the action button below it.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.0f)
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(150)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(100)
            )
        ) {
            // ElevatedCard draws a native Android drop shadow at the
            // requested elevation (handled by the platform Renderer,
            // not a custom blur), which is what the rider asked for, // crisp and consistent with other M3 surfaces.
            androidx.compose.material3.ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val takeFour = actions.take(4)
                    val rows: List<List<String>> = when (takeFour.size) {
                        4 -> listOf(takeFour.subList(0, 2), takeFour.subList(2, 4))
                        else -> listOf(takeFour)
                    }
                    rows.forEachIndexed { rowIdx, rowActions ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowActions.forEach { subKey ->
                                val subSpec = com.eried.eucplanet.data.model.ActionCatalog.byKey(subKey)
                                val subLabel = subSpec?.let { stringResource(it.labelRes) } ?: subKey
                                // Each tile gets the anchor's exact
                                // pixel-perfect width and height so it
                                // looks identical to the dashboard
                                // action button row.
                                ActionButton(
                                    icon = subSpec?.icon ?: Icons.Default.Campaign,
                                    label = subLabel,
                                    onClick = { onPick(subKey) },
                                    modifier = Modifier
                                        .width(buttonWidthDp)
                                        .height(buttonHeightDp),
                                    heightDp = null
                                )
                            }
                        }
                        if (rowIdx < rows.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    heightDp: Int? = null,
    /** Drawn in the disabled ink but still tappable: a feature this phone lacks yet. */
    muted: Boolean = false,
) {
    val disabledAlpha = 0.35f
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = disabledAlpha)
        active -> activeColor.copy(alpha = 0.2f)
        else -> MaterialTheme.appColors.tileBackground
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
        muted -> MaterialTheme.appColors.textDisabled
        active -> activeColor
        else -> MaterialTheme.appColors.tileLabel
    }
    Box(
        modifier = modifier
            .let { if (heightDp != null) it.height(heightDp.dp) else it }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
            // Short buttons (tiny cover screens) have no room for a caption:
            // icon only, the label stays as the content description.
            if (heightDp == null || heightDp >= 64) {
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = content,
                    maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
            }
        }
    }
}

/**
 * Label + value cell without corner stats or sparkline, the live value in the
 * metric's accent. Tap opens the same destination as the full tile (Battery
 * monitor / metric history). Fills its row height and copies the ActionButton
 * corner radius so the metric grid mirrors the button grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MicroMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.appColors.tileBackground)
            .combinedClickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            label, fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.appColors.tileLabel,
            maxLines = 1, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            color = accent, maxLines = 1
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    heightDp: Int? = null
) {
    Box(modifier = modifier) {
        var menuOpen by remember { mutableStateOf(false) }
        ActionButton(
            icon = icon,
            label = label,
            active = active,
            activeColor = activeColor,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            heightDp = heightDp
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            menu { menuOpen = false }
        }
    }
}
