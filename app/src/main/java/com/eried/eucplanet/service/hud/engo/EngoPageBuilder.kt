package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoElement
import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.model.MetricValueFormat
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.ui.settings.DashboardStat
import com.eried.eucplanet.ui.settings.computeDashboardStatValue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fills an [EngoPage] from what the app knows: the live value of each metric,
 * its rolling history (the dashboard's own buffers, so a max here is the max
 * the tile shows), the rider's units and gauge thresholds, and the next turn.
 *
 * Pure: the values come in as functions, so the settings preview can run the
 * rider's real page on sample numbers and the tests can pin every rule.
 */
object EngoPageBuilder {

    /** Points on a graph before the layout thins them to one every two pixels of its place. */
    const val GRAPH_POINTS = 64

    /** A graph never redraws more often than this, so a short window does not flood Bluetooth. */
    const val GRAPH_STEP_MIN_MS = 500L

    fun build(
        engo: EngoHudSettings,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        connected: Boolean,
        /** A metric's live value in its stored unit (km/h, °C, kPa, m), or null. */
        current: (String) -> Float?,
        /** A metric's rolling history, oldest first, stored units: what the statistics run on. */
        history: (String) -> List<MetricSample>,
        /** A metric's samples over the last [windowMs], oldest first, as finely as they are kept: what a graph draws. */
        recent: (key: String, windowMs: Long) -> List<MetricSample>,
        nav: EngoNav?,
        nowMs: Long,
        /** The dashboard's rolling window, for a graph set to follow it. */
        dashboardWindowSec: Int = 300,
    ): EngoPage = EngoPage(
        connected = connected,
        layout = EngoLayouts.kindOf(engo.layout),
        elements = (0 until EngoLayouts.MAX_ELEMENTS).map { i ->
            engo.elements.getOrNull(i)?.let {
                reading(it, units, orangePct, redPct, connected, current, history, recent, nowMs, dashboardWindowSec)
            }
        },
        font = EngoLayouts.fontOf(engo.font),
        nav = nav,
        turnsOn = engo.navTakeover,
    )

    /** How long a graph looks back, in ms. */
    fun windowMs(windowSec: Int, dashboardWindowSec: Int): Long =
        (if (windowSec > 0) windowSec else dashboardWindowSec.coerceAtLeast(5)) * 1000L

    private fun reading(
        el: EngoElement,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        connected: Boolean,
        current: (String) -> Float?,
        history: (String) -> List<MetricSample>,
        recent: (String, Long) -> List<MetricSample>,
        nowMs: Long,
        dashboardWindowSec: Int,
    ): EngoReading? {
        val key = el.key
        if (key.isBlank() || !EngoMetrics.known(key)) return null
        val supportsStats = MetricCatalog.byKey(key)?.supportsStats ?: false
        val statName = if (supportsStats) el.stat else "CURRENT"
        val stat = runCatching { DashboardStat.valueOf(statName) }.getOrDefault(DashboardStat.CURRENT)
        val title = EngoMetrics.title(key, stat.name, units)
        val style = EngoLayouts.styleOf(el.style)
        if (!connected) return EngoReading(title, "--", style = style)

        val samples = history(key)
        val live = current(key) ?: samples.lastOrNull()?.value
        val raw0 = computeDashboardStatValue(stat, samples, live ?: Float.NaN)
            ?.takeUnless { it.isNaN() }
            ?: return EngoReading(title, "--", style = style)
        // Rolling back reads as speed, not a minus sign.
        val raw = if (key == "SPEED" || key == "GPS_SPEED") abs(raw0) else raw0
        val value = EngoMetrics.digits(MetricValueFormat.format(key, raw, units.speed, "", units.temp, "",
            units.distance, units.pressure, units.altitude))
        val band = if (key == "LOAD") pwmBand(abs(raw), orangePct, redPct) else EngoBand.NORMAL
        val series = if (style == EngoStyle.GRAPH) {
            val w = windowMs(el.windowSec, dashboardWindowSec)
            series(key, recent(key, w), nowMs, w)
        } else emptyList()
        return EngoReading(
            title = title,
            value = value,
            level = level(key, raw, samples),
            series = series,
            band = band,
            style = style,
        )
    }

    fun pwmBand(pct: Float, orangePct: Int, redPct: Int): EngoBand = when {
        pct >= redPct -> EngoBand.DANGER
        pct >= orangePct -> EngoBand.WARN
        else -> EngoBand.OK
    }

    /**
     * How full the bar is. Percentages on 0..100, temperatures on 0..100 °C,
     * anything else against the highest it has been on this ride, so the bar
     * reads "how much of my peak".
     */
    fun level(key: String, raw: Float, samples: List<MetricSample>): Float {
        val v = abs(raw)
        val full = when {
            EngoMetrics.isPercent(key) -> 100f
            EngoMetrics.isTemperature(key) -> 100f
            else -> maxOf(samples.maxOfOrNull { abs(it.value) } ?: 0f, v)
        }
        return if (full <= 0f) 0f else (v / full).coerceIn(0f, 1f)
    }

    /**
     * The last [windowMs] in [GRAPH_POINTS] steps, each 0..1 of the graph's
     * height. Percentages keep their 0..100 scale; anything else spans the
     * window's own low and high, with a small floor on the span so a steady
     * reading draws a calm line instead of magnified noise.
     *
     * The window ends on a step boundary of at least [GRAPH_STEP_MIN_MS], so
     * the line moves in whole steps and an unchanged step sends nothing.
     */
    fun series(key: String, samples: List<MetricSample>, nowMs: Long, windowMs: Long): List<Float> {
        val stepMs = maxOf(windowMs / (GRAPH_POINTS - 1), GRAPH_STEP_MIN_MS)
        val end = nowMs - nowMs % stepMs
        val from = end - windowMs
        val window = samples.filter { it.timestampMs in from..end && !it.value.isNaN() }
        if (window.size < 2) return emptyList()
        // Average the samples falling in each step; a step with none repeats the last.
        val sums = FloatArray(GRAPH_POINTS)
        val counts = IntArray(GRAPH_POINTS)
        for (s in window) {
            val k = (((s.timestampMs - from).toFloat() / windowMs) * (GRAPH_POINTS - 1))
                .roundToInt().coerceIn(0, GRAPH_POINTS - 1)
            val v = if (key == "SPEED" || key == "GPS_SPEED") abs(s.value) else s.value
            sums[k] += v; counts[k]++
        }
        val first = (0 until GRAPH_POINTS).first { counts[it] > 0 }
        val raw = ArrayList<Float>()
        var last = sums[first] / counts[first]
        for (k in first until GRAPH_POINTS) {
            if (counts[k] > 0) last = sums[k] / counts[k]
            raw += last
        }
        if (raw.size < 2) return emptyList()
        val (lo, hi) = if (EngoMetrics.isPercent(key)) 0f to 100f else {
            val mn = raw.min(); val mx = raw.max()
            val span = maxOf(mx - mn, maxOf(abs(mx), abs(mn)) * 0.1f, 1f)
            val mid = (mn + mx) / 2
            (mid - span / 2) to (mid + span / 2)
        }
        return raw.map { ((it - lo) / (hi - lo)).coerceIn(0f, 1f) }
    }
}
