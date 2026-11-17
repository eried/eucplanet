package com.eried.eucplanet.widget

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the home screen widget says on both phones. */
class WidgetContentTest {
    private val data = WheelData(
        speed = 32.4f, voltage = 120f, current = -10f, batteryPercent = 71,
        tripDistance = 12.34f, totalDistance = 4321f,
    )

    private fun metrics(csv: String, data: WheelData?, battery: () -> Int = { 55 }) =
        WidgetContent.metrics(
            csv = csv, data = data, speedUnit = "kmh", speedUnitLabel = "km/h",
            distUnit = "km", tempUnit = "C", phoneBattery = battery, text = { it.key },
        )

    @Test
    fun aSlotShowsItsNumberAndItsUnitInTheCaption() {
        val m = metrics("SPEED,TRIP,BATTERY", data)
        // The decimal mark is the phone's (12.3 or 12,3), as everywhere in the app.
        assertEquals(listOf("32", "%.1f".format(12.3f), "71"), m.values)
        assertEquals(
            listOf(
                Res.string.widget_metric_speed.key + " (km/h)",
                Res.string.widget_metric_trip.key + " (km)",
                Res.string.widget_metric_battery.key + " (%)",
            ),
            m.captions,
        )
    }

    @Test
    fun withNoWheelTheNumbersAreDashesAndTheCaptionsStay() {
        val m = metrics("SPEED,TRIP,BATTERY", null)
        assertEquals(List(3) { WidgetContent.DASH }, m.values)
        assertEquals(Res.string.widget_metric_speed.key + " (km/h)", m.captions[0])
    }

    @Test
    fun anEmptySlotHasNoCaption() {
        val m = metrics("SPEED,NONE,BATTERY", data)
        assertEquals(WidgetMetricType.SLOTS, m.values.size)
        assertEquals("", m.captions[1])
        assertEquals(WidgetContent.DASH, m.values[1])
    }

    @Test
    fun thePhoneBatteryIsAskedOnlyWhenASlotShowsIt() {
        var asked = 0
        metrics("SPEED,TRIP,BATTERY", data) { asked++; 55 }
        assertEquals(0, asked)
        val m = metrics("SPEED,TRIP,PHONE_BATTERY", data) { asked++; 55 }
        assertEquals(1, asked)
        assertEquals("55", m.values[2])
    }

    @Test
    fun powerIsNeverNegativeAndAnUnknownAverageIsADash() {
        assertEquals("1200", WidgetContent.value(WidgetMetricType.POWER, data, "kmh", "km", "C", 0))
        val unknown = data.copy(whPerKmRecent = Float.NaN, rangeKmEstimate = Float.NaN, pwm = Float.NaN)
        for (type in listOf(WidgetMetricType.WH_PER_KM, WidgetMetricType.RANGE_ESTIMATE, WidgetMetricType.PWM)) {
            assertEquals(WidgetContent.DASH, WidgetContent.value(type, unknown, "kmh", "km", "C", 0), type.key)
        }
    }

    @Test
    fun everyMetricHasAUnit() {
        for (type in WidgetMetricType.entries) {
            assertTrue(WidgetContent.unit(type, "km/h", "km", "C").isNotBlank(), type.key)
        }
    }

    @Test
    fun aButtonNamesTheNextPress() {
        assertEquals(Res.string.notif_btn_light_on, WidgetContent.buttonLabel("LIGHT"))
        assertEquals(Res.string.notif_btn_light_off, WidgetContent.buttonLabel("LIGHT", lightOn = true))
        assertEquals(Res.string.notif_btn_unlock, WidgetContent.buttonLabel("LOCK", locked = true))
        assertEquals(Res.string.notif_btn_stop_record, WidgetContent.buttonLabel("RECORD", recording = true))
        assertEquals(Res.string.widget_voice_off, WidgetContent.buttonLabel("VOICE", voiceOn = true))
        assertEquals(Res.string.widget_voice_on, WidgetContent.buttonLabel("VOICE"))
    }

    @Test
    fun everyActionHasALabelAndAnEmptySlotHasNone() {
        for (type in WidgetActionType.entries) assertNotNull(WidgetContent.buttonLabel(type.key), type.key)
        assertNull(WidgetContent.buttonLabel(WidgetActionType.NONE))
    }

    @Test
    fun onlyVoiceAndRecordWorkWithoutAWheel() {
        val free = WidgetActionType.entries.filter { WidgetContent.enabled(it.key, connected = false) }
        assertEquals(listOf(WidgetActionType.RECORD, WidgetActionType.VOICE), free)
        assertTrue(WidgetActionType.entries.all { WidgetContent.enabled(it.key, connected = true) })
        assertFalse(WidgetContent.enabled(WidgetActionType.NONE, connected = false))
    }
}
