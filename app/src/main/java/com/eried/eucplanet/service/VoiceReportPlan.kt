package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings

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
     * Every report the app can speak, in the order a rider gets before they
     * touch anything.
     */
    val KNOWN = listOf(
        "Speed", "Battery", "PhoneBattery", "Temp", "PWM",
        "Current", "Power", "Distance", "Recording", "Time", "Navigation",
    )

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
