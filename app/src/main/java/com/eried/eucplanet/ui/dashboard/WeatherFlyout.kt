package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.weather.RidabilityScore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One scored forecast hour, ready for the flyout graph. */
data class ScoredHour(val timeMs: Long, val b: RidabilityScore.Breakdown)

/**
 * The thin forecast strip over the dashboard: the ridability score 0-10
 * across the chosen window as a gradient-filled curve, rain and snow bands
 * behind it, transition faces the rider can tap for a one-line read in EUC
 * lingo, a glyph strip naming which factor bites when, and sparse time
 * ticks. Built as a quick "is it good to go ride" glance.
 */
@Composable
fun WeatherFlyout(
    hours: List<ScoredHour>,
    windowLabel: String,
    refreshing: Boolean,
    error: String?,
    updatedAgoMin: Int?,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.appColors.sheetBackground),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    windowLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.appColors.textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        refreshing -> stringResource(R.string.weather_fetching)
                        updatedAgoMin != null -> stringResource(R.string.weather_updated_ago, updatedAgoMin)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.appColors.primary,
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.weather_refresh),
                            tint = MaterialTheme.appColors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.appColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when {
                hours.isEmpty() && error != null -> Text(
                    stringResource(R.string.weather_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.statusWarn,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                hours.isEmpty() -> Text(
                    stringResource(R.string.weather_fetching),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                else -> ScoreGraph(hours)
            }
        }
    }
}

/** A face pinned to the curve: where, which emoji, and the lingo line a tap
 *  reveals. Faces sit at the start plus the moments the ride character
 *  changes - a band crossing or a hazard beginning. */
private data class FaceSpot(val index: Int, val emoji: String, val infoRes: Int)

/** The tap-line and face for an hour, by what dominates it. Priority mirrors
 *  danger: snow, rain, wind, cold, hot, night, then just the score band. */
private fun faceFor(b: RidabilityScore.Breakdown): Pair<String, Int> = when {
    b.snow -> "🥶" to R.string.weather_face_snow          // cold face
    b.rain -> "😬" to R.string.weather_face_rain          // grimace
    b.wind && b.score < 7f -> "😖" to R.string.weather_face_wind
    b.cold -> "🥶" to R.string.weather_face_cold
    b.hot -> "🥵" to R.string.weather_face_hot            // hot face
    b.night && b.score < 9f -> "😴" to R.string.weather_face_night
    b.score >= 7f -> "😄" to R.string.weather_face_clear  // happy
    b.score >= 4f -> "😐" to R.string.weather_face_meh    // neutral
    else -> "🙁" to R.string.weather_face_meh             // frown
}

private fun band(score: Float): Int = when {
    score >= 7f -> 2
    score >= 4f -> 1
    else -> 0
}

@Composable
private fun ScoreGraph(hours: List<ScoredHour>) {
    val good = MaterialTheme.appColors.statusGood
    val warn = MaterialTheme.appColors.statusWarn
    val danger = MaterialTheme.appColors.statusDanger
    val gridColor = MaterialTheme.appColors.divider.copy(alpha = 0.6f)
    val rainBand = MaterialTheme.appColors.chartEnvelope.copy(alpha = 0.20f)

    fun colorFor(score: Float): Color = when {
        score >= 7f -> good
        score >= 4f -> warn
        else -> danger
    }

    // Start, end, and the moments the ride character changes: a score-band
    // crossing, or rain/snow starting. Capped so a volatile week stays
    // readable; first and last always survive.
    val faces = remember(hours) {
        val idx = LinkedHashSet<Int>()
        idx.add(0)
        for (i in 1 until hours.size) {
            val a = hours[i - 1].b
            val c = hours[i].b
            if (band(c.score) != band(a.score) ||
                (c.rain && !a.rain) || (c.snow && !a.snow)
            ) idx.add(i)
        }
        idx.add(hours.size - 1)
        val list = idx.toMutableList()
        while (list.size > 5) {
            // Drop the least dramatic interior transition.
            val interior = list.subList(1, list.size - 1)
            val drop = interior.minByOrNull { i ->
                kotlin.math.abs(hours[i].b.score - hours[(i - 1).coerceAtLeast(0)].b.score)
            } ?: break
            list.remove(drop)
        }
        list.map { i -> FaceSpot(i, faceFor(hours[i].b).first, faceFor(hours[i].b).second) }
    }
    var tappedInfo by remember(hours) { mutableStateOf<Int?>(null) }

    Column {
        val density = LocalDensity.current
        var graphW by remember { mutableStateOf(0) }
        val graphH = 64.dp

        Box(
            Modifier
                .fillMaxWidth()
                .height(graphH)
                .onSizeChanged { graphW = it.width }
        ) {
            Canvas(Modifier.fillMaxWidth().height(graphH)) {
                val w = size.width
                val h = size.height
                val n = hours.size
                fun x(i: Int) = if (n <= 1) 0f else i * w / (n - 1)
                fun y(score: Float) = h - (score / 10f) * (h - 6f) - 3f

                // Hazard bands first, behind everything: blue for the rainy
                // stretch, white for snow. White is deliberate - snow-colored
                // by nature, and it reads on both themes because it sits over
                // the tinted score fill drawn next.
                var i = 0
                while (i < n) {
                    val b = hours[i].b
                    if (b.rain || b.snow) {
                        var j = i
                        while (j + 1 < n && ((b.rain && hours[j + 1].b.rain) || (b.snow && hours[j + 1].b.snow))) j++
                        drawRect(
                            color = if (b.snow) Color.White.copy(alpha = 0.45f) else rainBand,
                            topLeft = Offset(x(i), 0f),
                            size = Size(x(j) - x(i) + (w / n).coerceAtLeast(2f), h),
                        )
                        i = j + 1
                    } else i++
                }

                // Gradient fill under the curve: the score's own colour at
                // every stop, so good stretches glow green and the bad hour
                // shades red, blending smoothly between.
                val stride = (n / 32).coerceAtLeast(1)
                val stops = ArrayList<Pair<Float, Color>>()
                var k = 0
                while (k < n) {
                    stops.add((if (n <= 1) 0f else k / (n - 1f)) to colorFor(hours[k].b.score).copy(alpha = 0.30f))
                    k += stride
                }
                if (stops.last().first < 1f) stops.add(1f to colorFor(hours[n - 1].b.score).copy(alpha = 0.30f))
                val area = Path().apply {
                    moveTo(x(0), y(hours[0].b.score))
                    for (p in 1 until n) lineTo(x(p), y(hours[p].b.score))
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(area, brush = Brush.horizontalGradient(*stops.toTypedArray()))

                // Faint guides at 0 / 5 / 10.
                for (s in listOf(0f, 5f, 10f)) {
                    drawLine(gridColor, Offset(0f, y(s)), Offset(w, y(s)), strokeWidth = 1f)
                }
                // Segment-coloured line on top.
                for (p in 1 until n) {
                    val seg = Path().apply {
                        moveTo(x(p - 1), y(hours[p - 1].b.score))
                        lineTo(x(p), y(hours[p].b.score))
                    }
                    drawPath(
                        seg,
                        color = colorFor((hours[p - 1].b.score + hours[p].b.score) / 2f),
                        style = Stroke(width = 5f, cap = StrokeCap.Round),
                    )
                }
                drawLine(gridColor, Offset(1.5f, 0f), Offset(1.5f, h), strokeWidth = 3f)
            }

            // Transition faces riding the curve; tap for the lingo line.
            if (graphW > 0) {
                val n = hours.size
                faces.forEach { spot ->
                    val frac = if (n <= 1) 0f else spot.index.toFloat() / (n - 1)
                    val score = hours[spot.index].b.score
                    val xDp = with(density) { (graphW * frac).toInt().toDp() } - 9.dp
                    val yFrac = 1f - (score / 10f)
                    val yDp = (graphH - 18.dp) * yFrac
                    Text(
                        spot.emoji,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .offset(x = xDp.coerceIn(0.dp, 10000.dp), y = yDp)
                            .size(18.dp)
                            .clickable {
                                tappedInfo = if (tappedInfo == spot.infoRes) null else spot.infoRes
                            },
                    )
                }
            }
            Text(
                "%.0f".format(hours.first().b.score),
                fontSize = 10.sp,
                color = colorFor(hours.first().b.score),
                modifier = Modifier.align(Alignment.BottomStart),
            )
            Text(
                "%.0f".format(hours.last().b.score),
                fontSize = 10.sp,
                color = colorFor(hours.last().b.score),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        // The tapped face's one-liner, rider to rider.
        tappedInfo?.let { res ->
            Text(
                stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textPrimary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Glyph strip: which factor bites, where along the timeline. Strided
        // so a rainy week doesn't render a hundred droplets.
        var stripWidthPx by remember { mutableStateOf(0) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .onSizeChanged { stripWidthPx = it.width }
        ) {
            if (stripWidthPx > 0) {
                val stride = (hours.size / 14).coerceAtLeast(1)
                for (i in hours.indices step stride) {
                    val b = hours[i].b
                    val glyph: Pair<ImageVector, Color>? = when {
                        b.snow -> Icons.Default.AcUnit to MaterialTheme.appColors.statusDanger
                        b.rain -> Icons.Default.WaterDrop to MaterialTheme.appColors.statusWarn
                        b.wind -> Icons.Default.Air to MaterialTheme.appColors.textSecondary
                        b.night -> Icons.Default.Bedtime to MaterialTheme.appColors.textSecondary
                        else -> null
                    }
                    if (glyph != null) {
                        val frac = if (hours.size <= 1) 0f else i.toFloat() / (hours.size - 1)
                        val xDp = with(density) { (stripWidthPx * frac).toInt().toDp() } - 6.dp
                        Icon(
                            glyph.first,
                            contentDescription = null,
                            tint = glyph.second.copy(alpha = 0.8f),
                            modifier = Modifier.offset(x = xDp.coerceAtLeast(0.dp)).size(12.dp),
                        )
                    }
                }
            }
        }

        // Sparse time ticks: start / quarter / half / three-quarter / end.
        val fmtHour = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtDay = SimpleDateFormat("EEE", Locale.getDefault())
        val spanMs = hours.last().timeMs - hours.first().timeMs
        val fmt = if (spanMs > 36L * 3600_000L) fmtDay else fmtHour
        Row(Modifier.fillMaxWidth()) {
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEachIndexed { idx, f ->
                val i = ((hours.size - 1) * f).toInt()
                Text(
                    if (idx == 0) stringResource(R.string.weather_now)
                    else fmt.format(Date(hours[i].timeMs)),
                    fontSize = 9.sp,
                    color = MaterialTheme.appColors.textSecondary,
                    textAlign = when (idx) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
