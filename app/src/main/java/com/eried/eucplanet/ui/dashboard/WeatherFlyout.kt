package com.eried.eucplanet.ui.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.roundToInt

/** One scored forecast hour, ready for the flyout graph. */
data class ScoredHour(val timeMs: Long, val b: RidabilityScore.Breakdown)

/**
 * The thin forecast strip over the dashboard, styled like the navigation
 * popup: an inverse rounded panel that stands out from the app background.
 * The ridability score runs -5 to +5 around a neutral centre line, drawn as
 * a curve blending light blue (good) to magenta (bad), with rain and snow
 * bands behind it, transition faces on the curve, a glyph strip naming which
 * factor bites when, and sparse time ticks. A quick "is it good to go ride"
 * glance.
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
    // Same family as the navigation popup: the inverse of the app background
    // so it stands out, rounded, with a real shadow.
    val panel = MaterialTheme.appColors.navPopupPanel
    val ink = MaterialTheme.appColors.navPopupInk
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = panel,
        contentColor = ink,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    windowLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        refreshing -> stringResource(R.string.weather_fetching)
                        updatedAgoMin != null -> stringResource(R.string.weather_updated_ago, updatedAgoMin)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.6f),
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ink,
                    )
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.weather_refresh),
                            tint = ink.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = ink.copy(alpha = 0.6f),
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
                    color = ink.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                else -> ScoreGraph(hours, ink)
            }
        }
    }
}

/** A face pinned to the curve: where and which emoji. Faces sit at the start
 *  plus the moments the ride character changes - a band crossing or a hazard
 *  beginning. Pure indicators, no tap behaviour. */
private data class FaceSpot(val index: Int, val emoji: String)

/** The face for an hour, by what dominates it. Priority mirrors danger:
 *  snow, rain, wind, cold, hot, night, then just the score band. */
private fun faceFor(b: RidabilityScore.Breakdown): String = when {
    b.snow -> "🥶"                       // cold face
    b.rain -> "😬"                       // grimace
    b.wind && b.score < 0f -> "😖"       // struggling
    b.cold -> "🥶"
    b.hot -> "🥵"                        // hot face
    b.night && b.score < 2f -> "😴"      // sleepy
    b.score >= 2f -> "😄"                // happy
    b.score >= -1f -> "😐"               // neutral
    else -> "🙁"                         // frown
}

private fun band(score: Float): Int = when {
    score >= 2f -> 2
    score >= -1f -> 1
    else -> 0
}

/** "-3", "0", "+4": the signed edge labels around the neutral centre. */
private fun signedLabel(score: Float): String {
    val v = score.roundToInt()
    return if (v > 0) "+$v" else "$v"
}

@Composable
private fun ScoreGraph(hours: List<ScoredHour>, ink: Color) {
    val good = MaterialTheme.appColors.weatherGood
    val bad = MaterialTheme.appColors.weatherBad
    val gridColor = ink.copy(alpha = 0.15f)
    val centerColor = ink.copy(alpha = 0.35f)
    val rainBand = MaterialTheme.appColors.chartEnvelope.copy(alpha = 0.20f)
    // The same diagonal texture as the charging pack tiles, inked so it reads
    // on the inverse panel in both themes.
    val hatch = ink.copy(alpha = 0.15f)

    // Light blue at +5, magenta at -5, blended through the middle.
    fun colorFor(score: Float): Color = lerp(bad, good, (score + 5f) / 10f)

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
        list.map { i -> FaceSpot(i, faceFor(hours[i].b)) }
    }

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
                fun y(score: Float) = h - ((score + 5f) / 10f) * (h - 6f) - 3f

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
                // every stop, light blue where it is good riding, magenta
                // where it is not, blending smoothly between.
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
                // Diagonal hatch clipped to the score area - the same texture
                // the battery pack tiles carry, spacing and stroke included.
                clipPath(area) {
                    var hx = 0f
                    while (hx < w + h) {
                        drawLine(hatch, Offset(hx, 0f), Offset(hx - h, h), strokeWidth = 1f)
                        hx += 26f
                    }
                }

                // The neutral centre line at 0, stronger than the faint
                // -5 / +5 guides at the edges.
                drawLine(gridColor, Offset(0f, y(5f)), Offset(w, y(5f)), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, y(-5f)), Offset(w, y(-5f)), strokeWidth = 1f)
                drawLine(centerColor, Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 2f)

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

            // Transition faces riding the curve.
            if (graphW > 0) {
                val n = hours.size
                faces.forEach { spot ->
                    val frac = if (n <= 1) 0f else spot.index.toFloat() / (n - 1)
                    val score = hours[spot.index].b.score
                    // Inset from both edges so the end faces sit fully inside
                    // the card instead of kissing its sides.
                    val maxX = with(density) { graphW.toDp() } - 26.dp
                    val xDp = (with(density) { (graphW * frac).toInt().toDp() } - 9.dp)
                        .coerceIn(8.dp, maxX.coerceAtLeast(8.dp))
                    val yFrac = 1f - (score + 5f) / 10f
                    val yDp = (graphH - 18.dp) * yFrac
                    Text(
                        spot.emoji,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .offset(x = xDp, y = yDp)
                            .size(18.dp),
                    )
                }
            }
            // The scale, signed around the neutral centre.
            Text(
                "+5",
                fontSize = 9.sp,
                color = ink.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp),
            )
            Text(
                "-5",
                fontSize = 9.sp,
                color = ink.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp),
            )
            Text(
                signedLabel(hours.last().b.score),
                fontSize = 10.sp,
                color = colorFor(hours.last().b.score),
                modifier = Modifier.align(Alignment.BottomEnd),
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
                        b.snow -> Icons.Default.AcUnit to ink.copy(alpha = 0.7f)
                        b.rain -> Icons.Default.WaterDrop to MaterialTheme.appColors.chartEnvelope
                        b.wind -> Icons.Default.Air to ink.copy(alpha = 0.6f)
                        b.night -> Icons.Default.Bedtime to ink.copy(alpha = 0.6f)
                        else -> null
                    }
                    if (glyph != null) {
                        val frac = if (hours.size <= 1) 0f else i.toFloat() / (hours.size - 1)
                        val xDp = with(density) { (stripWidthPx * frac).toInt().toDp() } - 6.dp
                        Icon(
                            glyph.first,
                            contentDescription = null,
                            tint = glyph.second.copy(alpha = glyph.second.alpha * 0.9f),
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
                    color = ink.copy(alpha = 0.6f),
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
