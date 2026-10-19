package com.eried.eucplanet.ui.navigator

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.NavigatorViewSettings
import com.eried.eucplanet.ui.metrics.MetricTextInputs
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.ui.metrics.MetricText
import com.eried.eucplanet.ui.settings.KNOWN_DASHBOARD_METRICS
import com.eried.eucplanet.ui.settings.metricChipLabel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import java.util.Date
import kotlin.math.absoluteValue

/** The rider's display units for the navigator tiles, from the Units effective* helpers. */
data class MetricUnits(
    val speed: String,
    val distance: String,
    val temp: String,
    val pressure: String,
)

/** Translucency of the navigator cards over the map, matching the map's other overlays. */
private const val HUD_ALPHA = 0.90f

/** The metric pill lets more of the map through than the cards do. */
private const val PILL_ALPHA = 0.70f

/** The map's button size: the FABs, the compass, the speed bubble and the pill's thickness. */
private val NAV_FAB_SIZE = 56.dp

/**
 * Everything the map draws while guidance runs: turn card, metric pill and
 * speed bubble. It collects the per-frame flows (wheel data, guidance state,
 * location) itself, so a BLE frame recomposes only this and not the map
 * screen around it.
 *
 * [panelHeightPx] and [mapBoxHeightPx] are device pixels; in landscape the
 * stops sidebar is [sidebarWidth] wide on the right when [sidebarRight].
 */
@Composable
fun BoxScope.NavigatorOverlay(
    viewModel: RouteBuilderViewModel,
    topPad: Dp,
    landscape: Boolean,
    sidebarRight: Boolean,
    sidebarWidth: Dp,
    panelHeightPx: Int,
    mapBoxHeightPx: Int,
    tileKeysCsv: String,
) {
    val navState by viewModel.navState.collectAsState()
    if (!navState.active) return
    val wheelData by viewModel.wheelData.collectAsState()
    val wheelLive by viewModel.wheelLive.collectAsState()
    val units by viewModel.unitsForMetrics.collectAsState()
    val tripMeterKm by viewModel.tripMeterKm.collectAsState()
    val phoneBattery by viewModel.phoneBatteryPercent.collectAsState()
    val imperial by viewModel.imperialUnits.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val density = LocalDensity.current

    val mapStartInset = if (landscape && !sidebarRight) sidebarWidth else 0.dp
    val mapEndInset = if (landscape && sidebarRight) sidebarWidth else 0.dp
    // Layers + My-location: two 56 dp buttons, 12 dp under each, with 16 dp
    // on both sides of the column.
    val fabStackH = (NAV_FAB_SIZE + 12.dp) * 2
    val fabColumnW = 16.dp + NAV_FAB_SIZE + 16.dp
    val panelDp = with(density) { panelHeightPx.toDp() }

    NavTurnCard(
        state = navState,
        imperial = imperial,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = topPad + 12.dp, start = mapStartInset + 12.dp)
    )

    val tileKeys = remember(tileKeysCsv) {
        NavigatorViewSettings.metricSlots(tileKeysCsv, KNOWN_DASHBOARD_METRICS.toSet())
    }
    val inputs = MetricTextInputs(
        live = wheelLive,
        wheel = wheelData,
        speedUnit = units.speed,
        distanceUnit = units.distance,
        tempUnit = units.temp,
        pressureUnit = units.pressure,
        currentAsWatts = false,
        tripMeterKm = tripMeterKm,
        phoneBatteryPct = phoneBattery,
        externalGpsBatteryPct = null,
        gpsAltitudeM = location?.takeIf { it.hasAltitude() }?.altitude,
        gpsSpeedKmh = location?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f },
        gpsBearingDeg = location?.takeIf { it.hasBearing() }?.bearing,
        gpsAccuracyM = location?.takeIf { it.hasAccuracy() }?.accuracy,
    )
    val valueOf: (String) -> String = { key -> MetricText.of(key, inputs) }
    // Portrait: one row just above the stops panel, the bubble at the left,
    // the pill in between, and My location at the right (it lives in the
    // screen's FAB column, 16 dp in and 12 dp above the panel).
    val portraitStart = 16.dp
    val rowBottom = panelDp + 12.dp
    if (landscape) {
        // Standing up under the compass, as wide as it. The cap keeps it off
        // the FAB stack when the FABs share that edge, and the segments
        // shrink on short screens.
        val pillTop = topPad + 12.dp + NAV_FAB_SIZE + 8.dp
        val pillBottomReserve = if (sidebarRight) 12.dp else fabStackH + 8.dp
        val pillMax = if (mapBoxHeightPx > 0)
            with(density) { mapBoxHeightPx.toDp() } - pillTop - pillBottomReserve
        else NAV_FAB_SIZE * NavigatorViewSettings.SLOTS
        NavMetricPill(
            keys = tileKeys,
            valueOf = valueOf,
            vertical = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = pillTop, end = mapEndInset + 16.dp)
                .width(NAV_FAB_SIZE)
                .height(
                    (NAV_FAB_SIZE * NavigatorViewSettings.SLOTS)
                        .coerceAtMost(pillMax)
                        .coerceAtLeast(NAV_FAB_SIZE * 2)
                )
        )
    } else {
        val pillInset = portraitStart + NAV_FAB_SIZE + 8.dp
        NavMetricPill(
            keys = tileKeys,
            valueOf = valueOf,
            vertical = false,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = pillInset, end = pillInset, bottom = rowBottom)
                .fillMaxWidth()
                .height(NAV_FAB_SIZE)
        )
    }

    // Wheel speed with a wheel connected, GPS speed without one.
    val bubbleKmh = if (wheelLive) wheelData.speed.absoluteValue
    else location?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f } ?: 0f
    val bubbleStart = when {
        !landscape -> portraitStart
        // The FABs share the bottom-left corner: sit beside them.
        sidebarRight -> fabColumnW
        else -> mapStartInset + 12.dp
    }
    NavSpeedBubble(
        kmh = bubbleKmh,
        speedUnit = units.speed,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = bubbleStart, bottom = if (landscape) 12.dp else rowBottom)
    )
}

/**
 * Top-left card while navigating on the map: the turn arrow, distance to the
 * turn, the instruction and street, and a line with the arrival time and the
 * distance left. Off route and arrival use the engine's own text in the same
 * card, so the map never needs the dashboard's pop-up.
 */
@Composable
fun NavTurnCard(state: NavState, imperial: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.appColors
    val arrowTint = when {
        state.offRoute -> colors.statusDanger
        state.arrived -> colors.statusGood
        else -> colors.primary
    }
    val angle by animateFloatAsState(state.arrowAngleDeg(), tween(350), label = "navArrow")
    // The engine keeps the last distance left and arrival on "arrived" and
    // "waiting" frames, so those frames show only the engine's own lines.
    val showProgress = !state.arrived && !state.waiting
    val clock = if (showProgress && state.etaEpochMs > 0L)
        DateFormat.getTimeFormat(context).format(Date(state.etaEpochMs)) else null
    val left = if (showProgress && !state.remainingM.isNaN())
        NavFormat.distance(context, state.remainingM, imperial) else null
    val street = when {
        state.offRoute || state.arrived -> ""
        state.straightLine -> stringResource(R.string.nav_view_straight_line)
        else -> state.nextStreet
    }
    Surface(
        modifier = modifier.widthIn(max = 220.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = HUD_ALPHA),
        contentColor = colors.textPrimary,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.arrived) Icons.Default.Flag else Icons.Default.Navigation,
                    contentDescription = null,
                    tint = arrowTint,
                    modifier = Modifier
                        .size(40.dp)
                        .rotate(if (state.arrived) 0f else angle)
                )
                if (state.distanceText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.distanceText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
            if (state.primaryText.isNotBlank()) {
                Text(
                    state.primaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.offRoute) colors.statusDanger else colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (street.isNotBlank()) {
                Text(
                    street,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val info = listOfNotNull(clock, left).joinToString(" · ")
            if (info.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    info,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Round speed readout, bottom-left on the map, in the rider's speed unit. */
@Composable
fun NavSpeedBubble(kmh: Float, speedUnit: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    val description = stringResource(R.string.nav_view_speed)
    Surface(
        modifier = modifier
            .size(NAV_FAB_SIZE)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = colors.surface.copy(alpha = HUD_ALPHA),
        contentColor = colors.textPrimary,
        shadowElevation = 6.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "%.0f".format(Units.speed(kmh, speedUnit)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
            )
            Text(
                MetricText.speedUnitLabel(speedUnit),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * The rider's 4 telemetry numbers as one see-through pill split in equal
 * segments: a row in portrait, a stack when [vertical]. Display only; the
 * numbers are picked in Settings > Navigation. The caller sizes it: the
 * thickness matches the map's buttons.
 */
@Composable
fun NavMetricPill(
    keys: List<String>,
    valueOf: (String) -> String,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = colors.surface.copy(alpha = PILL_ALPHA),
        contentColor = colors.textPrimary,
        border = BorderStroke(Dp.Hairline, colors.outline),
        shadowElevation = 6.dp,
    ) {
        val segments: @Composable (Modifier) -> Unit = { segMod ->
            keys.forEachIndexed { i, key ->
                if (i > 0) {
                    if (vertical) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        thickness = 1.dp,
                        color = colors.divider,
                    ) else VerticalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        thickness = 1.dp,
                        color = colors.divider,
                    )
                }
                NavPillSegment(value = valueOf(key), label = metricChipLabel(key, short = true), modifier = segMod)
            }
        }
        if (vertical) {
            // The rounded ends eat into the first and last segment, so the
            // stack keeps clear of them.
            Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                segments(Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                segments(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** Pill value size, and the one step down a long value takes ("1234.5 km"). */
private val PILL_VALUE_SP = 18.sp
private val PILL_VALUE_SMALL_SP = 14.sp
private val PILL_LABEL_SP = 10.sp

/**
 * One pill segment: the value large and bold over its small muted label. A
 * value too wide for the segment drops once to the smaller size, and only
 * ellipsizes if even that does not fit.
 */
@Composable
private fun NavPillSegment(value: String, label: String, modifier: Modifier) {
    val colors = MaterialTheme.appColors
    val valueStyle = MaterialTheme.typography.titleMedium.copy(
        color = colors.textPrimary,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        fontSize = PILL_VALUE_SP,
        lineHeight = 22.sp,
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        val maxW = constraints.maxWidth
        val fits = remember(value, maxW, valueStyle) {
            measurer.measure(value, valueStyle, maxLines = 1, softWrap = false).size.width <= maxW
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = if (fits) valueStyle
                else valueStyle.copy(fontSize = PILL_VALUE_SMALL_SP, lineHeight = 22.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = PILL_LABEL_SP,
                    lineHeight = 12.sp,
                ),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bottom sheet listing every dashboard metric, the [current] one checked.
 * Used by the Settings navigator tiles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavMetricPicker(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.sheetBackground,
        contentColor = colors.textPrimary,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.nav_view_pick_metric),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(KNOWN_DASHBOARD_METRICS) { key ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            metricChipLabel(key),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(24.dp)) {
                            if (key == current) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = colors.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
