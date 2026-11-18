package com.eried.eucplanet.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** The units a fresh install starts in. */
class DefaultUnitsTest {

    @Test fun `the United States, its territories and Liberia start imperial`() {
        for (c in listOf("US", "PR", "GU", "VI", "AS", "MP", "LR")) {
            assertEquals(c, DefaultUnits.IMPERIAL, DefaultUnits.pick(c, c))
        }
        assertEquals(DefaultUnits.IMPERIAL, DefaultUnits.pick("us", "US"))
        assertEquals(DefaultUnits.IMPERIAL, DefaultUnits.pick("PR", "US"))
    }

    @Test fun `the United Kingdom and the Crown Dependencies start on miles and Celsius`() {
        for (c in listOf("GB", "JE", "GG", "IM")) {
            assertEquals(c, DefaultUnits.MILES_CELSIUS, DefaultUnits.pick(c, c))
        }
        assertEquals(DefaultUnits.MILES_CELSIUS, DefaultUnits.pick("gb", "GB"))
    }

    @Test fun `Myanmar starts on miles and Celsius, no longer Fahrenheit`() {
        assertEquals(DefaultUnits.MILES_CELSIUS, DefaultUnits.pick("MM", "MM"))
    }

    @Test fun `everywhere else starts metric`() {
        for (c in listOf("NO", "DE", "FR", "CL", "AU", "CA", "IE", "JP", "IN")) {
            assertEquals(c, DefaultUnits.METRIC, DefaultUnits.pick(c, c))
        }
    }

    @Test fun `one signal alone is not enough`() {
        // Phone set to English (United States) on a Norwegian network.
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("NO", "US"))
        // An American SIM in a phone set to Norway.
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("US", "NO"))
        // Phone set to English (United Kingdom) abroad, a common choice.
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("DE", "GB"))
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("GB", "FR"))
    }

    @Test fun `an unknown signal counts as metric`() {
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("", "US"))
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("US", ""))
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("", "GB"))
        assertEquals(DefaultUnits.METRIC, DefaultUnits.pick("", ""))
    }

    @Test fun `two miles countries that disagree on the weather get Celsius`() {
        assertEquals(DefaultUnits.MILES_CELSIUS, DefaultUnits.pick("US", "GB"))
        assertEquals(DefaultUnits.MILES_CELSIUS, DefaultUnits.pick("GB", "US"))
    }
}
