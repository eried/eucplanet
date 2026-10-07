package com.eried.eucplanet.hud.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The HUD's copy of the battery envelope, pinned against the phone's.
 *
 * Two enums with the same name in two modules is the arrangement that lets the
 * renderer code be shared, and it is also the arrangement that drifts. The
 * phone's version of these assertions lives in StudioBatteryEnvelopeTest; if
 * either side changes what it draws, one of the two suites goes red.
 *
 * The case that matters on glasses is the first half minute of a ride, when
 * there is no envelope yet. A rider moving at speed must not be shown "NaN"
 * where a number belongs.
 */
class HudBatteryEnvelopeTest {

    private val metric = StudioMetric.BATTERY_ENVELOPE

    private fun at(envelope: Float) = WheelData(batteryEnvelope = envelope)

    @Test fun `it reads as a whole percent`() {
        assertEquals("71", metric.formatted(at(71.4f), "kmh", "km", "C"))
        assertEquals("%", metric.plainUnit)
    }

    @Test fun `the unit system does not touch a percentage`() {
        assertEquals("71", metric.formatted(at(71.4f), "mph", "mi", "F"))
    }

    @Test fun `nothing to say yet draws zero, never NaN`() {
        assertEquals("0", metric.formatted(at(Float.NaN), "kmh", "km", "C"))
        assertEquals(0f, metric.displayValue(at(Float.NaN), "kmh", "km", "C"), 0.001f)
    }

    @Test fun `the key matches the phone's, so one overlay works on both`() {
        // Overlay layouts are authored on the phone and rendered here. A key
        // that differs by a character renders nothing, silently.
        assertEquals("BATTERY_ENVELOPE", metric.key)
        assertEquals(metric, StudioMetric.fromKey("BATTERY_ENVELOPE"))
    }

    @Test fun `an unset field is the not-yet state, not a full battery`() {
        // WheelData defaults matter here: the HUD builds one per frame from
        // whatever the phone sent, and a phone too old to send this leaves it
        // at its default.
        assertEquals("0", metric.formatted(WheelData(), "kmh", "km", "C"))
    }
}
