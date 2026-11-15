package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Bluetooth relaunch on the iPhone carries on the trip iOS ended the app
 * in, when the app was gone only briefly; anything else is left for the
 * killed-recording sweep, as on Android.
 */
class KilledTripResumeTest {

    private fun open(id: Long, start: Long) = TripRecord(id = id, fileName = "t$id.csv", startTime = start)

    private val now = 10_000_000L

    @Test fun aTripKilledMomentsAgoCarriesOn() {
        val t = open(1, now - 600_000)
        assertEquals(t, KilledTripResume.pick(listOf(t), { now - 20_000 }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun aTripGoneTooLongIsLeftForTheSweep() {
        val t = open(1, now - 3_600_000)
        assertNull(KilledTripResume.pick(listOf(t), { now - 3_000_000 }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun onlyTheNewestOpenTripIsACandidate() {
        val old = open(1, now - 7_200_000)
        val newest = open(2, now - 300_000)
        // The old one's last row looks recent (a bad clock); still only the newest counts.
        val last = mapOf(1L to now - 5_000, 2L to now - 4_000_000)
        assertNull(KilledTripResume.pick(listOf(old, newest), { last[it.id] }, now, KilledTripResume.MAX_GAP_MS))
        val fresh = mapOf(1L to now - 5_000, 2L to now - 5_000)
        assertEquals(newest, KilledTripResume.pick(listOf(old, newest), { fresh[it.id] }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun aFileWithNoUsableRowIsNotCarriedOn() {
        assertNull(KilledTripResume.pick(listOf(open(1, now)), { null }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun aLastRowFromTheFutureIsNotCarriedOn() {
        assertNull(KilledTripResume.pick(listOf(open(1, now)), { now + 60_000 }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun noOpenTripPicksNothing() {
        assertNull(KilledTripResume.pick(emptyList(), { now }, now, KilledTripResume.MAX_GAP_MS))
    }

    @Test fun theWindowFollowsTheIdleStop() {
        val s = AppSettings()
        assertEquals(s.autoRecordStopIdleSeconds * 1000L, KilledTripResume.maxGapMs(s))
        assertEquals(KilledTripResume.MIN_GAP_MS, KilledTripResume.maxGapMs(s.copy(autoRecordStopIdleSeconds = 10)))
        assertEquals(KilledTripResume.MAX_GAP_MS, KilledTripResume.maxGapMs(s.copy(autoRecordStopIdleSeconds = 3600)))
        assertEquals(KilledTripResume.MAX_GAP_MS, KilledTripResume.maxGapMs(s.copy(autoRecord = false)))
        assertEquals(KilledTripResume.MAX_GAP_MS, KilledTripResume.maxGapMs(s.copy(autoRecordStopIdleSeconds = 0)))
    }
}
