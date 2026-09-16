package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.VoiceReportSettings

/**
 * Which reports a spoken announcement contains, and in what order.
 *
 * Separated from the speaking itself because this is the part that decides
 * whether a rider hears their battery at all, while the rest is formatting. It
 * needs no Android, so it can be tested; VoiceService formats whatever comes
 * back from here.
 *
 * Two settings drive it: the saved order, which the rider drags into shape, and
 * a pair of enabled flags per report - one for the periodic announcement, one
 * for the button trigger, which riders configure differently (a trigger tends
 * to say everything, a periodic one only what matters while moving).
 */
object VoiceReportPlan {

    /**
     * A report whose value and name both come from the metric catalog.
     *
     * The eleven in [KNOWN] each have a hand-written sentence, a hand-written
     * toggle row and a hand-written branch in three files, which is why the
     * list stopped growing: adding one meant editing five places and a rider
     * asking for estimated battery was told it was not worth it. These carry
     * their own accessors instead, so a new one is a line here and nothing
     * else. Rule 13.
     *
     * @param metricKey the catalog entry that supplies the value, its spoken
     *   name and its formatting, so a spoken odometer reads in the same units
     *   as the tile beside it.
     */
    data class MetricReport(
        val key: String,
        val metricKey: String,
        val read: (com.eried.eucplanet.data.model.WheelData) -> Float,
        /**
         * Whether zero means "nothing sent yet" for this field.
         *
         * WheelData is split on this and the split is not cosmetic. Voltage
         * and the odometer default to 0f, so a zero there is a wheel that has
         * said nothing. Estimated battery, range and consumption default to
         * NaN, so a zero there is a real reading, and it is the one a rider
         * most needs to hear: "range, 0 miles" is the announcement that ends
         * a ride early on purpose rather than at the roadside.
         */
        val blankAtZero: Boolean,
        val get: (VoiceReportSettings, Boolean) -> Boolean,
        val set: (VoiceReportSettings, Boolean, Boolean) -> VoiceReportSettings,
    )

    /**
     * The catalog-backed reports, in the order they join the list.
     *
     * Only values the wheel itself sends. Ride time, average speed and trip
     * maximum are just as worth saying and are not here: they live in the trip
     * recorder rather than in a wheel packet, and reaching for them would put
     * a repository behind a function that today takes only the packet. Worth
     * doing, not worth smuggling into this change.
     */
    val EXTRA: List<MetricReport> = listOf(
        MetricReport(
            "BatteryEst", "BATTERY_ENVELOPE", { it.batteryEnvelope }, blankAtZero = false,
            { v, p -> if (p) v.periodicBatteryEst else v.triggerBatteryEst },
            { v, p, on -> if (p) v.copy(periodicBatteryEst = on) else v.copy(triggerBatteryEst = on) },
        ),
        MetricReport(
            "Range", "RANGE_ESTIMATE", { it.rangeKmEstimate }, blankAtZero = false,
            { v, p -> if (p) v.periodicRange else v.triggerRange },
            { v, p, on -> if (p) v.copy(periodicRange = on) else v.copy(triggerRange = on) },
        ),
        MetricReport(
            "Voltage", "VOLTAGE", { it.voltage }, blankAtZero = true,
            { v, p -> if (p) v.periodicVoltage else v.triggerVoltage },
            { v, p, on -> if (p) v.copy(periodicVoltage = on) else v.copy(triggerVoltage = on) },
        ),
        MetricReport(
            "Odometer", "ODOMETER", { it.totalDistance }, blankAtZero = true,
            { v, p -> if (p) v.periodicOdometer else v.triggerOdometer },
            { v, p, on -> if (p) v.copy(periodicOdometer = on) else v.copy(triggerOdometer = on) },
        ),
        MetricReport(
            "Consumption", "WH_PER_KM", { it.whPerKmRecent }, blankAtZero = false,
            { v, p -> if (p) v.periodicConsumption else v.triggerConsumption },
            { v, p, on -> if (p) v.copy(periodicConsumption = on) else v.copy(triggerConsumption = on) },
        ),
    )

    /** The catalog-backed report for a key, or null for the hand-written ones. */
    fun extra(key: String): MetricReport? = EXTRA.firstOrNull { it.key == key }

    // Declared after EXTRA on purpose: an object initialises its properties in
    // source order, and a KNOWN that read EXTRA above it would read an empty
    // list and silently drop every catalog-backed report.
    /**
     * Every report the app can speak, in the order a rider gets before they
     * touch anything.
     */
    val KNOWN = listOf(
        "Speed", "Battery", "PhoneBattery", "Temp", "PWM",
        "Current", "Power", "Distance", "Recording", "Time", "Navigation",
    ) + EXTRA.map { it.key }

    /**
     * The saved order, cleaned up: unknown names dropped, and any report the
     * rider's saved order predates appended at the end.
     *
     * The appending matters. PhoneBattery arrived after riders had saved an
     * order, and without this their announcement would silently never gain it -
     * the setting would be on, the report simply absent.
     */
    fun order(saved: String): List<String> {
        val kept = saved.split(",").map { it.trim() }.filter { it in KNOWN }.distinct()
        return kept + KNOWN.filter { it !in kept }
    }

    /**
     * Whether [item] is switched on for this kind of announcement.
     *
     * Read from [AppSettings.voiceReports] rather than the flat aliases, since
     * that nested block is where the values actually live.
     */
    fun isEnabled(item: String, s: AppSettings, periodic: Boolean): Boolean {
        val v = s.voiceReports
        extra(item)?.let { return it.get(v, periodic) }
        return if (periodic) when (item) {
            "Speed" -> v.periodicSpeed
            "Battery" -> v.periodicBattery
            "PhoneBattery" -> v.periodicPhoneBattery
            "Temp" -> v.periodicTemp
            "PWM" -> v.periodicPwm
            "Current" -> v.periodicCurrent
            "Power" -> v.periodicPower
            "Distance" -> v.periodicDistance
            "Recording" -> v.periodicRecording
            "Time" -> v.periodicTime
            "Navigation" -> v.periodicNavigation
            else -> false
        } else when (item) {
            "Speed" -> v.triggerSpeed
            "Battery" -> v.triggerBattery
            "PhoneBattery" -> v.triggerPhoneBattery
            "Temp" -> v.triggerTemp
            "PWM" -> v.triggerPwm
            "Current" -> v.triggerCurrent
            "Power" -> v.triggerPower
            "Distance" -> v.triggerDistance
            "Recording" -> v.triggerRecording
            "Time" -> v.triggerTime
            "Navigation" -> v.triggerNavigation
            else -> false
        }
    }

    /** The reports to speak, in order. */
    fun items(s: AppSettings, periodic: Boolean): List<String> =
        order(s.voiceReportOrder).filter { isEnabled(it, s, periodic) }
}
