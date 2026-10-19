package com.eried.eucplanet.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMotionTest {
    private val movingKmh = 4.0
    private val movingMs = 1.2
    private val minDisp = 8.0

    @Test fun `wheel connected and standing ignores GPS speed jitter`() {
        // 2 m/s of GPS noise with the wheel at 0: no fix is moving, so the
        // heading window stays empty and the heading stays null.
        assertFalse(NavMotion.isMoving(true, 0.0, movingKmh, 2.0, movingMs, 5f))
        assertFalse(NavMotion.isMoving(true, 0.0, movingKmh, 2.0, movingMs, null))
    }

    @Test fun `wheel connected and rolling is moving whatever GPS says`() {
        assertTrue(NavMotion.isMoving(true, 12.0, movingKmh, 0.0, movingMs, 50f))
    }

    @Test fun `no wheel and inaccurate fix is not moving`() {
        assertFalse(NavMotion.isMoving(false, 0.0, movingKmh, 2.0, movingMs, 40f))
    }

    @Test fun `no wheel and accurate fix with real travel sets a heading`() {
        assertTrue(NavMotion.isMoving(false, 0.0, movingKmh, 2.0, movingMs, 5f))
        assertTrue(NavMotion.headingDispOk(10.0, minDisp, 5f, 5f))
    }

    @Test fun `no wheel and unknown accuracy still trusts GPS speed`() {
        assertTrue(NavMotion.isMoving(false, 0.0, movingKmh, 2.0, movingMs, null))
        assertFalse(NavMotion.isMoving(false, 0.0, movingKmh, 0.5, movingMs, null))
    }

    @Test fun `displacement must clear the larger accuracy`() {
        assertFalse(NavMotion.headingDispOk(10.0, minDisp, 5f, 15f))
        assertTrue(NavMotion.headingDispOk(16.0, minDisp, 5f, 15f))
    }

    @Test fun `unknown accuracy falls back to the minimum displacement`() {
        assertTrue(NavMotion.headingDispOk(8.0, minDisp, Float.NaN, 0f))
        assertFalse(NavMotion.headingDispOk(7.9, minDisp, Float.NaN, Float.NaN))
    }
}
