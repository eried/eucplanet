package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.PhoneLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone's fix as the wheel state samples it for the GPS speed, altitude
 * and accuracy histories: Android's TripRepository.currentFix, value for value.
 */
class IosRideRecordingTest {

    @Test fun aFixIsReadInTheUnitsTheHistoriesKeep() {
        val fix = IosRideRecording.gpsFix(
            PhoneLocation(
                latitude = 59.91, longitude = 10.75, altitude = 23.5, speed = 5f, accuracy = 4f,
                speedKnown = true, accuracyKnown = true, altitudeKnown = true, time = 1L,
            ),
        )
        // Metres a second in, km/h out.
        assertEquals(18f, fix.speedKmh)
        assertEquals(23.5f, fix.altitudeM)
        assertEquals(4f, fix.accuracyM)
    }

    @Test fun whatTheFixDoesNotKnowIsLeftOut() {
        // Location's defaults are zeroes, not readings.
        val fix = IosRideRecording.gpsFix(PhoneLocation(latitude = 59.91, longitude = 10.75, time = 1L))
        assertNull(fix.speedKmh)
        assertNull(fix.altitudeM)
        assertNull(fix.accuracyM)
    }
}
