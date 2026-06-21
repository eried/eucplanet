package com.eried.eucplanet.hudlink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the Overlay Studio wire format so an iOS-authored preset decodes on the
 * Android HUD. The HUD keys by property name + enum constant name (no
 * `@SerialName`), so these exact tokens MUST appear / round-trip.
 */
class OverlayPresetTest {
    @Test fun encodesWireExactTokens() {
        val preset = OverlayPreset(
            name = "Cruise",
            layout = ViewportLayout.SINGLE,
            elements = listOf(
                OverlayElement(id = "e1", type = OverlayElementType.DATA_DIAL, metric = "SPEED", gaugeMax = 60f),
                OverlayElement(id = "e2", type = OverlayElementType.TEXT, text = "Hi {speed}", textAlign = "CENTER"),
            ),
        )
        val js = OverlayPresetCodec.encode(preset)
        // Enum + field tokens the HUD decoder matches on.
        assertTrue("\"layout\":\"SINGLE\"" in js, js)
        assertTrue("\"type\":\"DATA_DIAL\"" in js, js)
        assertTrue("\"type\":\"TEXT\"" in js, js)
        assertTrue("\"metric\":\"SPEED\"" in js, js)
        assertTrue("\"textAlign\":\"CENTER\"" in js, js)
        // encodeDefaults must be on so every field ships (e.g. dialStyle default).
        assertTrue("\"dialStyle\":\"FULL\"" in js, js)
    }

    @Test fun roundTripsIntact() {
        val preset = OverlayPreset(
            name = "Round",
            elements = listOf(
                OverlayElement(id = "a", type = OverlayElementType.DATA_BAR, metric = "PWM", x = 0.2f, y = 0.3f, width = 0.5f, foreground = 0xFF00FF00L),
                OverlayElement(id = "b", type = OverlayElementType.CLOCK, clockStyle = "ANALOG", clock24Hour = false),
            ),
        )
        val decoded = OverlayPresetCodec.decode(OverlayPresetCodec.encode(preset))
        assertNotNull(decoded)
        assertEquals(preset, decoded)
        assertEquals("PWM", decoded.elements[0].metric)
        assertEquals(OverlayElementType.CLOCK, decoded.elements[1].type)
    }

    @Test fun decodeBlankIsNull() {
        assertEquals(null, OverlayPresetCodec.decode(""))
    }
}
