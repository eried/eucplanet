package com.eried.eucplanet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auto-volume curve is stored as text: "speed:boost,speed:boost". */
class VolumeCurveTest {

    @Test
    fun `a curve reads back in speed order`() {
        assertEquals(
            listOf(0f to 1f, 25f to 1.5f, 50f to 2f),
            parseVolumeCurve("50:2.0, 0:1, 25:1.5"),
        )
    }

    @Test
    fun `points that are not real numbers are left out`() {
        // Text parses "NaN" and "Infinity" as numbers. They can only come
        // from a hand-edited or synced settings file, and one of them in the
        // curve made every volume worked out from it meaningless.
        val curve = parseVolumeCurve("0:1,NaN:2,25:Infinity,30:NaN,-Infinity:1,50:1.5,x:1,60")
        assertEquals(listOf(0f to 1f, 50f to 1.5f), curve)
        assertTrue(curve.all { (speed, boost) -> speed.isFinite() && boost.isFinite() })
    }
}
