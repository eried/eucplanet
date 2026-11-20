package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.model.MetricRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngoMetricsTest {

    @Test
    fun everyOfferedKeyIsACatalogAndRegistryMetric() {
        // Drift guard: a key renamed in the catalog must not leave the glasses offering a dead one.
        for (k in EngoMetrics.KEYS) {
            assertNotNull("catalog $k", MetricCatalog.byKey(k))
            assertNotNull("registry $k", MetricRegistry.byKey(k))
        }
    }

    @Test
    fun everyTitleFitsItsColumn_inEveryUnitAndStatistic() {
        val unitSets = listOf(
            EngoMetrics.Units(),
            EngoMetrics.Units(speed = "mph", temp = "F", distance = "mi"),
            EngoMetrics.Units(speed = "ms", temp = "K"),
        )
        for (u in unitSets) for (k in EngoMetrics.KEYS) for (s in EngoMetrics.STATS) {
            val t = EngoMetrics.title(k, s, u)
            assertTrue("'$t' for $k $s too wide", EngoPixelFont.width(t, 2) <= 86)
            assertTrue("'$t' has a glyph for every character", t.all { EngoPixelFont.supports(it) })
        }
    }

    @Test
    fun titles() {
        val u = EngoMetrics.Units()
        assertEquals("TOP", EngoMetrics.title("SPEED", "MAX", u))
        assertEquals("KM/H", EngoMetrics.title("SPEED", "CURRENT", u))
        assertEquals("PWM %", EngoMetrics.title("LOAD", "CURRENT", u))
        assertEquals("MAX PWM", EngoMetrics.title("LOAD", "MAX", u))
        assertEquals("MOT°F", EngoMetrics.title("MOTOR_TEMP", "CURRENT", EngoMetrics.Units(temp = "F")))
        assertEquals("AVG SPD", EngoMetrics.title("SPEED", "AVG", u))
    }

    @Test
    fun digitsOnlyFromTheAppsOwnFormatting() {
        assertEquals("58", EngoMetrics.digits("58°C"))
        assertEquals("83.5", EngoMetrics.digits("83.5V"))
        assertEquals("83.5", EngoMetrics.digits("83,5V"))
        assertEquals("-12.0", EngoMetrics.digits("-12.0A"))
        assertEquals("92:40", EngoMetrics.digits("92:40"))
        assertEquals("--", EngoMetrics.digits("-"))
        assertEquals("112", EngoMetrics.digits("112.0A"))
        assertEquals("-123", EngoMetrics.digits("-123.4"))
    }
}
