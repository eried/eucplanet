package com.eried.eucplanet.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val tripDateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
private val replaySpeeds = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f)

private fun tripLabel(t: TripRecord, distanceUnit: String): String {
    val dist = com.eried.eucplanet.util.Units.distance(t.distanceKm.toFloat(), distanceUnit)
    return "${tripDateFmt.format(Date(t.startTime))}  ·  ${"%.1f".format(dist)} $distanceUnit"
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}×" else "$s×"

/**
 * A timeline handle: a 5-sided "home plate" — a square body with a triangular
 * point. The point sits on the track at [x]; [down] true points it downward.
 */
private fun handlePentagon(x: Float, trackY: Float, down: Boolean): Path {
    val hw = 16f          // half width — visual only; the drag hitbox is the
                          // whole top/bottom half of the track, not this shape
    val point = 18f       // height of the pointed part
    val total = 42f       // full height (point + square body)
    val s = if (down) -1f else 1f
    return Path().apply {
        moveTo(x, trackY)
        lineTo(x + hw, trackY + s * point)
        lineTo(x + hw, trackY + s * total)
        lineTo(x - hw, trackY + s * total)
        lineTo(x - hw, trackY + s * point)
        close()
    }
}

/** Photoshop-style transparency checkerboard, drawn behind a replay session. */
fun DrawScope.replayCheckerboard() {
    val tile = 26.dp.toPx().coerceAtLeast(1f)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + col) % 2 == 0) Color(0xFF8A8A94) else Color(0xFFC2C2CC),
                topLeft = Offset(x, y),
                size = Size(tile, tile)
            )
            x += tile
            col++
        }
        y += tile
        row++
    }
}

/**
 * The always-on Replay panel. Closing it (the X) returns the studio to Live
 * mode; the opacity toggle fades the panel so the overlays behind it stay
 * visible while scrubbing.
 */
@Composable
fun StudioReplayDialog(
    trips: List<TripRecord>,
    selectedTrip: TripRecord?,
    trip: ReplayTrip?,
    distanceUnit: String,
    positionMs: Long,
    rangeStartMs: Long,
    rangeEndMs: Long,
    speed: Float,
    playing: Boolean,
    dimmed: Boolean,
    onPickTrip: (TripRecord) -> Unit,
    onScrub: (Long) -> Unit,
    onRange: (Long, Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onToggleDim: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        // widthIn before fillMaxWidth so the cap actually applies — keeps the
        // panel from stretching edge-to-edge in landscape.
        modifier = modifier.widthIn(max = 500.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF21A1A20),
        contentColor = Color.White,
        shadowElevation = 12.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            // 0 = transport, 1 = trip list, 2 = speed list — inline pickers so
            // they rotate with the panel instead of escaping as popups.
            var picker by remember { mutableStateOf(0) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = StudioControlAccent)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (picker) {
                        1 -> stringResource(R.string.studio_replay_choose_trip)
                        2 -> stringResource(R.string.studio_replay_speed_title)
                        else -> stringResource(R.string.studio_replay_title)
                    },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                if (picker == 0) {
                    IconButton(onClick = onToggleDim) {
                        Icon(
                            Icons.Default.Opacity,
                            contentDescription = stringResource(R.string.studio_replay_cd_fade),
                            tint = if (dimmed) StudioControlAccent else Color.White
                        )
                    }
                }
                IconButton(onClick = { if (picker != 0) picker = 0 else onClose() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = if (picker != 0) stringResource(R.string.studio_replay_cd_back) else stringResource(R.string.studio_replay_cd_close)
                    )
                }
            }

          when (picker) {
            1 -> Column(
                Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
            ) {
                if (trips.isEmpty()) {
                    Text(
                        stringResource(R.string.studio_replay_no_trips),
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(12.dp)
                    )
                }
                trips.forEach { t ->
                    Text(
                        tripLabel(t, distanceUnit),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickTrip(t); picker = 0 }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    )
                }
            }
            2 -> Column {
                replaySpeeds.forEach { s ->
                    Text(
                        speedLabel(s),
                        color = if (s == speed) StudioControlAccent else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSpeed(s); picker = 0 }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    )
                }
            }
            else -> {
            // A trip is replayable once it parsed to a non-zero timeline.
            val active = trip != null && trip.durationMs > 0L
            val dur = trip?.durationMs ?: 0L
            // Trip picker + speed combo, side by side.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { picker = 1 },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        selectedTrip?.let { tripLabel(it, distanceUnit) } ?: stringResource(R.string.studio_replay_choose_trip),
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                OutlinedButton(
                    onClick = { picker = 2 },
                    enabled = active
                ) {
                    Text(speedLabel(speed))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.studio_replay_cd_speed))
                }
            }

            // Transport stays on screen always — disabled until a trip loads.
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPause, enabled = active) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) stringResource(R.string.studio_replay_cd_pause) else stringResource(R.string.studio_replay_cd_play)
                    )
                }
                Spacer(Modifier.width(10.dp))
                ReplayTimeline(
                    durationMs = dur,
                    positionMs = positionMs,
                    rangeStartMs = rangeStartMs,
                    rangeEndMs = rangeEndMs,
                    enabled = active,
                    onScrub = onScrub,
                    onRange = onRange,
                    modifier = Modifier.weight(1f)
                )
            }
            // current ( range-start - range-end ) total — the range only shows
            // once a handle is moved off the very start / end of the trip.
            val trimmed = active && (rangeStartMs > 0L || rangeEndMs < dur)
            val clockColor = Color.White.copy(alpha = if (active) 0.7f else 0.4f)
            // current playback (centred under the play button)  —  trimmed
            // range (centred under the timeline)  —  total (right).
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        formatReplayClock(positionMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = clockColor
                    )
                }
                Spacer(Modifier.width(10.dp))
                Spacer(Modifier.weight(1f))
                if (trimmed) {
                    Text(
                        "( ${formatReplayClock(rangeStartMs)}  -  " +
                            "${formatReplayClock(rangeEndMs)} )",
                        style = MaterialTheme.typography.bodySmall,
                        color = clockColor
                    )
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    formatReplayClock(dur),
                    style = MaterialTheme.typography.bodySmall,
                    color = clockColor
                )
            }
            }
          }
        }
    }
}

/**
 * Two-row timeline. The range trim handles are down-chevrons in the top row;
 * the playhead is a larger up-chevron in the (taller) bottom row. A drag in the
 * lower half always grabs the playhead, so it never fights a trim handle.
 */
@Composable
private fun ReplayTimeline(
    durationMs: Long,
    positionMs: Long,
    rangeStartMs: Long,
    rangeEndMs: Long,
    enabled: Boolean,
    onScrub: (Long) -> Unit,
    onRange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val dur = durationMs.coerceAtLeast(1L)
    BoxWithConstraints(modifier.height(82.dp)) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        fun xOf(ms: Long): Float = ((ms.toFloat() / dur) * wPx).coerceIn(0f, wPx)
        fun msOf(x: Float): Long = ((x / wPx) * dur).toLong().coerceIn(0L, dur)
        var dragHandle by remember { mutableStateOf(-1) }
        // The drag pointerInput is keyed only on durationMs (stable mid-drag).
        // These rememberUpdatedState values keep apply()'s coercion fresh
        // *without* re-keying — re-keying on positionMs / range was restarting
        // the gesture every update, so a handle never stayed grabbed.
        val curStart by rememberUpdatedState(rangeStartMs)
        val curEnd by rememberUpdatedState(rangeEndMs)
        val curPos by rememberUpdatedState(positionMs)

        fun apply(handle: Int, x: Float) {
            when (handle) {
                0 -> {
                    val s = msOf(x).coerceIn(0L, curEnd - 1L)
                    onRange(s, curEnd)
                    if (curPos < s) onScrub(s)
                }
                1 -> {
                    val e = msOf(x).coerceIn(curStart + 1L, dur)
                    onRange(curStart, e)
                    if (curPos > e) onScrub(e)
                }
                else -> onScrub(msOf(x).coerceIn(curStart, curEnd))
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.3f)
                .then(if (!enabled) Modifier else Modifier
                .pointerInput(durationMs) {
                    val trackY = size.height * 0.42f
                    detectTapGestures { o ->
                        if (o.y < trackY) {
                            apply(
                                if (abs(o.x - xOf(curStart)) <=
                                    abs(o.x - xOf(curEnd))) 0 else 1,
                                o.x
                            )
                        } else {
                            onScrub(msOf(o.x).coerceIn(curStart, curEnd))
                        }
                    }
                }
                .pointerInput(durationMs) {
                    val trackY = size.height * 0.42f
                    detectDragGestures(
                        onDragStart = { o ->
                            // Lower half is always the playhead; only the top
                            // row grabs the nearer trim handle.
                            dragHandle = if (o.y >= trackY) {
                                2
                            } else if (abs(o.x - xOf(curStart)) <=
                                abs(o.x - xOf(curEnd))
                            ) 0 else 1
                            apply(dragHandle, o.x)
                        },
                        onDragEnd = { dragHandle = -1 },
                        onDragCancel = { dragHandle = -1 }
                    ) { change, _ ->
                        change.consume()
                        apply(dragHandle, change.position.x)
                    }
                }
                )
        ) {
            val accent = Color(0xFF4FC3F7)
            val trackY = size.height * 0.42f
            val sx = xOf(rangeStartMs)
            val ex = xOf(rangeEndMs)
            val px = xOf(positionMs)
            // Track + trimmed span.
            drawLine(
                Color(0x44FFFFFF), Offset(0f, trackY), Offset(wPx, trackY),
                strokeWidth = 5f, cap = StrokeCap.Round
            )
            drawLine(
                accent.copy(alpha = 0.55f), Offset(sx, trackY), Offset(ex, trackY),
                strokeWidth = 8f, cap = StrokeCap.Round
            )
            // Range trim handles — down-pointing pentagons resting on the track.
            listOf(sx, ex).forEach { hx ->
                drawPath(handlePentagon(hx, trackY, down = true), Color.White)
            }
            // Playhead — an up-pointing pentagon, same size, below the track.
            drawLine(
                accent, Offset(px, trackY - 7f), Offset(px, trackY + 6f),
                strokeWidth = 3f, cap = StrokeCap.Round
            )
            drawPath(handlePentagon(px, trackY, down = false), accent)
        }
    }
}
