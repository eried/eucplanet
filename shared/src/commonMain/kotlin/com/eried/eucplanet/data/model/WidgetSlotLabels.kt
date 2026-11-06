package com.eried.eucplanet.data.model

import com.eried.eucplanet.resources.*
import org.jetbrains.compose.resources.StringResource

// Picker labels for the shared widget and notification registries.

val WidgetMetricType.pickerLabel: StringResource
    get() = when (this) {
        WidgetMetricType.SPEED -> Res.string.widget_metric_speed
        WidgetMetricType.TRIP -> Res.string.widget_metric_trip
        WidgetMetricType.ODO -> Res.string.widget_metric_odo
        WidgetMetricType.BATTERY -> Res.string.widget_metric_battery
        WidgetMetricType.VOLTAGE -> Res.string.widget_metric_voltage
        WidgetMetricType.TEMP -> Res.string.widget_metric_temp
        WidgetMetricType.PWM -> Res.string.widget_metric_pwm
        WidgetMetricType.CURRENT -> Res.string.widget_metric_current
        WidgetMetricType.TORQUE -> Res.string.widget_metric_torque
        WidgetMetricType.PHASE_CURRENT -> Res.string.widget_metric_phase_current
        WidgetMetricType.POWER -> Res.string.widget_metric_power
        WidgetMetricType.WH_CONSUMED -> Res.string.widget_metric_wh_consumed
        WidgetMetricType.WH_PER_KM -> Res.string.widget_metric_wh_per_km
        WidgetMetricType.RANGE_ESTIMATE -> Res.string.widget_metric_range
        WidgetMetricType.PHONE_BATTERY -> Res.string.widget_metric_phone_battery
    }

val WidgetActionType.pickerLabel: StringResource
    get() = when (this) {
        WidgetActionType.HORN -> Res.string.notif_action_horn
        WidgetActionType.LIGHT -> Res.string.notif_action_light
        WidgetActionType.LOCK -> Res.string.notif_action_lock
        WidgetActionType.RECORD -> Res.string.notif_action_record
        WidgetActionType.VOICE -> Res.string.widget_action_voice
        WidgetActionType.DISCONNECT -> Res.string.notif_action_disconnect
    }

val NotificationActionType.pickerLabel: StringResource
    get() = when (this) {
        NotificationActionType.STOP_ALL -> Res.string.notif_action_stop_all
        NotificationActionType.LOCK -> Res.string.notif_action_lock
        NotificationActionType.LIGHT -> Res.string.notif_action_light
        NotificationActionType.RECORD -> Res.string.notif_action_record
        NotificationActionType.HORN -> Res.string.notif_action_horn
        NotificationActionType.DISCONNECT -> Res.string.notif_action_disconnect
        NotificationActionType.STOP_NAV -> Res.string.notif_action_stop_nav
    }
