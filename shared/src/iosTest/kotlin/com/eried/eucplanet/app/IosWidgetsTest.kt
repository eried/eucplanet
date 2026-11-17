package com.eried.eucplanet.app

import com.eried.eucplanet.platform.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file the home screen widgets read (iosApp/Widgets/WheelWidgets.swift
 * decodes these names): a field renamed here and not there is an empty widget.
 */
class IosWidgetsTest {

    private val content = IosWidgets.Content(
        connected = true,
        status = "Connected",
        statusIdle = "No wheel",
        values = listOf("82", "31.5", "--"),
        captions = listOf("Battery (%)", "Speed (km/h)", ""),
        buttons = listOf(
            IosWidgets.Button("LIGHT", "Light off", enabled = true, enabledIdle = false),
            IosWidgets.Button("NONE", "", enabled = false, enabledIdle = false),
            IosWidgets.Button("VOICE", "Voice on", enabled = true, enabledIdle = true),
        ),
        standalone = listOf(IosWidgets.Button("HORN", "Horn", enabled = true, enabledIdle = false)),
        names = mapOf("wheel" to "Wheel dashboard", "unset" to "Set a button in Settings"),
    )

    @Test fun theFileCarriesWhatTheSwiftSideDecodes() {
        val o = JSONObject(IosWidgets.encode(content, atSeconds = 1_700_000_000))
        assertEquals(1_700_000_000L, o.getLong("at"))
        assertTrue(o.getBoolean("connected"))
        assertEquals("Connected", o.getString("status"))
        assertEquals("No wheel", o.getString("statusIdle"))
        val metrics = o.getJSONArray("metrics")
        assertEquals(3, metrics.length())
        assertEquals("31.5", metrics.getJSONObject(1).getString("value"))
        assertEquals("Speed (km/h)", metrics.getJSONObject(1).getString("caption"))
        assertEquals("Wheel dashboard", o.getJSONObject("names").getString("wheel"))
    }

    @Test fun aButtonSaysWhetherItWorksWithoutAWheel() {
        val buttons = JSONObject(IosWidgets.encode(content, 0)).getJSONArray("buttons")
        val light = buttons.getJSONObject(0)
        assertEquals("LIGHT", light.getString("key"))
        assertTrue(light.getBoolean("enabled"))
        assertFalse(light.getBoolean("enabledIdle"))
        assertTrue(buttons.getJSONObject(2).getBoolean("enabledIdle"))
    }

    @Test fun anEmptySlotKeepsItsPlace() {
        // The button widgets pick a slot by its position, so an unset one
        // still has an entry, with no label to draw.
        val o = JSONObject(IosWidgets.encode(content, 0))
        val empty = o.getJSONArray("buttons").getJSONObject(1)
        assertEquals("NONE", empty.getString("key"))
        assertEquals("", empty.getString("label"))
        assertEquals("", o.getJSONArray("metrics").getJSONObject(2).getString("caption"))
        assertEquals(1, o.getJSONArray("standalone").length())
    }
}
