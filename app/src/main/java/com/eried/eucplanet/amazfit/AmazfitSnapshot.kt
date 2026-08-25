package com.eried.eucplanet.amazfit

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.util.Units

/**
 * Builds the `/state` frame. Field for field the same snapshot
 * `GarminBridge.encodeSnapshot` sends over Connect IQ, so the three watch
 * dials render from identical data; the only additions are [AmazfitKeys.POLL_MS]
 * (how soon the watch should ask again) and [AmazfitKeys.EVENTS] (the one-shots
 * queued since the previous poll).
 *
 * Pure Kotlin: everything Android-flavoured (battery level, theme colours,
 * navigation state, GPS) arrives pre-resolved from [AmazfitBridge], which
 * keeps this testable on the JVM.
 */
internal object AmazfitSnapshot {

    /** Navigation mirror, already folded with the rider's watch opt-in and the
     *  phone popup's visibility, so the watch just shows or hides on [show]. */
    data class Nav(
        val show: Boolean,
        val angleDeg: Float,
        val primary: String,
        val distance: String,
        val arrived: Boolean
    )

    fun encode(
        data: WheelData,
        connected: Boolean,
        wheelName: String?,
        maxSpeedKmh: Float,
        settings: AppSettings,
        speedMultiplier: Float,
        phoneBatteryPercent: Int,
        accentArgb: String,
        gps: Pair<Float, String>?,
        nav: Nav,
        events: List<Map<String, Any>>,
        nowMs: Long
    ): Map<String, Any> {
        val speedUnit = Units.effectiveSpeedUnit(settings)
        val distanceUnit = Units.effectiveDistanceUnit(settings)
        val tempUnit = Units.effectiveTempUnit(settings)
        return buildMap {
            put(AmazfitKeys.KIND, AmazfitKeys.KIND_STATE)
            put(AmazfitKeys.CONNECTED, connected)
            put(AmazfitKeys.WHEEL_NAME, wheelName ?: "")
            put(AmazfitKeys.SPEED, data.speed * speedMultiplier)
            put(AmazfitKeys.BATTERY, data.batteryPercent)
            put(AmazfitKeys.PHONE_BATT, phoneBatteryPercent)
            put(AmazfitKeys.VOLTAGE, data.voltage)
            put(AmazfitKeys.CURRENT, data.current)
            put(AmazfitKeys.PWM, data.pwm)
            put(AmazfitKeys.TEMP, data.maxTemperature)
            put(AmazfitKeys.TRIP_KM, data.tripDistance)
            put(AmazfitKeys.TORQUE, data.torque)
            put(AmazfitKeys.LIGHT_ON, data.lightOn)
            put(AmazfitKeys.MAX_SPEED, maxSpeedKmh)
            put(AmazfitKeys.HAS_HORN, true)
            put(AmazfitKeys.HAS_LIGHT, true)
            put(AmazfitKeys.UNIT_SPEED, speedUnit)
            put(AmazfitKeys.UNIT_DISTANCE, distanceUnit)
            put(AmazfitKeys.UNIT_TEMP, tempUnit)
            put(AmazfitKeys.IMPERIAL, speedUnit == "mph")
            put(AmazfitKeys.ACCENT, accentArgb)
            put(AmazfitKeys.OPT_KEEP_ON, settings.watchKeepScreenOn)
            put(AmazfitKeys.OPT_SHOW_WHEEL_BATT, settings.watchShowWheelBattery)
            put(AmazfitKeys.OPT_SHOW_PHONE_BATT, settings.watchShowPhoneBattery)
            put(AmazfitKeys.OPT_SHOW_WATCH_BATT, settings.watchShowWatchBattery)
            put(AmazfitKeys.OPT_PWM_DISPLAY, settings.watchPwmDisplay)
            put(AmazfitKeys.OPT_SHOW_SPEED_UNIT, settings.watchShowSpeedUnit)
            put(AmazfitKeys.OPT_PRIORITIZE_PWM, settings.watchPrioritizePwm)
            put(AmazfitKeys.OPT_DIAL_ROTATION, settings.watchDialRotationDeg)
            put(AmazfitKeys.OPT_GAUGE_BAND, settings.showGaugeColorBand)
            put(AmazfitKeys.OPT_GAUGE_ORANGE, settings.gaugeOrangeThresholdPct)
            put(AmazfitKeys.OPT_GAUGE_RED, settings.gaugeRedThresholdPct)
            put(AmazfitKeys.OPT_CLOSE_ON_EXIT, settings.watchCloseOnExit)
            put(AmazfitKeys.STEM1_CLICK, settings.watchStem1Click)
            put(AmazfitKeys.STEM1_HOLD, settings.watchStem1Hold)
            put(AmazfitKeys.STEM2_CLICK, settings.watchStem2Click)
            put(AmazfitKeys.STEM2_HOLD, settings.watchStem2Hold)
            put(AmazfitKeys.SCREEN1_CLICK, settings.watchScreen1Click)
            put(AmazfitKeys.SCREEN1_HOLD, settings.watchScreen1Hold)
            put(AmazfitKeys.SCREEN2_CLICK, settings.watchScreen2Click)
            put(AmazfitKeys.SCREEN2_HOLD, settings.watchScreen2Hold)
            put(AmazfitKeys.HAPTIC_ON_ACTION, settings.watchHapticOnAction)
            // Same -1 sentinel as Garmin for "nothing to show"; JSON has no NaN.
            put(AmazfitKeys.GPS_SPEED, gps?.first ?: -1f)
            put(AmazfitKeys.GPS_SOURCE, gps?.second ?: "")
            put(AmazfitKeys.NAV_ACTIVE, nav.show)
            put(AmazfitKeys.NAV_ANGLE, nav.angleDeg)
            put(AmazfitKeys.NAV_PRIMARY, nav.primary)
            put(AmazfitKeys.NAV_DISTANCE, nav.distance)
            put(AmazfitKeys.NAV_ARRIVED, nav.arrived)
            put(AmazfitKeys.POLL_MS, amazfitPollIntervalMsFor(settings.watchUpdateRate))
            put(AmazfitKeys.EVENTS, events)
            put(AmazfitKeys.TIMESTAMP, nowMs)
        }
    }

    /** Every key the watch app reads. The drift-guard test asserts [encode]
     *  emits all of them, so a field cannot silently vanish from the wire. */
    val WATCH_KEYS: List<String> = listOf(
        "k", "c", "n", "s", "b", "b2", "v", "i", "p", "t", "tr", "tq", "l", "ms", "ch", "cl",
        "us", "ud", "ut", "im", "ac",
        "wko", "wsb", "wpb", "wwb", "wpd", "wsu", "wpp", "wrot", "wgb", "wgo", "wgr", "wce",
        "s1c", "s1h", "s2c", "s2h", "b1c", "b1h", "b2c", "b2h", "hap",
        "gs", "gsr", "na", "ng", "np", "nd", "nar",
        "pi", "ev", "ts"
    )
}
