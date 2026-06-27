package com.eried.eucplanet.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eried.eucplanet.R

@Entity(tableName = "alarm_rules")
data class AlarmRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,

    // Condition
    val metric: String = AlarmMetric.SPEED.name,
    val comparator: String = AlarmComparator.GREATER_EQUAL.name,
    val threshold: Float = 30f,

    // Beep action
    val beepEnabled: Boolean = false,
    val beepFrequency: Int = 1000,   // Hz: 400-3000 (the pitch AT the threshold)
    val beepDurationMs: Int = 300,   // per beep
    val beepCount: Int = 1,          // 1=single, 2=double, 3=triple
    /**
     * Pitch modulation strength, as a percent. 0 = Fixed (always
     * [beepFrequency]). >0 = the pitch starts at [beepFrequency] when the value
     * is at the threshold and climbs as the value pushes past it; at 50% of the
     * threshold past it the pitch has risen by this percent of the base (100% =
     * doubles), clamped to a 4 kHz ceiling. Computed once per fire (each beep in
     * one fire is a single pitch). See [com.eried.eucplanet.service.AlarmLogic.modulatedBeepHz].
     */
    val beepModulation: Int = 0,
    /**
     * Silence in ms between repeated beeps AND between the beep and the voice.
     * 0 = back-to-back (lets a Many + cooldown-0 alarm sound near-continuous).
     */
    val beepGapMs: Int = 100,
    /**
     * Base beep loudness as a percent of the phone's system media volume (the
     * hard ceiling). 100 = as loud as the app plays it today; lower = quieter.
     */
    val beepVolume: Int = 100,
    /**
     * Volume modulation strength, as a percent. 0 = constant [beepVolume]. >0 =
     * the volume ramps from [beepVolume] up toward 100% (system) as the value
     * worsens. See [com.eried.eucplanet.service.AlarmLogic.modulatedVolumePct].
     */
    val beepVolumeModulation: Int = 0,
    /**
     * How far past the threshold (as a percent of the threshold) the PITCH
     * modulation reaches full strength. 50 = peaks at 50% past the threshold.
     * X position of the pitch knee in the graph editor. Default 50.
     */
    val beepModulationReachPct: Int = 50,
    /**
     * How far past the threshold (as a percent of the threshold) the VOLUME
     * modulation reaches full strength -- the X of the volume knee, independent
     * of the pitch knee so the two curves can peak at different points. Default 50.
     */
    val beepVolumeReachPct: Int = 50,

    // Voice action
    val voiceEnabled: Boolean = false,
    val voiceText: String = "Warning! {metric} at {value}",

    // Vibrate action
    val vibrateEnabled: Boolean = false,
    val vibrateDurationMs: Int = 500,
    /**
     * Where the buzz fires when [vibrateEnabled] is true: "PHONE", "WATCH",
     * or "BOTH". Defaults to BOTH so a rider gets the haptic on whichever
     * device they're paying attention to, if no watch is paired, the WATCH
     * branch in [com.eried.eucplanet.wear.WatchVibrator] silently no-ops.
     * The storage key stays "BOTH" for backup/sync compatibility even though
     * the UI label is "All".
     */
    val vibrateTarget: String = "BOTH",

    // Timing
    val cooldownSeconds: Int = 5,
    /**
     * "Many" (true, the default) re-alerts every [cooldownSeconds] for as long
     * as the value stays past the threshold; "Once" (false) alerts a single
     * time per crossing. Default is Many so a sustained danger (held overspeed,
     * high PWM) keeps reminding the rider rather than beeping once and going
     * quiet. Only affects newly created rules; existing rules keep their saved
     * value. See [com.eried.eucplanet.service.AlarmLogic.shouldFire].
     */
    val repeatWhileActive: Boolean = true,

    /**
     * Predictive lead time in milliseconds. 0 (default) = fire the instant the
     * threshold is crossed, exactly as before. When > 0, the engine also fires
     * early if the recent trend projects the value across the threshold within
     * this window (e.g. 1000 = "warn me ~1 s before PWM is about to hit 95%").
     * Only triggers while moving toward the threshold; a flat or retreating
     * value never fires predictively. See [com.eried.eucplanet.service.AlarmEngine].
     */
    val leadTimeMs: Int = 0
)

enum class AlarmMetric(
    val labelRes: Int,
    val unit: String,
    /** Name spoken by voice alarms, defaults to the on-screen label. */
    val voiceLabelRes: Int = labelRes
) {
    SPEED(R.string.alarm_metric_speed, "km/h"),
    BATTERY(R.string.alarm_metric_battery, "%"),
    TEMPERATURE(R.string.alarm_metric_temperature, "°C"),
    PWM(R.string.alarm_metric_pwm, "%", R.string.alarm_metric_pwm_voice),
    VOLTAGE(R.string.alarm_metric_voltage, "V"),
    CURRENT(R.string.alarm_metric_current, "A"),

    /**
     * Distance in metres to the closest tracked vehicle from the rear-view
     * radar. Pair with the LESS_THAN comparator: "trigger when the closest
     * car is closer than 40 m". Only fires when at least one threat is
     * present in the latest frame, an empty frame is treated as "no value"
     * rather than "0 metres" so a clear lane doesn't keep tripping the rule.
     */
    RADAR_DISTANCE(R.string.alarm_metric_radar_distance, "m"),

    /**
     * Approach speed in km/h of the fastest closing vehicle. Pair with
     * GREATER_EQUAL: "trigger when a car is closing at 60 km/h or more".
     */
    RADAR_APPROACH_SPEED(R.string.alarm_metric_radar_approach, "km/h")
}

enum class AlarmComparator(val labelRes: Int, val symbol: String) {
    GREATER_EQUAL(R.string.alarm_cmp_ge, "≥"),
    LESS_THAN(R.string.alarm_cmp_lt, "<");

    companion object {
        // Map legacy stored strings to the reduced set; safe fallback to GREATER_EQUAL.
        fun parse(raw: String?): AlarmComparator = when (raw) {
            "GREATER_EQUAL", "GREATER_THAN" -> GREATER_EQUAL
            "LESS_THAN", "LESS_EQUAL" -> LESS_THAN
            else -> GREATER_EQUAL
        }
    }
}
