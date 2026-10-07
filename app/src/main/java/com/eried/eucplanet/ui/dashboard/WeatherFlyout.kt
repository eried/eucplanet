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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.alpha
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

/** The compact window tag beside the title: "8 h", "36 h", "3 d".
 *
 *  Formatted rather than picked from four fixed strings, because the window
 *  is any number of hours now. Days once past a day and a half, where the
 *  hour count stops being something a rider reads at a glance; rounded to
 *  the nearest day, so 72 h is "3 d" and 80 h still is. */
@Composable
private fun windowShortLabel(windowHours: Int): String =
    if (windowHours <= 36) stringResource(R.string.weather_win_hours_fmt, windowHours)
    else stringResource(R.string.weather_win_days_fmt, (windowHours + 12) / 24)

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
    /** Open with the detail charts already unfolded, per the rider's setting. */
    startExpanded: Boolean = false,
) {
    // Same family as the navigation popup: the inverse of the app background
    // so it stands out, rounded, with a real shadow.
    val panel = MaterialTheme.appColors.navPopupPanel
    val ink = MaterialTheme.appColors.navPopupInk
    val titleRes = remember { WeatherPhrases.titleRes() }
    var expanded by remember { mutableStateOf(startExpanded) }
    // The one moment the whole panel is pointing at, 0..1 across the window.
    // Hoisted so the score graph and the detail charts cannot disagree about
    // where the rider's finger is.
    var scrubFrac by remember(hours) { mutableStateOf<Float?>(null) }
    // ...and it does not outstay the finger. Every move restarts the clock,
    // so it is five seconds after the rider STOPS, not after they start.
    var scrubFading by remember(hours) { mutableStateOf(false) }
    val scrubAlpha by animateFloatAsState(
        targetValue = if (scrubFading) 0f else 1f,
        animationSpec = tween(SCRUB_FADE_MS),
        label = "scrubFade",
    )
    LaunchedEffect(scrubFrac) {
        if (scrubFrac == null) {
            scrubFading = false
            return@LaunchedEffect
        }
        scrubFading = false
        delay(SCRUB_HOLD_MS)
        scrubFading = true
        // Clear only once it has actually faded, so the readouts do not snap
        // back to "now" while the line is still on screen.
        delay(SCRUB_FADE_MS.toLong())
        scrubFrac = null
        scrubFading = false
    }
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
            // Always now, never the scrubbed hour. Dragging the curve
            // already answers "what about then" in the tooltip that follows
            // the finger; if the header moved too, the rider would lose the
            // one fixed reading on the panel and have to lift off to get it
            // back. The header is the answer to "should I go out", which is
            // a question about now.
            val head = hours.firstOrNull()
            // The panel's own ramp, from the theme. The widget hardcodes the
            // same two ends only because widgets inflate outside the theme.
            val good = MaterialTheme.appColors.weatherGood
            val bad = MaterialTheme.appColors.weatherBad
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (head != null) {
                    val (emoji, verdictRes) = faceFor(head.b)
                    Text(emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        signedLabel(head.b.score),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = lerp(bad, good, (head.b.score + 5f) / 10f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(verdictRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = ink.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    // No forecast yet: the question is all there is to say.
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = ink,
                        modifier = Modifier.weight(1f),
                    )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    windowShortLabel(windowHours),
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
                        // conditions and the refresh button off the row.
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
                // The numbers the widget shows under its score, in the panel's
                // own units. This is the flexible cell: it ellipsizes first so
                // the stamp and the refresh button keep their size.
                Text(
                    head?.let {
                        val degrees = if (tempF) it.h.tempC * 9f / 5f + 32f else it.h.tempC
                        val speed = if (windMph) it.h.windMs * 2.23694f else it.h.windMs
                        "%.1f%s · %.1f %s".format(
                            degrees, if (tempF) "°F" else "°C",
                            speed, if (windMph) "mph" else "m/s",
                        )
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The stamp says its piece and then gets out of the way. It
                // is context for a reading, not a clock worth a permanent
                // seat: how old the forecast is matters when you arrive at
                // the panel, and stops mattering while you read it.
                //
                // Keyed on refreshing alone, deliberately. The age ticks over
                // on its own every minute, and keying on that would flash the
                // stamp back once a minute forever. This way it returns for
                // the two things the rider would call an update: opening the
                // panel again, which remembers afresh, and a fetch landing.
                var stampShown by remember { mutableStateOf(true) }
                LaunchedEffect(refreshing) {
                    stampShown = true
                    if (!refreshing) {
                        delay(STAMP_HOLD_MS)
                        stampShown = false
                    }
                }
                val statusAlpha by animateFloatAsState(
                    targetValue = if (stampShown) 0.6f else 0f,
                    animationSpec = tween(900),
                    label = "updatedFade",
                )
                Text(
                    when {
                        // Nothing: the spinner sitting right beside this
                        // says "fetching" on its own, and the words next to
                        // it were the same sentence the panel body already
                        // shows where the graph will appear.
                        refreshing -> ""
                        // Just the age. "Updated" said nothing the clock
                        // did not, and it was the half that got truncated.
                        updatedAgoMin == 0 -> stringResource(R.string.sources_fresh_just_now)
                        updatedAgoMin != null ->
                            stringResource(R.string.sources_fresh_minutes_fmt, updatedAgoMin)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = statusAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // One still icon, never a spinner swapped in for it. A
                // spinner here spun in the corner of a panel a rider is
                // reading, and it moved the row's contents as it came and
                // went. It dims instead, and the panel body already says
                // "fetching" where the graph will be.
                IconButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.weather_refresh),
                        tint = ink.copy(alpha = if (refreshing) 0.25f else 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            when {
                // A precondition, not a failure: there is nothing to report
                // about because there is nowhere to ask about. Shown on its
                // own, without the "Forecast failed" wrapper that a real fetch
                // error earns.
                hours.isEmpty() && error == stringResource(R.string.weather_error_no_location) -> Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 10.dp),
                )

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
                else -> ScoreGraph(
                    hours, altHours, windowHours, ink, panel,
                    scrub = scrubFrac,
                    onScrub = { scrubFrac = it },
                    scrubAlpha = scrubAlpha,
                )
            }

            // The expanded condition charts plus the generated advisories,
            // growing the panel downward.
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded && hours.isNotEmpty(),
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                DetailSection(
                    hours, tempF, windMph, ink,
                    scrub = scrubFrac,
                    onScrub = { scrubFrac = it },
                    scrubAlpha = scrubAlpha,
                )
            }
        }
    }
}

/** How long a scrubbed read stays put after the finger stops. */
private const val SCRUB_HOLD_MS = 5000L

/** How long the "7 min ago" stamp stays before fading out of the header. */
private const val STAMP_HOLD_MS = 4000L

/** And how long it takes to go, so it fades rather than blinking out. */
private const val SCRUB_FADE_MS = 600

/** One line series inside a detail chart, self-normalized to its own range
 *  unless the chart shares one. [fill] paints a faint band under the line,
 *  which suits a quantity that is really "how much", like wind. */
private data class ChartSeries(
    val values: List<Float>,
    val color: Color,
    val dashed: Boolean = false,
    val fill: Boolean = false,
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
    /** The panel's one scrubbed moment, shared with the score graph above. */
    scrub: Float?,
    onScrub: (Float) -> Unit,
    /** Fades to zero once the read has been sitting there unattended. */
    scrubAlpha: Float,
) {
    val idx = ((hours.size - 1) * (scrub ?: 0f)).roundToInt().coerceIn(0, hours.size - 1)
    val h = hours[idx].h
    fun t(c: Float) = if (tempF) c * 9f / 5f + 32f else c
    fun w(ms: Float) = if (windMph) ms * 2.23694f else ms
    val tUnit = if (tempF) "°F" else "°C"
    val wUnit = if (windMph) "mph" else "m/s"
    Column(Modifier.padding(top = 6.dp)) {
        // No time line here any more: the bubble on the score curve says the
        // moment for the whole panel, and saying it twice put the same words
        // three centimetres apart.
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
                ChartSeries(hours.map { it.h.tempC }, MaterialTheme.appColors.weatherTemp),
                ChartSeries(
                    hours.map { it.h.humidityPct },
                    MaterialTheme.appColors.weatherHumidity,
                    dashed = true,
                    fill = true,
                ),
            ),
            bars = listOf(
                ChartSeries(hours.map { it.h.precipMmH }, MaterialTheme.appColors.weatherPrecip),
                ChartSeries(hours.map { it.h.snowCmH }, ink.copy(alpha = 0.45f)),
            ),
            chartHeight = 88.dp,
            ink = ink, scrub = scrub, onScrub = onScrub,
        )
        DetailChart(
            label = stringResource(R.string.weather_chart_wind),
            value = "%.1f / %.1f %s".format(w(h.windMs), w(h.gustMs), wUnit),
            series = listOf(
                ChartSeries(
                    hours.map { it.h.windMs },
                    MaterialTheme.appColors.weatherWind,
                    fill = true,
                ),
                ChartSeries(hours.map { it.h.gustMs }, MaterialTheme.appColors.weatherWind, dashed = true),
            ),
            // Wind and gusts are the same quantity, so they share one scale:
            // a gust is never weaker than the wind under it, and the dashed
            // line riding above the filled band is what says so.
            sharedScale = true,
            // Same height as the chart above it: two panels of one size read
            // as a set, and the fill needs the room anyway.
            chartHeight = 88.dp,
            ink = ink, scrub = scrub, onScrub = onScrub,
        )

        // Simple generated advisories: what starts when, what to watch for.
        val nowRef = hours.first().timeMs
        val snowIdx = hours.indexOfFirst { it.h.snowCmH > 0f }
        val rainIdx = hours.indexOfFirst { it.h.snowCmH <= 0f && it.h.precipMmH > 0f }
        val strongGusts = hours.any { it.h.gustMs >= 9f }
        val nearFreeze = hours.any { it.h.tempC <= 1f }
        val hotStretch = hours.any { it.b.hot }
        val goldenIdx = hours.indexOfFirst { it.b.golden }

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
        if (hotStretch) lines += stringResource(R.string.weather_adv_heat)
        if (goldenIdx > 0) lines += stringResource(R.string.weather_adv_golden, lead(goldenIdx))
        if (lines.isEmpty()) lines += stringResource(R.string.weather_adv_clear)

        // One flowing paragraph rather than a line per advisory: they are
        // short sentences about the same window, and stacked they read as a
        // warning list for a ride that is usually fine.
        Text(
            lines.take(4).joinToString(" "),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ink.copy(alpha = 0.8f),
        )
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
    scrubAlpha: Float = 1f,
    // One scale across every series, for charts whose lines are the same
    // quantity (wind and its gusts). Off for mixed units, where each line
    // has to normalize to its own range to be readable at all.
    sharedScale: Boolean = false,
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
        // A shared scale spans every series at once; otherwise each line gets
        // its own, as before.
        val sharedMin = if (sharedScale) series.minOf { it.values.minOrNull() ?: 0f } else 0f
        val sharedMax = if (sharedScale) series.maxOf { it.values.maxOrNull() ?: 0f } else 0f
        fun scaledPoints(s: ChartSeries): List<Offset> {
            val minV = if (sharedScale) sharedMin else s.values.min()
            val span = ((if (sharedScale) sharedMax else s.values.max()) - minV)
                .coerceAtLeast(0.1f)
            val n = s.values.size
            return List(n) { i ->
                Offset(
                    if (n <= 1) 0f else i * w / (n - 1),
                    hgt - 3f - (s.values[i] - minV) / span * (hgt - 6f),
                )
            }
        }

        // Bands first, then the bars, then the lines: a fill painted over the
        // precipitation bars would grey them out, and they are the reading
        // that matters most on that chart.
        series.forEach { s ->
            if (s.values.isEmpty() || !s.fill) return@forEach
            val pts = scaledPoints(s)
            val area = smoothPathOf(pts).apply {
                lineTo(w, hgt)
                lineTo(0f, hgt)
                close()
            }
            // Anchored to the LINE, not to the canvas: a gradient measured
            // from the top of the chart lands almost entirely in its own
            // transparent half whenever the value is low, and the band
            // vanishes exactly when it is most worth seeing.
            drawPath(
                area,
                brush = Brush.verticalGradient(
                    listOf(s.color.copy(alpha = 0.30f), s.color.copy(alpha = 0.04f)),
                    startY = pts.minOf { it.y },
                    endY = hgt,
                ),
            )
        }

        // Precipitation-style bars, behind the lines. All bar series
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
            val p = smoothPathOf(scaledPoints(s))
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
            drawLine(
                ink.copy(alpha = 0.5f * scrubAlpha),
                Offset(w * f, 0f), Offset(w * f, hgt), strokeWidth = 2f,
            )
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
    /** "Sat 08:15", shown above the phrase. Null for the face tips, which
     *  are about the character of an hour rather than a moment in it. */
    val timeLabel: String? = null,
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

/** The face and tip line for an hour, by what dominates it. The choice itself
 *  lives in [com.eried.eucplanet.weather.WeatherFace] so the home screen
 *  widgets reach the same verdict from a snapshot. */
private fun faceFor(b: RidabilityScore.Breakdown): Pair<String, Int> =
    com.eried.eucplanet.weather.WeatherFace.of(b).let { it.emoji to it.textRes }

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
    scrub: Float?,
    onScrub: (Float) -> Unit,
    scrubAlpha: Float,
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
                (c.rain && !a.rain) || (c.snow && !a.snow) ||
                (c.golden && !a.golden)
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
        // Not the first one. The header shows exactly that face, larger and
        // a couple of centimetres above, so on the curve it is the same
        // answer twice. The transitions after it are the ones that say
        // something the header cannot.
        list.filter { it != 0 }.map { i ->
            val (emoji, res) = faceFor(hours[i].b)
            FaceSpot(i, emoji, res)
        }
    }
    // The visible tooltip (face lingo, or a graph-tap level read),
    // auto-hiding like a toast. The random phrase behind it is what
    // WeatherPhrases keeps stable for an hour.
    // Face tips only. The scrubbed read is derived from the shared moment
    // below, so dragging ANY chart in the panel updates it - the detail
    // charts no longer carry a time line of their own.
    var tip by remember(hours) { mutableStateOf<GraphTip?>(null) }
    val fmtTip = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val scrubTip: GraphTip? = scrub?.let { f ->
        val n = hours.size
        val i = ((n - 1) * f).roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0))
        val score = hours[i].b.score
        GraphTip(
            srcKey = -(i + 1),
            frac = if (n <= 1) 0f else i / (n - 1f),
            yFrac = 1f - (score + 5f) / 10f,
            prefix = signedLabel(score),
            textRes = WeatherPhrases.levelRes(levelBucket(score)),
            timeLabel = fmtTip.format(Date(hours[i].timeMs)),
        )
    }
    LaunchedEffect(tip) {
        if (tip != null) {
            delay(SCRUB_HOLD_MS)
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
                            // The read comes from the moment itself now.
                            tip = null
                            onScrub(if (n <= 1) 0f else i / (n - 1f))
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
                            tip = null
                            onScrub(if (n <= 1) 0f else i / (n - 1f))
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
                // The scrubbed moment, DASHED and in that moment's own score
                // colour. Deliberately not the thin solid line the detail
                // charts use: this graph is the verdict and those are the
                // evidence, so the two marks should not read alike. Drawn
                // over the curve, since it is what the rider is pointing at.
                scrub?.let { f ->
                    val cx = f.coerceIn(0f, 1f) * w
                    val ci = ((n - 1) * f).roundToInt().coerceIn(0, n - 1)
                    drawLine(
                        color = colorFor(hours[ci].b.score).copy(alpha = 0.85f * scrubAlpha),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
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
                (tip ?: scrubTip)?.let { t ->
                    val tipY = ((graphH - 18.dp) * t.yFrac - 24.dp).coerceAtLeast(0.dp)
                    val prefix = t.prefix?.let { "$it · " } ?: ""
                    Box(Modifier.fillMaxWidth().offset(y = tipY)) {
                        Column(
                            modifier = Modifier
                                .align(BiasAlignment(t.frac * 2f - 1f, 0f))
                                // The bubble goes with its line: one read,
                                // one fade.
                                .alpha(if (tip != null) 1f else scrubAlpha)
                                .shadow(4.dp, RoundedCornerShape(5.dp))
                                .background(ink.copy(alpha = 0.92f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                                .clickable { tip = null },
                        ) {
                            // When, then what. The time leads because with the
                            // panel collapsed there is nothing else on screen
                            // that says which moment is being read.
                            t.timeLabel?.let { when_ ->
                                Text(
                                    when_,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = panel.copy(alpha = 0.75f),
                                    maxLines = 1,
                                )
                            }
                            Text(
                                prefix + stringResource(t.textRes),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = panel,
                                maxLines = 1,
                            )
                        }
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

        // Time ticks at ROUND clock times, placed where those times actually
        // fall.
        //
        // They used to be the quarter, half and three-quarter points of the
        // data, printing whatever timestamp sat there: an eight-hour window
        // starting at 09:00 read 11:15, 13:30, 15:45. Positions, not times.
        // Nobody reads a forecast in quarter-of-the-window units.
        val fmtHour = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtDay = SimpleDateFormat("EEE", Locale.getDefault())
        val startMs = hours.first().timeMs
        val spanMs = hours.last().timeMs - startMs
        val fmt = if (spanMs > 36L * 3600_000L) fmtDay else fmtHour
        val nowLabel = stringResource(R.string.weather_now)
        // The smallest step that still leaves the labels far enough apart to
        // read; a day once the window is long enough to be about days.
        val stepMs = listOf(
            1L, 2L, 3L, 6L, 12L, 24L, 48L, 72L,
        ).map { it * 3600_000L }.firstOrNull { spanMs / it <= 4L } ?: (7L * 24 * 3600_000L)
        val ticks = remember(startMs, spanMs, stepMs) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = startMs
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // Walk whole hours from the top of the starting hour: the first
            // round time on the step lands inside the window.
            val stepH = (stepMs / 3600_000L).toInt().coerceAtLeast(1)
            while (cal.timeInMillis < startMs ||
                (cal.get(java.util.Calendar.HOUR_OF_DAY) % stepH) != 0
            ) {
                cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
            }
            val out = ArrayList<Pair<Float, Long>>()
            while (cal.timeInMillis <= startMs + spanMs && out.size < 8) {
                val f = if (spanMs <= 0L) 0f else (cal.timeInMillis - startMs).toFloat() / spanMs
                // Leave the left end to "Now" and keep the last label on-panel.
                if (f > 0.12f && f < 0.97f) out.add(f to cal.timeInMillis)
                cal.add(java.util.Calendar.HOUR_OF_DAY, stepH)
            }
            out
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val full = maxWidth
            val slot = 44.dp
            Text(
                nowLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = ink.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            ticks.forEach { (f, t) ->
                Text(
                    fmt.format(Date(t)),
                    fontSize = 9.sp,
                    color = ink.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(slot)
                        .offset(x = (full - slot) * f),
                )
            }
        }
    }
}
