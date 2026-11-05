package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.FullMetricHistory
import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.util.Smoothing
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs

/** Strings in the language the voice speaks, whatever the app is set to. */
interface SpokenStrings {
    fun text(res: StringResource, vararg args: Any): String

    /** The time now in that language's short clock format ("14:05", "2:05 PM"). */
    fun timeNow(): String
}

/**
 * What each spoken report says: the periodic and triggered announcements, a
 * spoken question's answer and the settings preview. Which reports, in what
 * order, is [VoiceReportPlan]; this turns them into sentences from live
 * telemetry. Shared so both phones say the same thing; each platform's voice
 * (VoiceService on Android) only speaks the result.
 */
class VoiceReports(
    /** The dashboard's history, for the statistic reports ("max speed"). */
    private val fullHistory: () -> FullMetricHistory,
    /** The phone's battery percent, for the "phone" report. */
    private val phoneBattery: () -> Int,
) {
    private val lock = SynchronizedObject()

    /**
     * Current spoken navigation cue, kept up to date by [NavigationEngine] as
     * it pushes navState changes. Read by [buildReportParts] when the
     * "Navigation" row of the Report-status grid is on, so the periodic /
     * triggered status report includes the live turn instruction.
     */
    @kotlin.concurrent.Volatile var currentNavCue: String? = null

    /**
     * Recent telemetry, so limit-style reports can speak an average instead of
     * whatever the wheel happened to be doing at the instant the report fired.
     *
     * A momentary PWM or current peak tells a rider nothing about how hard the
     * wheel is actually working, and it is the sustained load that matters for
     * staying inside the wheel's limits.
     *
     * Bounded by time, not by count, because the BLE sample rate varies by
     * wheel and by the poll-interval setting.
     */
    private class LoadSample(val t: Long, val pwm: Float, val current: Float, val powerW: Float)

    private val loadSamples = ArrayDeque<LoadSample>()

    /** Longest window a report can ask for; older samples are dropped. */
    private val LOAD_HISTORY_MS = 300_000L

    /**
     * Feed one telemetry frame. Called from the service's wheelData collector,
     * so this sees every frame rather than only the ones a report lands on.
     */
    fun recordTelemetry(data: WheelData) = synchronized(lock) {
        val now = data.timestamp
        loadSamples.addLast(
            LoadSample(now, data.pwm, data.current, data.voltage * data.current)
        )
        while (loadSamples.isNotEmpty() && now - loadSamples.first().t > LOAD_HISTORY_MS) {
            loadSamples.removeFirst()
        }
    }

    /** Clears the rolling window, e.g. when the wheel disconnects. */
    fun clearTelemetryHistory() = synchronized(lock) { loadSamples.clear() }

    /**
     * Average of one telemetry field over the last [windowMs], or null when the
     * window holds nothing usable, in which case the caller skips that report
     * row rather than speaking a made-up number.
     */
    private fun smoothed(windowMs: Long, pick: (LoadSample) -> Float): Float? = synchronized(lock) {
        if (loadSamples.isEmpty()) return null
        val now = loadSamples.last().t
        return Smoothing.trailingAverage(
            loadSamples.map { it.t to pick(it) }, now, windowMs
        )
    }

    /**
     * "Phase amps, 42 A" for a catalog metric with no report of its own: the
     * value a spoken question reads, in the tile's units. Null, and so left
     * out, when the wheel has sent nothing for it (a wheel with no tyre
     * sensor, a temperature slot it does not fill).
     */
    private fun catalogSentence(key: String, data: WheelData, settings: AppSettings, vctx: SpokenStrings): String? {
        val read = com.eried.eucplanet.voice.EXTRACTORS[key]
            ?: { w: WheelData -> com.eried.eucplanet.data.model.MetricRegistry.read(key, w) }
        val raw = read(data)?.takeIf { !it.isNaN() } ?: return null
        val spec = com.eried.eucplanet.data.model.MetricCatalog.all.firstOrNull { it.key == key } ?: return null
        val name = vctx.text(spec.spokenLabelRes ?: spec.labelRes)
        return "$name, ${formatCatalogValue(key, raw, settings, vctx)}"
    }

    private fun formatCatalogValue(key: String, raw: Float, settings: AppSettings, vctx: SpokenStrings): String =
        com.eried.eucplanet.data.model.MetricValueFormat.format(
            key = key,
            raw = raw,
            speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings),
            speedUnitLabel = vctx.text(com.eried.eucplanet.util.Units.speedUnitRes(
                com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
            )),
            tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings),
            tempUnitLabel = com.eried.eucplanet.util.Units.tempUnit(
                com.eried.eucplanet.util.Units.effectiveTempUnit(settings)
            ),
            distanceUnit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings),
            pressureUnit = com.eried.eucplanet.util.Units.effectivePressureUnit(settings),
        )

    /**
     * "Max speed, 42 km/h": a statistic over the stats window, from the same
     * history and the same arithmetic as the dashboard tiles and a spoken
     * question, in the phrasing a spoken question already uses.
     */
    private fun statSentence(pill: VoicePill, settings: AppSettings, vctx: SpokenStrings, preview: Boolean): String? {
        val key = VoiceReportPlan.statKey(pill.item) ?: return null
        val spec = com.eried.eucplanet.data.model.MetricCatalog.all.firstOrNull { it.key == key } ?: return null
        val name = vctx.text(spec.spokenLabelRes ?: spec.labelRes)
        val word = vctx.text(
            when (pill.stat) {
                VoicePill.Stat.MAX -> Res.string.voice_stat_max_terms
                VoicePill.Stat.MIN -> Res.string.voice_stat_min_terms
                VoicePill.Stat.AVG -> Res.string.voice_stat_avg_terms
                else -> Res.string.voice_stat_peak_terms
            }
        ).split(",").first().trim()
        val h = fullHistory()
        val samples = when (key) {
            "BATTERY" -> h.battery
            "TEMPERATURE" -> h.temperature
            "VOLTAGE" -> h.voltage
            "CURRENT" -> h.current
            "LOAD" -> h.load
            "SPEED" -> h.speed
            else -> h.extras[key].orEmpty()
        }
        val dashStat = when (pill.stat) {
            VoicePill.Stat.MAX -> com.eried.eucplanet.ui.settings.DashboardStat.MAX
            VoicePill.Stat.MIN -> com.eried.eucplanet.ui.settings.DashboardStat.MIN
            VoicePill.Stat.AVG -> com.eried.eucplanet.ui.settings.DashboardStat.AVG
            else -> com.eried.eucplanet.ui.settings.DashboardStat.SUSTAINED_PEAK
        }
        val raw = samples.takeIf { it.isNotEmpty() }?.let {
            com.eried.eucplanet.ui.settings.computeDashboardStatValue(dashStat, it, Float.NaN)
        }?.takeIf { !it.isNaN() }
            ?: return if (preview) vctx.text(Res.string.voice_answer_nodata, "$word $name") else null
        val value = formatCatalogValue(key, raw, settings, vctx)
        return vctx.text(Res.string.voice_stat_answer, word, name, value)
    }

    /**
     * "Estimated battery, 43%" for a report the metric catalog already knows.
     *
     * Name, value and units all come from the catalog, so the sentence needs
     * no format string of its own in twenty-three languages and cannot drift
     * from the tile. Null when the wheel has sent nothing usable: a row is
     * skipped rather than announcing a confident zero, the same rule the
     * hand-written load reports follow.
     */
    private fun metricSentence(
        report: VoiceReportPlan.MetricReport,
        data: WheelData,
        settings: AppSettings,
        vctx: SpokenStrings,
    ): String? {
        val raw = report.read(data)
        // NaN is always nothing. Zero is nothing only for the fields that use
        // it as their unset value: treating every zero as missing would
        // swallow a real 0% battery, which is the reading that matters most.
        if (raw.isNaN() || (report.blankAtZero && raw == 0f)) return null
        val spec = com.eried.eucplanet.data.model.MetricCatalog.all
            .firstOrNull { it.key == report.metricKey } ?: return null
        // The spoken name here, not the tile's: this one is said out loud,
        // and "Battery (est)" read aloud is "battery est".
        val name = vctx.text(spec.spokenLabelRes ?: spec.labelRes)
        val value = com.eried.eucplanet.data.model.MetricValueFormat.format(
            key = report.metricKey,
            raw = raw,
            speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings),
            speedUnitLabel = vctx.text(com.eried.eucplanet.util.Units.speedUnitRes(
                com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
            )),
            tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings),
            tempUnitLabel = com.eried.eucplanet.util.Units.tempUnit(
                com.eried.eucplanet.util.Units.effectiveTempUnit(settings)
            ),
            distanceUnit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings),
            pressureUnit = com.eried.eucplanet.util.Units.effectivePressureUnit(settings),
        )
        return "$name, $value"
    }

    fun buildReportParts(
        data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean,
        /**
         * Strings in the voice's language, which is not always the app's: an
         * English sentence read by a Russian voice is the mismatch a tester
         * reported for spoken questions.
         */
        vctx: SpokenStrings,
        /** One report only, for a spoken question. Null keeps the planned set. */
        only: String? = null,
        /** Settings preview: a statistic with no history says so. */
        preview: Boolean = false,
    ): List<String> {
        // Which reports, in what order: VoiceReportPlan, so the choice can be
        // tested without a TTS engine. Everything below is formatting.
        // Window the load-style reports average over. The advanced setting is in
        // samples for the ~1 Hz trip graphs, which makes it seconds here.
        val loadWindowMs = settings.smoothingWindowSamples.coerceAtLeast(1) * 1000L
        val parts = mutableListOf<String>()
        // The announcement is read out by a voice the rider picked, which is
        // not always the language the app is in. Resolving these against the
        // interface language meant an English sentence read aloud by a Russian
        // voice: the same mismatch a tester reported for spoken questions,
        // living here too and never noticed because both settings usually
        // agree.
        // A direct question names its own report and bypasses the plan, which
        // is about what an unprompted announcement contains.
        val planned = if (only != null) listOf(VoicePill(only)) else VoiceReportPlan.pills(settings, periodic)
        for (pill in planned) {
            if (pill.item == VoicePill.MESSAGE) {
                if (pill.text.isNotBlank()) parts.add(pill.text)
                continue
            }
            if (pill.stat != VoicePill.Stat.NOW) {
                statSentence(pill, settings, vctx, preview)?.let { parts.add(it) }
                continue
            }
            if (pill.item.startsWith(VoiceReportPlan.CATALOG_PREFIX)) {
                catalogSentence(pill.item.removePrefix(VoiceReportPlan.CATALOG_PREFIX), data, settings, vctx)
                    ?.let { parts.add(it) }
                continue
            }
            val item = pill.item
            run {

                // Convert each value to the user's display unit before
                // formatting. The "kilometers / miles" wording in
                // voice_trip_fmt also switches via the distance-imperial variant.
                val speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
                val distanceUnit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings)
                val tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings)
                val displaySpeed = com.eried.eucplanet.util.Units.speed(data.speed, speedUnit)
                val displayTemp = com.eried.eucplanet.util.Units.temperature(data.maxTemperature, tempUnit)
                val displayTrip = com.eried.eucplanet.util.Units.distance(data.tripDistance, distanceUnit)
                // The catalog-backed reports: name and value both come from
                // the same place the tile reads, so a spoken odometer cannot
                // disagree with the one on screen. Said as "name, value"
                // because that is how a spoken question is already answered,
                // and a rider hearing both should hear one voice.
                val extra = com.eried.eucplanet.service.VoiceReportPlan.extra(item)
                if (extra != null) {
                    metricSentence(extra, data, settings, vctx)?.let { parts.add(it) }
                    return@run
                }
                when (item) {
                    "Speed" -> parts.add(vctx.text(Res.string.voice_speed_fmt, "%.0f".format(displaySpeed)))
                    "Battery" -> parts.add(vctx.text(Res.string.voice_battery_fmt, data.batteryPercent))
                    "PhoneBattery" -> parts.add(vctx.text(Res.string.voice_phone_battery_fmt, phoneBattery()))
                    "Temp" -> parts.add(vctx.text(Res.string.voice_temp_fmt, "%.0f".format(displayTemp)))
                    // Load-style reports speak the recent average, not the
                    // instant reading. Falling back to the live value keeps the
                    // report working before any history has built up. A row is
                    // skipped entirely when the wheel reports nothing usable,
                    // rather than announcing a confident zero.
                    "PWM" -> {
                        val v = smoothed(loadWindowMs) { it.pwm } ?: data.pwm
                        if (!v.isNaN()) parts.add(vctx.text(Res.string.voice_load_fmt, "%.0f".format(v)))
                    }
                    "Current" -> {
                        val v = smoothed(loadWindowMs) { it.current } ?: data.current
                        if (!v.isNaN()) parts.add(vctx.text(Res.string.voice_current_fmt, "%.0f".format(abs(v))))
                    }
                    "Power" -> {
                        val v = smoothed(loadWindowMs) { it.powerW } ?: (data.voltage * data.current)
                        if (!v.isNaN()) parts.add(vctx.text(Res.string.voice_power_fmt, "%.0f".format(abs(v))))
                    }
                    "Distance" -> parts.add(vctx.text(
                        when (distanceUnit) {
                            "mi" -> Res.string.voice_trip_miles_fmt
                            "m" -> Res.string.voice_trip_meters_fmt
                            else -> Res.string.voice_trip_fmt
                        },
                        if (distanceUnit == "m") "%.0f".format(displayTrip)
                        else String.format(Locale.US, "%.1f", displayTrip)
                    ))
                    "Recording" -> parts.add(vctx.text(if (isRecording) Res.string.voice_recording_on else Res.string.voice_recording_off))
                    "Time" -> parts.add(vctx.text(Res.string.voice_time_fmt, vctx.timeNow()))
                    // The cue is pushed in by NavigationEngine when nav is live;
                    // null when there is nothing to say, in which case the row
                    // is silently skipped.
                    "Navigation" -> currentNavCue?.let { parts.add(it) }
                }
            }
        }
        return parts
    }
}
