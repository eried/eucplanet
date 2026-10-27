package com.eried.eucplanet.data.model

import androidx.annotation.StringRes
import com.eried.eucplanet.R

// Picker labels for the shared widget registries. They stay in :app while the
// strings are Android resources.

@get:StringRes
val WidgetMetricType.pickerLabel: Int
    get() = when (this) {
        WidgetMetricType.SPEED -> R.string.widget_metric_speed
        WidgetMetricType.TRIP -> R.string.widget_metric_trip
        WidgetMetricType.ODO -> R.string.widget_metric_odo
        WidgetMetricType.BATTERY -> R.string.widget_metric_battery
        WidgetMetricType.VOLTAGE -> R.string.widget_metric_voltage
        WidgetMetricType.TEMP -> R.string.widget_metric_temp
        WidgetMetricType.PWM -> R.string.widget_metric_pwm
        WidgetMetricType.CURRENT -> R.string.widget_metric_current
        WidgetMetricType.TORQUE -> R.string.widget_metric_torque
        WidgetMetricType.PHASE_CURRENT -> R.string.widget_metric_phase_current
        WidgetMetricType.POWER -> R.string.widget_metric_power
        WidgetMetricType.WH_CONSUMED -> R.string.widget_metric_wh_consumed
        WidgetMetricType.WH_PER_KM -> R.string.widget_metric_wh_per_km
        WidgetMetricType.RANGE_ESTIMATE -> R.string.widget_metric_range
        WidgetMetricType.PHONE_BATTERY -> R.string.widget_metric_phone_battery
    }

@get:StringRes
val WidgetActionType.pickerLabel: Int
    get() = when (this) {
        WidgetActionType.HORN -> R.string.notif_action_horn
        WidgetActionType.LIGHT -> R.string.notif_action_light
        WidgetActionType.LOCK -> R.string.notif_action_lock
        WidgetActionType.RECORD -> R.string.notif_action_record
        WidgetActionType.VOICE -> R.string.widget_action_voice
        WidgetActionType.DISCONNECT -> R.string.notif_action_disconnect
    }
