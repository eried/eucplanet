package com.eried.eucplanet.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which connected wheels show the "Preliminary supported wheel" banner. */
class ExperimentalBannerTest {

    @Test fun `the Begode Master is rider-tested, the Master Pro is not`() {
        assertFalse(isPreliminaryWheel("Master"))
        assertFalse(isPreliminaryWheel("Begode Master"))
        assertFalse(isPreliminaryWheel("Master_4400"))
        assertTrue(isPreliminaryWheel("Begode Master Pro"))
        assertTrue(isPreliminaryWheel("Master Pro"))
    }

    @Test fun `the other tested wheels still pass and untested ones still warn`() {
        assertFalse(isPreliminaryWheel("V14 50GB"))
        assertFalse(isPreliminaryWheel("Mten3"))
        assertFalse(isPreliminaryWheel("KS-18L"))
        assertTrue(isPreliminaryWheel("Begode T4"))
        assertTrue(isPreliminaryWheel("KS-S22"))
        assertFalse(isPreliminaryWheel(null))
    }
}
