package com.eried.eucplanet.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.weather.HourForecast
import com.eried.eucplanet.weather.RidabilityScore
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One scored forecast hour, ready for the flyout graph. [h] carries the raw
 *  forecast values the expanded detail charts draw. */
data class ScoredHour(
    val timeMs: Long,
    val b: RidabilityScore.Breakdown,
    val h: HourForecast = HourForecast(timeMs, 0f, 0f, 0f, 0f, true),
)

/**
 * The rotating phrases: a "should I ride" title and one flavour line per
 * score level. Picks are random but cached in-process for an hour, so the
 * app keeps one voice for a while instead of rerolling every open. Plain
 * app state, deliberately not a setting.
 */
private object WeatherPhrases {
    private const val TTL_MS = 3_600_000L
    private val titlePool = listOf(
        R.string.weather_title_1,
        R.string.weather_title_2,
        R.string.weather_title_3,
        R.string.weather_title_4,
        R.string.weather_title_5,
    )
    private val levelPools = listOf(
        listOf(R.string.weather_lvl_awful_1, R.string.weather_lvl_awful_2),
        listOf(R.string.weather_lvl_bad_1, R.string.weather_lvl_bad_2),
        listOf(R.string.weather_lvl_notworst_1, R.string.weather_lvl_notworst_2),
        listOf(R.string.weather_lvl_meh_1, R.string.weather_lvl_meh_2),
        listOf(R.string.weather_lvl_ok_1, R.string.weather_lvl_ok_2),
        listOf(R.string.weather_lvl_good_1, R.string.weather_lvl_good_2),
        listOf(R.string.weather_lvl_prime_1, R.string.weather_lvl_prime_2),
    )
    private var title: Pair<Int, Long>? = null
    private val levels = HashMap<Int, Pair<Int, Long>>()

    fun titleRes(): Int {
        val now = System.currentTimeMillis()
        title?.let { if (now - it.second < TTL_MS) return it.first }
        return titlePool.random().also { title = it to now }
    }

    fun levelRes(bucket: Int): Int {
        val now = System.currentTimeMillis()
        levels[bucket]?.let { if (now - it.second < TTL_MS) return it.first }
        return levelPools[bucket].random().also { levels[bucket] = it to now }
    }
}

/** The compact window tag shown beside the title: "6h", "24h", "3d", "1w". */
private fun windowShortRes(windowHours: Int): Int = when {
    windowHours <= 6 -> R.string.weather_win_6
    windowHours <= 24 -> R.string.weather_win_24
    windowHours <= 72 -> R.string.weather_win_3d
    else -> R.string.weather_win_1w
}

/** Vertical segments the window divides into: hourly for 6h, 4h blocks for a
 *  day, half-days for 3 days, whole days for the week. */
private fun windowDivisions(windowHours: Int): Int = if (windowHours > 72) 7 else 6

/**
 * The thin forecast strip over the dashboard, styled like the navigation
 * popup: an inverse rounded panel that stands out from the app background.
 * The ridability score runs -5 to +5 around a dashed zero axis, drawn as a
 * curve blending light blue (good) to magenta (bad), with rain and snow
 * bands behind it, vertical window segments, transition faces the rider can
 * tap for a one-line read in EUC lingo (a compact dark tip, like the trip
 * map's scrub tooltip), a glyph strip naming which factor bites when, and
 * sparse time ticks. A quick "is it good to go ride" glance.
 */
@Composable
fun WeatherFlyout(
    hours: List<ScoredHour>,
    altHours: List<ScoredHour>,
    windowHours: Int,
    tempF: Boolean,
    windMph: Boolean,
    refreshing: Boolean,
    error: String?,
    updatedAgoMin: Int?,
    place: String?,
    destName: String?,
    usingDest: Boolean,
    onToggleSource: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same family as the navigation popup: the inverse of the app background
    // so it stands out, rounded, with a real shadow.
    val panel = MaterialTheme.appColors.navPopupPanel
    val ink = MaterialTheme.appColors.navPopupInk
    val titleRes = remember { WeatherPhrases.titleRes() }
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // Absorb taps so they don't fall through to the dismiss scrim
            // behind the panel, same trick as the nav popup.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        shape = RoundedCornerShape(12.dp),
        color = panel,
        contentColor = ink,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(windowShortRes(windowHours)),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = 0.7f),
                    modifier = Modifier
                        .background(ink.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(4.dp))
                // Where this forecast is for: here (place name when known),
                // or the navigator's final stop. Tap swaps when a route is
                // set; filled style marks destination mode.
                Text(
                    when {
                        usingDest -> destName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.weather_src_destination)
                        else -> place ?: stringResource(R.string.weather_src_current)
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (usingDest) panel else ink.copy(alpha = 0.7f),
                    modifier = Modifier
                        // Long place names ellipsize instead of squeezing the
                        // refresh and expand buttons off the row.
                        .widthIn(max = 110.dp)
                        .background(
                            if (usingDest) ink.copy(alpha = 0.75f) else ink.copy(alpha = 0.12f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(enabled = destName != null) { onToggleSource() }
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                // Explicit swap between here and the destination, only when
                // a route offers one.
                if (destName != null) {
                    IconButton(onClick = onToggleSource, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.weather_swap_src),
                            tint = ink.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // A fresh fetch reads "just now", then quietly fades out;
                // older stamps stay as minutes.
                var justNowShown by remember { mutableStateOf(true) }
                LaunchedEffect(refreshing, updatedAgoMin) {
                    justNowShown = true
                    if (!refreshing && updatedAgoMin == 0) {
                        delay(2500)
                        justNowShown = false
                    }
                }
                val statusAlpha by animateFloatAsState(
                    targetValue = if (refreshing || (updatedAgoMin ?: 1) > 0 || justNowShown) 0.6f else 0f,
                    animationSpec = tween(900),
                    label = "updatedFade",
                )
                Text(
                    when {
                        refreshing -> stringResource(R.string.weather_fetching)
                        updatedAgoMin == 0 -> stringResource(R.string.weather_updated_now)
                        updatedAgoMin != null -> stringResource(R.string.weather_updated_ago, updatedAgoMin)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = statusAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The flexible middle: this shrinks first, so the trailing
                    // icon buttons always keep their full size.
                    modifier = Modifier.weight(1f),
                )
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
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.weather_expand),
                        tint = ink.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp),
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
                else -> ScoreGraph(hours, altHours, windowHours, ink, panel)
            }

            // The expanded condition charts plus the generated advisories,
            // growing the panel downward.
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded && hours.isNotEmpty(),
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                DetailSection(hours, tempF, windMph, ink)
            }
        }
    }
}

/** One line series inside a detail chart, self-normalized to its own range. */
private data class ChartSeries(
    val values: List<Float>,
    val color: Color,
    val dashed: Boolean = false,
)

/**
 * The expanded weather-app view: temp+humidity, precipitation, and
 * wind+gusts as small scrubbable charts sharing one finger-followed cursor
 * (numbers update live, like the trip detail charts), plus a few generated
 * advisory lines for the window.
 */
@Composable
private fun DetailSection(
    hours: List<ScoredHour>,
    tempF: Boolean,
    windMph: Boolean,
    ink: Color,
) {
    var scrub by remember(hours) { mutableStateOf<Float?>(null) }
    val idx = ((hours.size - 1) * (scrub ?: 0f)).roundToInt().coerceIn(0, hours.size - 1)
    val h = hours[idx].h
    fun t(c: Float) = if (tempF) c * 9f / 5f + 32f else c
    fun w(ms: Float) = if (windMph) ms * 2.23694f else ms
    val tUnit = if (tempF) "°F" else "°C"
    val wUnit = if (windMph) "mph" else "m/s"
    val fmtTime = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val onScrub: (Float) -> Unit = { scrub = it }

    Column(Modifier.padding(top = 6.dp)) {
        // The scrubbed moment, once for all three charts.
        Text(
            fmtTime.format(Date(hours[idx].timeMs)),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = 0.75f),
        )
        // Temp, humidity and precipitation share one double-height chart,
        // weather-app style: lines over rain/snow bars.
        DetailChart(
            label = stringResource(R.string.weather_chart_temp),
            value = "%.1f%s · %.0f%% · %s".format(
                t(h.tempC), tUnit, h.humidityPct,
                if (h.snowCmH > 0f) "%.1f cm/h".format(h.snowCmH)
                else "%.1f mm/h".format(h.precipMmH),
            ),
            series = listOf(
                ChartSeries(hours.map { it.h.tempC }, MaterialTheme.appColors.metricTemp),
                ChartSeries(hours.map { it.h.humidityPct }, MaterialTheme.appColors.chartEnvelope, dashed = true),
            ),
            bars = listOf(
                ChartSeries(hours.map { it.h.precipMmH }, MaterialTheme.appColors.chartEnvelope),
                ChartSeries(hours.map { it.h.snowCmH }, ink.copy(alpha = 0.45f)),
            ),
            chartHeight = 88.dp,
            ink = ink, scrub = scrub, onScrub = onScrub,
        )
        DetailChart(
            label = stringResource(R.string.weather_chart_wind),
            value = "%.1f / %.1f %s".format(w(h.windMs), w(h.gustMs), wUnit),
            series = listOf(
                ChartSeries(hours.map { it.h.windMs }, MaterialTheme.appColors.metricPosition),
                ChartSeries(hours.map { it.h.gustMs }, MaterialTheme.appColors.metricPosition, dashed = true),
            ),
            ink = ink, scrub = scrub, onScrub = onScrub,
        )

        // Simple generated advisories: what starts when, what to watch for.
        val nowRef = hours.first().timeMs
        val snowIdx = hours.indexOfFirst { it.h.snowCmH > 0f }
        val rainIdx = hours.indexOfFirst { it.h.snowCmH <= 0f && it.h.precipMmH > 0f }
        val strongGusts = hours.any { it.h.gustMs >= 9f }
        val nearFreeze = hours.any { it.h.tempC <= 1f }

        @Composable
        fun lead(i: Int): String {
            val min = ((hours[i].timeMs - nowRef) / 60_000L).toInt().coerceAtLeast(1)
            return if (min < 90) stringResource(R.string.weather_in_min, min)
            else stringResource(R.string.weather_in_h, (min + 30) / 60)
        }

        val lines = mutableListOf<String>()
        when {
            snowIdx == 0 -> lines += stringResource(R.string.weather_adv_snow_now)
            snowIdx > 0 -> lines += stringResource(R.string.weather_adv_snow_in, lead(snowIdx))
        }
        when {
            rainIdx == 0 -> lines += stringResource(R.string.weather_adv_rain_now)
            rainIdx > 0 -> lines += stringResource(R.string.weather_adv_rain_in, lead(rainIdx))
        }
        if (strongGusts) lines += stringResource(R.string.weather_adv_gusts)
        if (nearFreeze) lines += stringResource(R.string.weather_adv_freeze)
        if (lines.isEmpty()) lines += stringResource(R.string.weather_adv_clear)

        Column(Modifier.padding(top = 6.dp)) {
            lines.take(3).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun DetailChart(
    label: String,
    value: String,
    series: List<ChartSeries>,
    ink: Color,
    scrub: Float?,
    onScrub: (Float) -> Unit,
    bars: List<ChartSeries> = emptyList(),
    chartHeight: Dp = 44.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(label, fontSize = 10.sp, color = ink.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = 0.85f),
        )
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(chartHeight)
            // Finger-follow scrub, plus tap-to-place; the cursor is shared by
            // all three charts so one drag reads the whole moment.
            .pointerInput(series) {
                detectHorizontalDragGestures { change, _ ->
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(series) {
                detectTapGestures { off -> onScrub((off.x / size.width).coerceIn(0f, 1f)) }
            }
    ) {
        val w = size.width
        val hgt = size.height
        // Precipitation-style bars first, behind the lines. All bar series
        // share one scale so rain and snow compare honestly.
        if (bars.isNotEmpty()) {
            val barMax = bars.maxOf { b -> b.values.maxOrNull() ?: 0f }.coerceAtLeast(1f)
            val n = bars.first().values.size
            val slot = if (n > 0) w / n else w
            bars.forEachIndexed { bi, b ->
                val bw = (slot * 0.6f / bars.size).coerceAtLeast(2f)
                for (i in b.values.indices) {
                    val v = b.values.getOrElse(i) { 0f }
                    if (v <= 0f) continue
                    val bh = (v / barMax) * (hgt * 0.6f)
                    drawRect(
                        color = b.color.copy(alpha = 0.55f),
                        topLeft = Offset(slot * i + slot * 0.2f + bi * bw, hgt - 1f - bh),
                        size = Size(bw, bh),
                    )
                }
            }
        }
        series.forEach { s ->
            if (s.values.isEmpty()) return@forEach
            val minV = s.values.min()
            val span = (s.values.max() - minV).coerceAtLeast(0.1f)
            val n = s.values.size
            val pts = List(n) { i ->
                Offset(
                    if (n <= 1) 0f else i * w / (n - 1),
                    hgt - 3f - (s.values[i] - minV) / span * (hgt - 6f),
                )
            }
            val p = smoothPathOf(pts)
            drawPath(
                p,
                color = s.color,
                style = Stroke(
                    width = 3f,
                    cap = StrokeCap.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
                ),
            )
        }
        drawLine(ink.copy(alpha = 0.12f), Offset(0f, hgt - 1f), Offset(w, hgt - 1f), strokeWidth = 1f)
        scrub?.let { f ->
            drawLine(ink.copy(alpha = 0.5f), Offset(w * f, 0f), Offset(w * f, hgt), strokeWidth = 2f)
        }
    }
}

/** A face pinned to the curve: where, which emoji, and the lingo line its
 *  tap tooltip shows. Faces sit at the start plus the moments the ride
 *  character changes - a band crossing or a hazard beginning. */
private data class FaceSpot(val index: Int, val emoji: String, val infoRes: Int)

/** A visible tooltip on the graph: where, an optional numeric prefix, and
 *  the phrase resource. srcKey identifies the anchor so a second tap on the
 *  same spot closes it. */
private data class GraphTip(
    val srcKey: Int,
    val frac: Float,
    val yFrac: Float,
    val prefix: String?,
    val textRes: Int,
)

/** Score level buckets for the graph-tap read, -5 horrible up to +5 prime. */
private fun levelBucket(score: Float): Int = when {
    score <= -3.5f -> 0
    score <= -1.5f -> 1
    score <= -0.5f -> 2
    score < 0.5f -> 3
    score < 2.5f -> 4
    score < 4.5f -> 5
    else -> 6
}

/** The face and tip line for an hour, by what dominates it. Priority mirrors
 *  danger: snow, rain, wind, cold, hot, night, then just the score band. */
private fun faceFor(b: RidabilityScore.Breakdown): Pair<String, Int> = when {
    b.snow -> "🥶" to R.string.weather_face_snow          // cold face
    b.rain -> "😬" to R.string.weather_face_rain          // grimace
    b.wind && b.score < 0f -> "😖" to R.string.weather_face_wind
    b.cold -> "🥶" to R.string.weather_face_cold
    b.hot -> "🥵" to R.string.weather_face_hot            // hot face
    b.night && b.score < 2f -> "😴" to R.string.weather_face_night
    b.score >= 2f -> "😄" to R.string.weather_face_clear  // happy
    b.score >= -1f -> "😐" to R.string.weather_face_meh   // neutral
    else -> "🙁" to R.string.weather_face_meh             // frown
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

/** Catmull-Rom cubic for the segment i -> i+1 of [pts]. Display smoothing
 *  only - the numeric data and every hit test stay on the exact points. */
private fun Path.smoothSegmentTo(pts: List<Offset>, i: Int) {
    val p0 = pts[(i - 1).coerceAtLeast(0)]
    val p1 = pts[i]
    val p2 = pts[i + 1]
    val p3 = pts[(i + 2).coerceAtMost(pts.lastIndex)]
    cubicTo(
        p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
        p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
        p2.x, p2.y,
    )
}

/** The whole polyline as one smooth path. */
private fun smoothPathOf(pts: List<Offset>): Path = Path().apply {
    if (pts.isNotEmpty()) {
        moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.lastIndex) smoothSegmentTo(pts, i)
    }
}

@Composable
private fun ScoreGraph(
    hours: List<ScoredHour>,
    alt: List<ScoredHour>,
    windowHours: Int,
    ink: Color,
    panel: Color,
) {
    val good = MaterialTheme.appColors.weatherGood
    val bad = MaterialTheme.appColors.weatherBad
    val gridColor = ink.copy(alpha = 0.15f)
    // Super faint and dotted, so the neutral axis can never be confused
    // with the long-dashed comparison curve.
    val axisColor = ink.copy(alpha = 0.22f)
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
        list.map { i ->
            val (emoji, res) = faceFor(hours[i].b)
            FaceSpot(i, emoji, res)
        }
    }
    // The visible tooltip (face lingo, or a graph-tap level read),
    // auto-hiding like a toast. The random phrase behind it is what
    // WeatherPhrases keeps stable for an hour.
    var tip by remember(hours) { mutableStateOf<GraphTip?>(null) }
    LaunchedEffect(tip) {
        if (tip != null) {
            delay(4000)
            tip = null
        }
    }

    Column {
        val density = LocalDensity.current
        var graphW by remember { mutableStateOf(0) }
        // The modal presentation buys room: about 25% taller than the first cut.
        val graphH = 80.dp

        Box(
            Modifier
                .fillMaxWidth()
                .height(graphH)
                .onSizeChanged { graphW = it.width }
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(graphH)
                    // Tap anywhere on the graph for the numeric read of that
                    // moment plus its level line.
                    .pointerInput(hours) {
                        detectTapGestures { off ->
                            val n = hours.size
                            val i = if (n <= 1) 0
                            else ((off.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                            val score = hours[i].b.score
                            val key = -(i + 1)
                            tip = if (tip?.srcKey == key) null else GraphTip(
                                srcKey = key,
                                frac = if (n <= 1) 0f else i / (n - 1f),
                                yFrac = 1f - (score + 5f) / 10f,
                                prefix = signedLabel(score),
                                textRes = WeatherPhrases.levelRes(levelBucket(score)),
                            )
                        }
                    }
                    // And the finger-follow version: drag along the curve and
                    // the read moves with the finger.
                    .pointerInput(hours) {
                        detectHorizontalDragGestures { change, _ ->
                            val n = hours.size
                            val i = if (n <= 1) 0
                            else ((change.position.x / size.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                            val score = hours[i].b.score
                            tip = GraphTip(
                                srcKey = -(i + 1),
                                frac = if (n <= 1) 0f else i / (n - 1f),
                                yFrac = 1f - (score + 5f) / 10f,
                                prefix = signedLabel(score),
                                textRes = WeatherPhrases.levelRes(levelBucket(score)),
                            )
                        }
                    }
            ) {
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

                // Ghost fill for the comparison location, drawn first so the
                // selected location's fill sits over it.
                if (alt.size > 1) {
                    val m = alt.size
                    fun axf(i: Int) = i * w / (m - 1)
                    val altPts = List(m) { Offset(axf(it), y(alt[it].b.score)) }
                    val altArea = smoothPathOf(altPts).apply {
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }
                    val altStride = (m / 32).coerceAtLeast(1)
                    val altStops = ArrayList<Pair<Float, Color>>()
                    var ak = 0
                    while (ak < m) {
                        altStops.add((if (m <= 1) 0f else ak / (m - 1f)) to colorFor(alt[ak].b.score).copy(alpha = 0.12f))
                        ak += altStride
                    }
                    if (altStops.last().first < 1f) altStops.add(1f to colorFor(alt[m - 1].b.score).copy(alpha = 0.12f))
                    drawPath(altArea, brush = Brush.horizontalGradient(*altStops.toTypedArray()))
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
                val pts = List(n) { Offset(x(it), y(hours[it].b.score)) }
                val area = smoothPathOf(pts).apply {
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

                // Vertical window segments: hourly for 6h, coarser blocks for
                // the longer windows, so the timeline has visible structure.
                val div = windowDivisions(windowHours)
                for (d in 1 until div) {
                    val vx = w * d / div
                    drawLine(gridColor, Offset(vx, 0f), Offset(vx, h), strokeWidth = 1f)
                }

                // Faint -5 / +5 guides at the edges; the zero line is a
                // dashed axis so it reads as the neutral reference, not as
                // part of the measurement.
                drawLine(gridColor, Offset(0f, y(5f)), Offset(w, y(5f)), strokeWidth = 1f)
                drawLine(gridColor, Offset(0f, y(-5f)), Offset(w, y(-5f)), strokeWidth = 1f)
                drawLine(
                    axisColor,
                    Offset(0f, y(0f)),
                    Offset(w, y(0f)),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f)),
                )

                // Segment-coloured line on top.
                for (p in 1 until n) {
                    val seg = Path().apply {
                        moveTo(pts[p - 1].x, pts[p - 1].y)
                        smoothSegmentTo(pts, p - 1)
                    }
                    drawPath(
                        seg,
                        color = colorFor((hours[p - 1].b.score + hours[p].b.score) / 2f),
                        style = Stroke(width = 5f, cap = StrokeCap.Round),
                    )
                }
                // Comparison overlay: the other location's curve, dashed and
                // dimmer, so the rider sees at a glance whether the score
                // improves or decays over there.
                if (alt.size > 1) {
                    val m = alt.size
                    fun ax(i: Int) = i * w / (m - 1)
                    val altSegPts = List(m) { Offset(ax(it), y(alt[it].b.score)) }
                    for (p in 1 until m) {
                        val seg = Path().apply {
                            moveTo(altSegPts[p - 1].x, altSegPts[p - 1].y)
                            smoothSegmentTo(altSegPts, p - 1)
                        }
                        drawPath(
                            seg,
                            color = colorFor((alt[p - 1].b.score + alt[p].b.score) / 2f)
                                .copy(alpha = 0.55f),
                            style = Stroke(
                                width = 2.5f,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 9f)),
                            ),
                        )
                    }
                }
            }

            // Transition faces riding the curve; tap for the lingo tip.
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
                            .size(18.dp)
                            .clickable {
                                tip = if (tip?.srcKey == spot.index) null
                                else GraphTip(spot.index, frac, yFrac, null, spot.infoRes)
                            },
                    )
                }

                // The tip: a compact dark bubble like the trip map's scrub
                // tooltip, floated near its anchor and clamped to the graph,
                // inverse-inked so it pops on the panel. Face taps show the
                // lingo line; graph taps prefix the signed score.
                tip?.let { t ->
                    val tipY = ((graphH - 18.dp) * t.yFrac - 24.dp).coerceAtLeast(0.dp)
                    val prefix = t.prefix?.let { "$it · " } ?: ""
                    Box(Modifier.fillMaxWidth().offset(y = tipY)) {
                        Text(
                            prefix + stringResource(t.textRes),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = panel,
                            maxLines = 1,
                            modifier = Modifier
                                .align(BiasAlignment(t.frac * 2f - 1f, 0f))
                                .shadow(4.dp, RoundedCornerShape(5.dp))
                                .background(ink.copy(alpha = 0.92f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                .clickable { tip = null },
                        )
                    }
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
            // The comparison curve's end score, dimmer, top-right.
            if (alt.isNotEmpty()) {
                Text(
                    signedLabel(alt.last().b.score),
                    fontSize = 9.sp,
                    color = colorFor(alt.last().b.score).copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
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
