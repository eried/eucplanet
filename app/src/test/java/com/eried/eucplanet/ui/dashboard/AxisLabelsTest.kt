package com.eried.eucplanet.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisLabelsTest {

    private val whole: (Float) -> String = { "%.0f".format(it) }

    @Test
    fun `a wide range keeps whole numbers`() {
        assertEquals(listOf("0", "25", "50", "75", "100"), axisLabels(listOf(0f, 25f, 50f, 75f, 100f), whole))
    }

    @Test
    fun `a narrow range that would repeat a number takes a decimal`() {
        // The Amps chart at rest: 2 to 5 A read 2, 3, 3, 4, 5.
        val labels = axisLabels(listOf(2f, 2.75f, 3.5f, 4.25f, 5f), whole)
        assertEquals(5, labels.distinct().size)
        assertEquals("2.0", labels.first().replace(',', '.'))
        assertEquals("5.0", labels.last().replace(',', '.'))
    }

    @Test
    fun `the voltage axis of a short charge names every line`() {
        // 80.2 to 81.0 V on the Battery monitor read 80, 80, 81, 81, 81.
        val labels = axisLabels(listOf(80.2f, 80.4f, 80.6f, 80.8f, 81.0f), whole)
        assertEquals(5, labels.distinct().size)
        assertEquals("80.2", labels.first().replace(',', '.'))
    }

    @Test
    fun `a very narrow range takes two decimals`() {
        val labels = axisLabels(listOf(3.30f, 3.32f, 3.34f, 3.36f, 3.38f), whole)
        assertEquals(5, labels.distinct().size)
    }

    @Test
    fun `a format that already separates the lines is left alone`() {
        val milli: (Float) -> String = { "%.1f".format(it) }
        assertEquals(
            listOf("0.0", "0.5", "1.0", "1.5", "2.0"),
            axisLabels(listOf(0f, 0.5f, 1f, 1.5f, 2f), milli).map { it.replace(',', '.') },
        )
    }
}
