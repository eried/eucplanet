package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider's 6 km that was not 6 km.
 *
 * A KingSong KS-16X recording arrived as nine rows over eight seconds: the
 * wheel stationary, its odometer frozen at 2761.734 km, top speed 1.47 km/h,
 * and about nine metres of GPS wander from a phone sitting still. The app
 * showed 6 km.
 *
 * Nothing in the file says 6. The number came from the recorder's fallback,
 * which handed over WheelData.tripDistance - the wheel's own trip meter,
 * counting since the rider last cleared it and unrelated to when recording
 * started. Their earlier riding that day, borrowed by an eight-second ride.
 *
 * These rows are the file, so the guard is against the real thing.
 */
class StationaryTripDistanceTest {

    /** The rider's file: date, lat, lon, "Total mileage". */
    private val rows = listOf(
        TripCsv.Quad("01.09.2026 19:10:03.670", 54.505424, 18.549819, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:04.685", 54.505424, 18.549819, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:05.675", 54.505424, 18.549819, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:06.679", 54.505409, 18.549817, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:07.671", 54.505415, 18.549807, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:08.674", 54.505458, 18.549780, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:09.668", 54.505461, 18.549782, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:10.670", 54.505464, 18.549787, 2761.734f),
        TripCsv.Quad("01.09.2026 19:10:11.764", 54.505460, 18.549797, 2761.734f),
    )

    @Test fun `the ride is metres, not kilometres`() {
        val m = TripCsv.metricsFrom(rows)
        assertTrue("a stationary eight seconds must not read as a ride", m.distanceKm < 0.05f)
    }

    @Test fun `the frozen odometer contributes nothing, and is never read raw`() {
        // 2761.734 is a lifetime reading. Summing per-step deltas gives zero
        // here, which is right; reading the column itself would give 2761.
        val m = TripCsv.metricsFrom(rows)
        assertTrue("the lifetime odometer leaked into the distance", m.distanceKm < 1f)
    }

    @Test fun `it is still a valid trip, just a very short one`() {
        val m = TripCsv.metricsFrom(rows)
        assertTrue("the timestamps did not parse", m.valid)
        // 19:10:03.670 to 19:10:11.764.
        assertEquals(8_094L, m.endMs - m.startMs)
    }

    @Test fun `a wheel that actually moves is still measured by its odometer`() {
        // The counterpart, so the fix above cannot be "always report zero".
        val moving = rows.mapIndexed { i, r -> r.copy(mileage = 2761.734f + i * 0.1f) }
        val m = TripCsv.metricsFrom(moving)
        assertEquals(0.8f, m.distanceKm, 0.01f)
    }
}
