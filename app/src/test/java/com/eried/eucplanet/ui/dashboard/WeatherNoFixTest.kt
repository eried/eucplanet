package com.eried.eucplanet.ui.dashboard

import com.eried.eucplanet.resources.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the weather panel says before the phone has a location fix. */
class WeatherNoFixTest {

    private fun message(permission: Boolean, locationOn: Boolean) =
        WeatherNoFix.messageRes(WeatherNoFix.canLocate(permission) { locationOn })

    @Test fun `permission and location on with no fix says it is waiting`() {
        assertEquals(Res.string.weather_waiting_location, message(permission = true, locationOn = true))
    }

    @Test fun `anything missing keeps the old no-location message`() {
        assertEquals(Res.string.weather_error_no_location, message(permission = true, locationOn = false))
        assertEquals(Res.string.weather_error_no_location, message(permission = false, locationOn = true))
        assertEquals(Res.string.weather_error_no_location, message(permission = false, locationOn = false))
    }

    @Test fun `the location switch is only asked with the permission`() {
        var asked = false
        assertFalse(WeatherNoFix.canLocate(false) { asked = true; true })
        assertFalse(asked)
        assertTrue(WeatherNoFix.canLocate(true) { asked = true; true })
        assertTrue(asked)
    }
}
