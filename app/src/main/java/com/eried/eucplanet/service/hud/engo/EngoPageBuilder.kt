package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.EngoHudSettings
import com.eried.eucplanet.data.model.EngoSlot
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

    /** The graph covers the last minute. */
    const val GRAPH_WINDOW_MS = 60_000L

    /** Points on a graph: one every two pixels of an 86 px column. */
    const val GRAPH_POINTS = 43

    fun build(
        engo: EngoHudSettings,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        connected: Boolean,
        /** A metric's live value in its stored unit (km/h, °C, kPa, m), or null. */
        current: (String) -> Float?,
        /** A metric's rolling history, oldest first, stored units. */
        history: (String) -> List<MetricSample>,
        nav: EngoNav?,
        nowMs: Long,
    ): EngoPage {
        val speedRaw = if (connected) current("SPEED") else null
        val speed = speedRaw?.let {
            EngoMetrics.digits(MetricValueFormat.format("SPEED", abs(it), units.speed, "", units.temp, "",
                units.distance, units.pressure, units.altitude))
        } ?: "--"
        fun reading(slot: EngoSlot) = reading(slot, units, orangePct, redPct, connected, current, history, nowMs)
        return EngoPage(
            connected = connected,
            speed = speed,
            speedUnit = units.speedLabel,
            top = reading(engo.top),
            bottom = (0 until 3).map { i -> engo.slots.getOrNull(i)?.let { reading(it) } },
            graphs = engo.graphs,
            nav = if (engo.navTakeover) nav else null,
        )
    }

    private fun reading(
        slot: EngoSlot,
        units: EngoMetrics.Units,
        orangePct: Int,
        redPct: Int,
        connected: Boolean,
        current: (String) -> Float?,
        history: (String) -> List<MetricSample>,
        nowMs: Long,
    ): EngoReading? {
        val key = slot.key
        if (key.isBlank() || !EngoMetrics.known(key)) return null
        val supportsStats = MetricCatalog.byKey(key)?.supportsStats ?: false
        val statName = if (supportsStats) slot.stat else "CURRENT"
        val stat = runCatching { DashboardStat.valueOf(statName) }.getOrDefault(DashboardStat.CURRENT)
        val title = EngoMetrics.title(key, stat.name, units)
        if (!connected) return EngoReading(title, "--")

        val samples = history(key)
        val live = current(key) ?: samples.lastOrNull()?.value
        val raw = computeDashboardStatValue(stat, samples, live ?: Float.NaN)
            ?.takeUnless { it.isNaN() }
            ?: return EngoReading(title, "--")
        val value = EngoMetrics.digits(MetricValueFormat.format(key, raw, units.speed, "", units.temp, "",
            units.distance, units.pressure, units.altitude))
        val band = if (key == "LOAD") pwmBand(abs(raw), orangePct, redPct) else EngoBand.NORMAL
        return EngoReading(
            title = title,
            value = value,
            level = level(key, raw, samples),
            series = series(key, samples, nowMs),
            band = band,
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
     * The last minute in [GRAPH_POINTS] steps, each 0..1 of the graph's
     * height. Percentages keep their 0..100 scale; anything else spans the
     * minute's own low and high, with a small floor on the span so a steady
     * reading draws a calm line instead of magnified noise.
     */
    fun series(key: String, samples: List<MetricSample>, nowMs: Long): List<Float> {
        val from = nowMs - GRAPH_WINDOW_MS
        val window = samples.filter { it.timestampMs >= from && !it.value.isNaN() }
        if (window.size < 2) return emptyList()
        // Average the samples falling in each step; a step with none repeats the last.
        val sums = FloatArray(GRAPH_POINTS)
        val counts = IntArray(GRAPH_POINTS)
        for (s in window) {
            val k = (((s.timestampMs - from).toFloat() / GRAPH_WINDOW_MS) * (GRAPH_POINTS - 1))
                .roundToInt().coerceIn(0, GRAPH_POINTS - 1)
            sums[k] += s.value; counts[k]++
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
