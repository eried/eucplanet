package com.eried.eucplanet.widget

import android.content.Context
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.util.speedUnit

/**
 * One way to turn a [WidgetMetricType] into a number and a unit, shared by the
 * home screen widget and the Android Auto screen, so a stat reads the same on
 * both. Units are the rider's effective ones (see [Units.effectiveSpeedUnit]).
 * The rules live in the shared [WidgetContent], which the iPhone's widget
 * reads too; this adds the speed unit in the rider's language.
 */
object WidgetMetricFormat {

    fun unit(context: Context, type: WidgetMetricType, speedUnit: String, distUnit: String, tempUnit: String): String =
        WidgetContent.unit(type, Units.speedUnit(context, speedUnit), distUnit, tempUnit)

    fun value(
        type: WidgetMetricType, data: WheelData,
        speedUnit: String, distUnit: String, tempUnit: String, phoneBattery: Int,
    ): String = WidgetContent.value(type, data, speedUnit, distUnit, tempUnit, phoneBattery)
}
