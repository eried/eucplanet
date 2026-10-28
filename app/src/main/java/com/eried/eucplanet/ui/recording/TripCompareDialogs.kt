package com.eried.eucplanet.ui.recording

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.ui.settings.DropdownSelect
import com.eried.eucplanet.ui.settings.NumberUpDown
import com.eried.eucplanet.ui.settings.RestoreChip
import com.eried.eucplanet.ui.settings.SegmentedChoice
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The metrics two trips can be compared on, keyed like the Trip Details charts
 * they draw into. Raw units: the charts convert for display, alignment does not
 * need to. Smoothed and derived-envelope charts stay this trip only, as on the web.
 */
internal enum class CompareMetric(
    val key: String,
    @StringRes val label: Int,
    val raw: (TripDataPoint) -> Float,
) {
    SPEED("speed", R.string.metric_chip_speed, { it.speed }),
    BATTERY("battery", R.string.metric_chip_battery, { it.battery.toFloat() }),
    TEMP("temp", R.string.metric_chip_temperature, { it.temperature }),
    VOLTAGE("voltage", R.string.metric_chip_voltage, { it.voltage }),
    CURRENT("current", R.string.metric_chip_current, { it.current }),
    PWM("pwm", R.string.metric_chip_load, { it.pwm }),
    TORQUE("torque", R.string.metric_chip_torque, { it.torque }),
    PHASE_CURRENT("phaseCurrent", R.string.metric_chip_phase_current, { it.phaseCurrent }),
    POWER("power", R.string.metric_chip_battery_power,
        { if (it.current.isNaN()) Float.NaN else it.voltage * it.current }),
    ALTITUDE("altitude", R.string.metric_chip_gps_altitude, { it.altitude });

    /** A column that is empty or all zero counts as missing, as on the web. */
    fun presentIn(points: List<TripDataPoint>): Boolean =
        points.any { val v = raw(it); !v.isNaN() && v != 0f }

    fun values(points: List<TripDataPoint>): FloatArray =
        FloatArray(points.size) { raw(points[it]) }

    companion object {
        fun byKey(key: String): CompareMetric? = entries.firstOrNull { it.key == key }
        fun shared(a: List<TripDataPoint>, b: List<TripDataPoint>): List<CompareMetric> =
            entries.filter { it.presentIn(a) && it.presentIn(b) }
    }
}

/** The other trip, read and timed, ready to lay over this one. */
internal class CompareTrip(
    val trip: TripRecord,
    val points: List<TripDataPoint>,
    /** Elapsed ms from B's own first sample, one per point. */
    val ms: LongArray,
) {
    val durationMs: Long get() = ms.lastOrNull() ?: 0L
}

private const val VIEW_STACKED = "stacked"
private const val VIEW_OVERLAY = "overlay"

private val compareDateFmt = SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault())

internal fun compareTripLabel(t: TripRecord): String =
    t.customName?.takeIf { it.isNotBlank() } ?: compareDateFmt.format(Date(t.startTime))

/**
 * Step one: which trip to compare with. Every trip is listed, this one too
 * (marked), so a ride can be laid over an earlier stretch of itself.
 */
@Composable
internal fun TripComparePickDialog(
    trips: List<TripRecord>,
    currentTripId: Long,
    distanceUnit: String,
    onPick: (TripRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(trips, filter) {
        val q = filter.trim().lowercase()
        trips.sortedByDescending { it.startTime }.filter { t ->
            q.isEmpty() || compareTripLabel(t).lowercase().contains(q) ||
                compareDateFmt.format(Date(t.startTime)).contains(q) ||
                t.fileName.lowercase().contains(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_compare_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.trip_compare_filter)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = themedFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.trip_compare_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { t ->
                        PickRow(t, isCurrent = t.id == currentTripId, distanceUnit = distanceUnit) { onPick(t) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun PickRow(t: TripRecord, isCurrent: Boolean, distanceUnit: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                compareTripLabel(t),
                color = MaterialTheme.appColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                t.fileName.substringAfterLast('/').removeSuffix(".csv"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCurrent) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.appColors.primary),
            ) {
                Text(
                    stringResource(R.string.trip_compare_this_trip),
                    fontSize = 11.sp,
                    color = MaterialTheme.appColors.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        } else {
            val km = Units.distance(t.distanceKm, distanceUnit)
            val secs = ((t.endTime ?: t.startTime) - t.startTime) / 1000
            Text(
                "%.1f %s  %s".format(km, Units.distanceUnit(distanceUnit), Units.humanDuration(secs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
            )
        }
    }
}

/**
 * Step two: line the trips up, pick how they draw, and Apply.
 *
 * Nothing here touches the screen until Apply. Auto runs when B arrives and
 * again when the metric changes, until the rider sets the offset by hand.
 * Tapping outside does not close it (an offset dragged into place is work).
 */
@Composable
internal fun TripCompareAlignDialog(
    aPoints: List<TripDataPoint>,
    aMs: LongArray,
    b: CompareTrip?,
    /** Where B sat last time, or null to let Auto place it. */
    initialOffsetMs: Long?,
    initialStacked: Boolean,
    initialDeltas: Boolean,
    onChange: () -> Unit,
    /** Ends the comparison, offered when one is already on (eucviewer's Reset). */
    onStop: (() -> Unit)?,
    onApply: (offsetMs: Long, stacked: Boolean, deltas: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val shared = remember(aPoints, b) {
        if (b == null) emptyList() else CompareMetric.shared(aPoints, b.points)
    }
    var metric by remember(b) {
        mutableStateOf(shared.firstOrNull { it == CompareMetric.SPEED } ?: shared.firstOrNull())
    }
    var offsetMs by remember(b) { mutableLongStateOf(initialOffsetMs ?: 0L) }
    // Spent = the rider set the offset; Auto then never overrides it on its own.
    var autoSpent by remember(b) { mutableStateOf(initialOffsetMs != null) }
    var autoRun by remember(b) { mutableIntStateOf(0) }
    var stacked by remember { mutableStateOf(initialStacked) }
    var deltas by remember { mutableStateOf(initialDeltas) }

    LaunchedEffect(b, metric, autoRun) {
        val m = metric ?: return@LaunchedEffect
        if (b == null) return@LaunchedEffect
        if (autoSpent && autoRun == 0) return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            TripCompare.autoOffsetMs(aMs, m.values(aPoints), b.ms, m.values(b.points))
        }
        if (found != null) offsetMs = found
    }

    val aDur = aMs.lastOrNull() ?: 0L
    val overlap = b?.let { TripCompare.overlapMs(aDur, it.durationMs, offsetMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_compare)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompareDot()
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            b?.let { compareTripLabel(it.trip) } ?: stringResource(R.string.trip_compare_loading),
                            color = MaterialTheme.appColors.chartCompare,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        b?.let {
                            Text(
                                Units.humanDuration(it.durationMs / 1000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.appColors.textSecondary,
                            )
                        }
                    }
                    TextButton(onClick = onChange, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.trip_compare_change))
                    }
                }

                if (b == null) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.appColors.primary)
                    }
                    return@Column
                }
                if (shared.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.trip_compare_no_shared),
                        color = MaterialTheme.appColors.statusDanger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    return@Column
                }
                val m = metric ?: return@Column
                val notch = MaterialTheme.appColors.dialog

                Spacer(Modifier.height(8.dp))
                AlignStrip(
                    aMs = aMs,
                    aValues = remember(aPoints, m) { m.values(aPoints) },
                    bMs = b.ms,
                    bValues = remember(b, m) { m.values(b.points) },
                    offsetMs = offsetMs,
                    onOffset = { offsetMs = it; autoSpent = true },
                )
                if (overlap == null) {
                    Text(
                        stringResource(R.string.trip_compare_no_overlap),
                        color = MaterialTheme.appColors.statusDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Metric and offset side by side, each labelled in its border.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    DropdownSelect(
                        label = stringResource(R.string.trip_compare_metric),
                        selected = stringResource(m.label),
                        options = shared.map { it.key to stringResource(it.label) },
                        onSelect = { key -> CompareMetric.byKey(key)?.let { metric = it } },
                        notchFill = notch,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val lo = -(b.durationMs / 1000).toInt()
                    val hi = (aDur / 1000).toInt()
                    Column(Modifier.weight(1f)) {
                        NumberUpDown(
                            value = TripCompare.toSeconds(offsetMs).toInt().coerceIn(lo, hi),
                            onValueChange = { offsetMs = it * 1000L; autoSpent = true },
                            range = lo..hi,
                            label = stringResource(R.string.trip_compare_offset),
                            format = { TripCompare.formatOffset(it * 1000L) },
                            parse = { TripCompare.parseTypedSeconds(it) },
                            allowSign = true,
                            notchFill = notch,
                        )
                        // Auto is the offset's default: the app's restore chip,
                        // lit once the rider has moved it by hand.
                        RestoreChip(
                            text = stringResource(R.string.trip_compare_auto),
                            enabled = autoSpent,
                        ) { autoSpent = false; autoRun++ }
                    }
                }

                Spacer(Modifier.height(12.dp))
                SegmentedChoice(
                    label = stringResource(R.string.trip_compare_view),
                    options = listOf(
                        VIEW_STACKED to stringResource(R.string.trip_compare_stacked),
                        VIEW_OVERLAY to stringResource(R.string.trip_compare_overlay),
                    ),
                    current = if (stacked) VIEW_STACKED else VIEW_OVERLAY,
                    onChange = { stacked = it == VIEW_STACKED },
                    notchFill = notch,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { deltas = !deltas },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.trip_compare_deltas),
                        color = MaterialTheme.appColors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = deltas, onCheckedChange = { deltas = it }, colors = themedSwitchColors())
                }
            }
        },
        confirmButton = {
            // Stop sits alone on the far left, apart from Cancel/Apply, the way
            // the rename dialog keeps the action that undoes something.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (onStop != null) {
                    TextButton(onClick = onStop, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.trip_compare_stop))
                    }
                } else Spacer(Modifier.width(1.dp))
                Row {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = { onApply(offsetMs, stacked, deltas) },
                        enabled = b != null && shared.isNotEmpty() && overlap != null,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(R.string.action_apply)) }
                }
            }
        },
    )
}

@Composable
internal fun CompareDot(modifier: Modifier = Modifier) {
    val c = MaterialTheme.appColors.chartCompare
    Canvas(modifier.size(8.dp)) { drawCircle(c) }
}

/**
 * This trip on top, the other below, each on its own scale, along A's clock.
 * Dragging sideways slides B. The span is frozen while a finger is down so the
 * picture does not rescale under it.
 */
@Composable
private fun AlignStrip(
    aMs: LongArray,
    aValues: FloatArray,
    bMs: LongArray,
    bValues: FloatArray,
    offsetMs: Long,
    onOffset: (Long) -> Unit,
) {
    val aColor = MaterialTheme.appColors.metricBattery
    val bColor = MaterialTheme.appColors.chartCompare
    val bg = MaterialTheme.appColors.surfaceVariant
    val line = MaterialTheme.appColors.outline
    val aDur = aMs.lastOrNull() ?: 0L
    val bDur = bMs.lastOrNull() ?: 0L
    val liveOffset by rememberUpdatedState(offsetMs)
    val liveOnOffset by rememberUpdatedState(onOffset)
    var frozen by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val t0 = frozen?.first ?: minOf(0L, offsetMs)
    val t1 = frozen?.second ?: maxOf(aDur, offsetMs + bDur)
    val span = (t1 - t0).coerceAtLeast(1L)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            // A dragged trip slides past the edges; keep it inside the box.
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(aDur, bDur) {
                var startOffset = 0L
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        startOffset = liveOffset
                        dragged = 0f
                        frozen = minOf(0L, liveOffset) to maxOf(aDur, liveOffset + bDur)
                    },
                    onDragEnd = { frozen = null },
                    onDragCancel = { frozen = null },
                ) { change, dx ->
                    change.consume()
                    dragged += dx
                    val f = frozen ?: return@detectHorizontalDragGestures
                    val ms = (dragged / size.width * (f.second - f.first)).toLong()
                    // Round to whole seconds, the stepper's unit.
                    val next = ((startOffset + ms) / 1000L) * 1000L
                    liveOnOffset(next.coerceIn(-bDur, aDur))
                }
            }
    ) {
        drawRoundRect(bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        val pad = 4.dp.toPx()
        val half = size.height / 2f
        drawLine(line, Offset(0f, half), Offset(size.width, half), strokeWidth = 1f)
        fun series(ms: LongArray, vs: FloatArray, shift: Long, top: Float, bottom: Float, c: Color) {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (v in vs) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (!lo.isFinite()) return
            val r = (hi - lo).takeIf { it > 1e-6f } ?: 1f
            val stride = (vs.size / 600).coerceAtLeast(1)
            val p = Path()
            var pen = false
            var i = 0
            while (i < vs.size) {
                val v = vs[i]
                if (v.isNaN()) pen = false else {
                    val x = ((ms[i] + shift - t0).toFloat() / span) * size.width
                    val y = bottom - (v - lo) / r * (bottom - top)
                    if (pen) p.lineTo(x, y) else { p.moveTo(x, y); pen = true }
                }
                i += stride
            }
            drawPath(p, c, style = Stroke(width = 1.5f))
        }
        series(aMs, aValues, 0L, pad, half - pad, aColor)
        series(bMs, bValues, offsetMs, half + pad, size.height - pad, bColor)
    }
}

/**
 * Says what this trip is being compared with, under the trip's header: the
 * other trip, where it sits, and how much of the two overlaps. Tapping it
 * reopens the settings; the Compare icon in the top bar turns it off.
 */
@Composable
internal fun TripCompareStrip(
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
) {
    val editLabel = stringResource(R.string.trip_compare_edit)
    androidx.compose.material3.Card(
        onClick = onEdit,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.appColors.surfaceVariant,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().semantics { onClick(label = editLabel, action = null) },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompareDot()
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MaterialTheme.appColors.chartCompare,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.appColors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Says the strip opens something: the settings glyph the app
            // already uses for "adjust this".
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.appColors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
