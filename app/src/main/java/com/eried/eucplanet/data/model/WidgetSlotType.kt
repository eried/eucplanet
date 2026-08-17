package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import com.eried.eucplanet.R

/**
 * What the home screen widget can show and do, configured the same way the
 * ongoing notification's action buttons are.
 *
 * Deliberately the same shape as [NotificationActionType]: a stable [key] that
 * is what gets persisted, a picker label, a fixed number of slots, a synthetic
 * NONE for an empty one, and CSV helpers that pad and truncate. A rider who has
 * configured the notification already knows how this works, and the two
 * registries can be read side by side.
 */
enum class WidgetMetricType(val key: String, @StringRes val pickerLabel: Int) {
    SPEED("SPEED", R.string.widget_metric_speed),
    TRIP("TRIP", R.string.widget_metric_trip),
    ODO("ODO", R.string.widget_metric_odo),
    BATTERY("BATTERY", R.string.widget_metric_battery),
    VOLTAGE("VOLTAGE", R.string.widget_metric_voltage),
    TEMP("TEMP", R.string.widget_metric_temp),
    PWM("PWM", R.string.widget_metric_pwm),
    CURRENT("CURRENT", R.string.widget_metric_current),
    POWER("POWER", R.string.widget_metric_power),
    WH_CONSUMED("WH_CONSUMED", R.string.widget_metric_wh_consumed),
    WH_PER_KM("WH_PER_KM", R.string.widget_metric_wh_per_km),
    RANGE_ESTIMATE("RANGE_ESTIMATE", R.string.widget_metric_range),
    PHONE_BATTERY("PHONE_BATTERY", R.string.widget_metric_phone_battery);

    companion object {
        /** The layout has room for three readable numbers across. */
        const val SLOTS = 3
        const val NONE = "NONE"
        const val DEFAULT_KEYS = "SPEED,TRIP,BATTERY"

        fun byKey(key: String): WidgetMetricType? = entries.firstOrNull { it.key == key }

        /** The stored CSV as exactly [SLOTS] ordered keys, padding and truncating. */
        fun slots(csv: String): List<String> {
            val parts = csv.split(",").map { it.trim() }
            return (0 until SLOTS).map { i -> parts.getOrNull(i)?.ifEmpty { NONE } ?: NONE }
        }
    }
}

/**
 * The widget's tap targets.
 *
 * Mostly the same set the notification offers, so one wheel command has one
 * meaning across the app, plus VOICE, which only makes sense somewhere with
 * room for a persistent on/off label.
 */
enum class WidgetActionType(val key: String, @StringRes val pickerLabel: Int) {
    HORN("HORN", R.string.notif_action_horn),
    LIGHT("LIGHT", R.string.notif_action_light),
    LOCK("LOCK", R.string.notif_action_lock),
    RECORD("RECORD", R.string.notif_action_record),
    VOICE("VOICE", R.string.widget_action_voice),
    DISCONNECT("DISCONNECT", R.string.notif_action_disconnect);

    companion object {
        /**
         * Three buttons across, matching the three readings above them.
         *
         * An older build had two. A stored two-key CSV still loads: [slots]
         * pads the missing one with NONE, and an empty slot collapses, so a
         * widget already on someone's launcher keeps the layout they chose.
         */
        const val SLOTS = 3
        const val NONE = "NONE"
        const val DEFAULT_KEYS = "HORN,LIGHT,VOICE"

        fun byKey(key: String): WidgetActionType? = entries.firstOrNull { it.key == key }

        fun slots(csv: String): List<String> {
            val parts = csv.split(",").map { it.trim() }
            return (0 until SLOTS).map { i -> parts.getOrNull(i)?.ifEmpty { NONE } ?: NONE }
        }
    }
}

/**
 * Home screen widget layout, nested to keep AppSettings' copy() under the
 * JVM's 255 parameter slots. See [VoiceReportSettings] for why that matters.
 */
data class WidgetSettings(
    /** CSV of [WidgetMetricType] keys, one per reading in the big widget. */
    val metrics: String = WidgetMetricType.DEFAULT_KEYS,
    /** CSV of [WidgetActionType] keys, one per button in the big widget. */
    val actions: String = WidgetActionType.DEFAULT_KEYS,
    /**
     * CSV of [WidgetActionType] keys for the standalone button widgets,
     * independent of [actions].
     *
     * They were the same list at first, which forced a compromise: the button
     * row inside a dashboard widget and a bare button sitting alone on a home
     * screen are different jobs. A rider may want the horn on its own by the
     * dock while the big widget carries light, lock and record.
     */
    val standaloneActions: String = WidgetActionType.DEFAULT_KEYS,
)
