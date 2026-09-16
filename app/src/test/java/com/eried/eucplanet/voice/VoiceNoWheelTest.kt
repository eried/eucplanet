package com.eried.eucplanet.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What can be answered with no wheel connected.
 *
 * Found by a rider asking out loud with nothing paired: the app said
 * "temperature 0 degrees". A fresh WheelData is all zeroes, and the report
 * route phrases a zero exactly like a measurement, so the one rule this
 * feature has - never invent a number - was broken by the path that was
 * supposed to be the safe one.
 *
 * The split is not "wheel or not" in the abstract: it is whether the answer
 * comes off the wheel. The clock and the phone's own battery are answerable
 * sitting on a desk.
 */
class VoiceNoWheelTest {

    @Test
    fun `the clock and the phone do not need a wheel`() {
        for (key in listOf("Time", "PhoneBattery", "Recording", "Navigation", "PHONE_BATTERY")) {
            assertTrue("$key should be answerable with no wheel", key in OFF_WHEEL)
        }
    }

    @Test
    fun `gps comes from the phone, not the wheel`() {
        // A rider walking with the app open still has an altitude.
        assertTrue("GPS_ALTITUDE" in OFF_WHEEL)
        assertTrue("GPS_SPEED" in OFF_WHEEL)
    }

    @Test
    fun `asking what can be said never needs a wheel`() {
        // The worst possible time to refuse the help term is when nothing
        // else is answerable and the rider is trying to work out why.
        assertTrue(VoiceVocabulary.HELP_KEY in OFF_WHEEL)
    }

    @Test
    fun `every reading does need one`() {
        // These are the ones that spoke zeroes. If a later change adds one of
        // them to the off-wheel set, this fails rather than a rider being told
        // their motor is at zero degrees.
        for (key in listOf(
            "Temp", "Battery", "Speed", "Current", "Power", "Distance", "PWM",
            "VOLTAGE", "MOTOR_TEMP", "BATTERY", "SPEED", "TIRE_PRESSURE",
        )) {
            assertFalse("$key must not be answered without a wheel", key in OFF_WHEEL)
        }
    }
}
