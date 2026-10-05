package com.eried.eucplanet.widget

import android.content.Context
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.util.Units
import kotlin.math.abs

/**
 * One way to turn a [WidgetMetricType] into a number and a unit, shared by the
 * home screen widget and the Android Auto screen, so a stat reads the same on
 * both. Units are the rider's effective ones (see [Units.effectiveSpeedUnit]).
 */
object WidgetMetricFormat {

    /**
     * A slot's unit depends on what it shows and the rider's preferences,
     * never on whether a packet just arrived, so a caption keeps its unit
     * while telemetry is missing.
     */
    fun unit(context: Context, type: WidgetMetricType, speedUnit: String, distUnit: String, tempUnit: String): String =
        when (type) {
            WidgetMetricType.SPEED -> Units.speedUnit(context, speedUnit)
            WidgetMetricType.TRIP,
            WidgetMetricType.ODO -> Units.distanceUnit(distUnit)
            WidgetMetricType.BATTERY,
            WidgetMetricType.PWM,
            WidgetMetricType.PHONE_BATTERY -> "%"
            WidgetMetricType.VOLTAGE -> "V"
            WidgetMetricType.TEMP -> Units.tempUnit(tempUnit)
            WidgetMetricType.CURRENT,
            WidgetMetricType.PHASE_CURRENT -> "A"
            WidgetMetricType.TORQUE -> "Nm"
            WidgetMetricType.POWER -> "W"
            WidgetMetricType.WH_CONSUMED -> "Wh"
            WidgetMetricType.WH_PER_KM -> "Wh/" + Units.distanceUnit(distUnit)
            WidgetMetricType.RANGE_ESTIMATE -> Units.distanceUnit(distUnit)
        }

    fun value(
        type: WidgetMetricType, data: WheelData,
        speedUnit: String, distUnit: String, tempUnit: String, phoneBattery: Int,
    ): String = when (type) {
        WidgetMetricType.SPEED -> "%.0f".format(Units.speed(data.speed, speedUnit))
        WidgetMetricType.TRIP -> "%.1f".format(Units.distance(data.tripDistance, distUnit))
        WidgetMetricType.ODO -> "%.0f".format(Units.distance(data.totalDistance, distUnit))
        WidgetMetricType.BATTERY -> "${data.batteryPercent}"
        WidgetMetricType.VOLTAGE -> "%.0f".format(data.voltage)
        WidgetMetricType.TEMP -> "%.0f".format(Units.temperature(data.maxTemperature, tempUnit))
        WidgetMetricType.PWM -> if (data.pwm.isNaN()) "--" else "%.0f".format(data.pwm)
        WidgetMetricType.CURRENT -> "%.0f".format(abs(data.current))
        WidgetMetricType.TORQUE -> "%.1f".format(abs(data.torque))
        WidgetMetricType.PHASE_CURRENT -> "%.0f".format(abs(data.phaseCurrent))
        WidgetMetricType.POWER -> "%.0f".format(abs(data.voltage * data.current))
        WidgetMetricType.WH_CONSUMED -> "%.0f".format(data.whConsumed)
        // Both are NaN until the rolling window has enough distance; say
        // nothing rather than show a made-up zero.
        WidgetMetricType.WH_PER_KM ->
            if (data.whPerKmRecent.isNaN()) "--"
            else "%.0f".format(data.whPerKmRecent / Units.distance(1f, distUnit))
        WidgetMetricType.RANGE_ESTIMATE ->
            if (data.rangeKmEstimate.isNaN()) "--"
            else "%.0f".format(Units.distance(data.rangeKmEstimate, distUnit))
        WidgetMetricType.PHONE_BATTERY -> "$phoneBattery"
    }
}
