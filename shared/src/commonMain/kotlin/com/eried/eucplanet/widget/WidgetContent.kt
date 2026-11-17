package com.eried.eucplanet.widget

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.data.model.pickerLabel
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.util.Units
import kotlin.math.abs
import org.jetbrains.compose.resources.StringResource

/** A widget's readings: one value and one caption per configured slot. */
data class WidgetMetrics(val values: List<String>, val captions: List<String>)

/**
 * What the home screen widget says, for both phones: Android's EucWidget
 * (painted by WheelService) and the iPhone's WidgetKit widget (IosWidgets)
 * read their numbers, units, captions and button labels here, so a slot
 * reads the same on both. The Android Auto screen shares the numbers too.
 */
object WidgetContent {
    /** A slot with no reading to show. */
    const val DASH = "--"

    /**
     * A slot's unit depends on what it shows and the rider's preferences,
     * never on whether a packet just arrived, so a caption keeps its unit
     * while telemetry is missing. [speedUnitLabel] is the speed unit in the
     * rider's language (Units.speedUnitRes).
     */
    fun unit(type: WidgetMetricType, speedUnitLabel: String, distUnit: String, tempUnit: String): String =
        when (type) {
            WidgetMetricType.SPEED -> speedUnitLabel
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
        WidgetMetricType.PWM -> if (data.pwm.isNaN()) DASH else "%.0f".format(data.pwm)
        WidgetMetricType.CURRENT -> "%.0f".format(abs(data.current))
        WidgetMetricType.TORQUE -> "%.1f".format(abs(data.torque))
        WidgetMetricType.PHASE_CURRENT -> "%.0f".format(abs(data.phaseCurrent))
        WidgetMetricType.POWER -> "%.0f".format(abs(data.voltage * data.current))
        WidgetMetricType.WH_CONSUMED -> "%.0f".format(data.whConsumed)
        // Both are NaN until the rolling window has enough distance; say
        // nothing rather than show a made-up zero.
        WidgetMetricType.WH_PER_KM ->
            if (data.whPerKmRecent.isNaN()) DASH
            else "%.0f".format(data.whPerKmRecent / Units.distance(1f, distUnit))
        WidgetMetricType.RANGE_ESTIMATE ->
            if (data.rangeKmEstimate.isNaN()) DASH
            else "%.0f".format(Units.distance(data.rangeKmEstimate, distUnit))
        WidgetMetricType.PHONE_BATTERY -> "$phoneBattery"
    }

    /**
     * The readings for the stored [csv] of metric keys, one per slot. [data]
     * null is the disconnected state: dashes, with the captions kept. The
     * unit rides in the caption so the number itself stays large.
     * [phoneBattery] is asked only when a slot shows it, and [text] reads a
     * label in the rider's language (inline, so each phone passes its own way
     * of reading one).
     */
    inline fun metrics(
        csv: String,
        data: WheelData?,
        speedUnit: String,
        speedUnitLabel: String,
        distUnit: String,
        tempUnit: String,
        phoneBattery: () -> Int,
        text: (StringResource) -> String,
    ): WidgetMetrics {
        val keys = WidgetMetricType.slots(csv)
        val values = ArrayList<String>(keys.size)
        val captions = ArrayList<String>(keys.size)
        for (key in keys) {
            val type = WidgetMetricType.byKey(key)
            if (type == null) {
                values.add(DASH)
                captions.add("")
                continue
            }
            val unit = unit(type, speedUnitLabel, distUnit, tempUnit)
            values.add(
                if (data == null) DASH
                else value(type, data, speedUnit, distUnit, tempUnit, if (type == WidgetMetricType.PHONE_BATTERY) phoneBattery() else 0)
            )
            captions.add(text(type.pickerLabel) + if (unit.isBlank()) "" else " ($unit)")
        }
        return WidgetMetrics(values, captions)
    }

    /**
     * What a widget button says, or null for an empty slot.
     *
     * A button names the next tap, so it is "Light on" or "Light off" and
     * never both; the settings dropdowns name the slot (pickerLabel). The
     * defaults are the resting state, which is also the truth before a wheel
     * connects: nothing is lit, locked or recording.
     */
    fun buttonLabel(
        key: String,
        lightOn: Boolean = false,
        locked: Boolean = false,
        recording: Boolean = false,
        voiceOn: Boolean = false,
    ): StringResource? = when (WidgetActionType.byKey(key)) {
        null -> null
        WidgetActionType.HORN -> Res.string.notif_btn_horn
        WidgetActionType.LIGHT -> if (lightOn) Res.string.notif_btn_light_off else Res.string.notif_btn_light_on
        WidgetActionType.LOCK -> if (locked) Res.string.notif_btn_unlock else Res.string.notif_btn_lock
        WidgetActionType.RECORD -> if (recording) Res.string.notif_btn_stop_record else Res.string.notif_btn_record
        WidgetActionType.VOICE -> if (voiceOn) Res.string.widget_voice_off else Res.string.widget_voice_on
        WidgetActionType.DISCONNECT -> Res.string.notif_btn_disconnect
    }

    /**
     * Whether pressing this button can do anything right now.
     *
     * Horn, light, lock and disconnect all send a command to the wheel, so
     * with nothing connected there is nobody to send it to.
     *
     * VOICE and RECORD are not wheel commands. Voice toggles the periodic
     * announcements, which is an app setting. Recording writes a trip from
     * whatever the phone can see, so with no wheel it still produces a
     * GPS-only ride, and the Recorder screen lets a rider start one exactly
     * that way. Greying these out would have the widget contradict the app.
     */
    fun enabled(key: String, connected: Boolean): Boolean =
        connected || WidgetActionType.byKey(key) in ALWAYS_AVAILABLE

    /** Actions that do not need a wheel on the other end. */
    private val ALWAYS_AVAILABLE = setOf(WidgetActionType.VOICE, WidgetActionType.RECORD)
}
