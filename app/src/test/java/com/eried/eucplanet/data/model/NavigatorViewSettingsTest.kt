package com.eried.eucplanet.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigatorViewSettingsTest {
    private val known = setOf("VOLTAGE", "BATTERY", "TRIP", "LOAD", "SPEED", "TEMPERATURE")

    @Test fun defaultsAreVoltageBatteryTripPwm() {
        assertEquals(listOf("VOLTAGE", "BATTERY", "TRIP", "LOAD"),
            NavigatorViewSettings.metricSlots(NavigatorViewSettings.DEFAULT_METRICS, known))
    }

    @Test fun keepsValidChoices() {
        assertEquals(listOf("SPEED", "BATTERY", "TEMPERATURE", "LOAD"),
            NavigatorViewSettings.metricSlots("SPEED,BATTERY,TEMPERATURE,LOAD", known))
    }

    @Test fun unknownKeyFallsBackToSlotDefault() {
        assertEquals(listOf("VOLTAGE", "BATTERY", "TRIP", "LOAD"),
            NavigatorViewSettings.metricSlots("GONE,,TRIP", known))
    }

    @Test fun alwaysFourSlots() {
        assertEquals(4, NavigatorViewSettings.metricSlots("SPEED,BATTERY,TRIP,LOAD,VOLTAGE,SPEED", known).size)
        assertEquals(4, NavigatorViewSettings.metricSlots("", known).size)
    }

    @Test fun withSlotReplacesOnlyThatTile() {
        assertEquals("VOLTAGE,BATTERY,SPEED,LOAD",
            NavigatorViewSettings.withSlot(NavigatorViewSettings.DEFAULT_METRICS, 2, "SPEED", known))
    }

    @Test fun withSlotIgnoresOutOfRangeIndex() {
        assertEquals(NavigatorViewSettings.DEFAULT_METRICS,
            NavigatorViewSettings.withSlot(NavigatorViewSettings.DEFAULT_METRICS, 4, "SPEED", known))
        assertEquals(NavigatorViewSettings.DEFAULT_METRICS,
            NavigatorViewSettings.withSlot(NavigatorViewSettings.DEFAULT_METRICS, -1, "SPEED", known))
    }

    @Test fun cruiseSpecIsRegisteredWithDefault25() {
        val spec = ADVANCED_SPECS.single { it.id == "navCruiseKmh" }
        assertEquals(25, spec.get(AdvancedSettings()))
        assertEquals(10..60, spec.range)
    }
}
