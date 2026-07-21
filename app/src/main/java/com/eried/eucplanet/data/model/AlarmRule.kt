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
     * Pitch modulation FACTOR, stored x100 (100 = 1.0x = unchanged, the default).
     * The pitch ramps toward [beepFrequency] * factor as the value pushes past the
     * threshold. Above 1.0x ramps the pitch up; below 1.0x (down to 0.1x) reduces
     * it. See [com.eried.eucplanet.service.AlarmLogic.modulatedBeepHz].
     */
    val beepModulation: Int = 100,
    /**
     * Silence in ms between repeated beeps AND between the beep and the voice.
     * 0 = back-to-back (lets a Many + cooldown-0 alarm sound near-continuous).
     */
    val beepGapMs: Int = 100,
    /**
     * Attack/release ramp as a percent of the beep duration (0..50). 0 = crisp
     * (short click-guard only), 50 = a pure raised-cosine swell with no flat top.
     * Higher = smoother "up and down"; with gap 0 the swells butt together into a
     * continuous undulating tone. See [com.eried.eucplanet.service.TonePlayer].
     */
    val beepTransitionPct: Int = 12,
    /**
     * Oscillator shape: 0=Sine (pure, default), 1=Triangle, 2=Square, 3=Saw,
     * 4=FM (metallic). Drives the timbre before [beepEffect] is applied. See
     * [com.eried.eucplanet.service.TonePlayer].
     */
    val beepWaveform: Int = 0,
    /**
     * Post effect on the tone: 0=None, 1=Drive (waveshaper grit), 2=Sweep
     * (resonant low-pass whose cutoff tracks the pitch), 3=Crush (bitcrush
     * lo-fi). Applied on top of [beepWaveform].
     */
    val beepEffect: Int = 0,
    /**
     * Base beep loudness as a percent of the phone's system media volume (the
     * hard ceiling). 100 = as loud as the app plays it today; lower = quieter.
     */
    val beepVolume: Int = 100,
    /**
     * Volume modulation FACTOR, stored x100 (100 = 1.0x = constant, the default).
     * The volume ramps toward [beepVolume] * factor as the value pushes past the
     * threshold. Above 1.0x ramps it louder (capped at 100); below 1.0x reduces it.
     */
    val beepVolumeModulation: Int = 100,
    /** Legacy (unused since modulation became a base*factor multiplier). */
    val beepModulationReachPct: Int = 50,
    /** Legacy (unused since modulation became a base*factor multiplier). */
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
     * Battery percent of the paired external GPS box (RaceBox / Dragy). Pair
     * with LESS_THAN: "warn when the GPS box drops below 15%". Evaluated off
     * [com.eried.eucplanet.data.repository.ExternalGpsRepository] via
     * [com.eried.eucplanet.service.AlarmEngine.evaluateExternalGps], not the
     * wheel loop, so it fires even with no wheel connected.
     */
    EXTERNAL_GPS_BATTERY(R.string.alarm_metric_external_gps_battery, "%"),

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
