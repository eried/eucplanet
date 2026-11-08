package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The start-up sweep both phones run over recordings the app was killed in
 * (recoverKilledRecordings). On iOS a swipe away mid-recording used to leave
 * a 0 s trip in the list; this pins what Android already did: a row with data
 * is finalized in place, an empty one goes with its file, and the recording
 * running now is left alone.
 */
class KilledRecordingSweepTest {

    private fun killed(id: Long, file: String) = TripRecord(
        id = id, fileName = file, tripUuid = "uuid-$id", startTime = 1_000L, endTime = null,
    )

    private val withRows = "Date,Speed,Latitude,Longitude,Total mileage\n" +
        "25.06.2026 15:30:00.000,10,37.400,-122.100,1000.0\n" +
        "25.06.2026 15:30:01.000,12,37.401,-122.100,1000.5\n" +
        "25.06.2026 15:30:04.000,14,37.402,-122.100,1001.2\n"

    private class Store(trips: List<TripRecord>, val files: MutableMap<String, String>) {
        val rows = trips.toMutableList()
        suspend fun sweep(live: Long? = null) = recoverKilledRecordings(
            rows.filter { it.endTime == null },
            liveTripId = live,
            readCsv = { files[it.fileName] },
            keep = { t, _ -> rows[rows.indexOfFirst { it.id == t.id }] = t },
            drop = { t -> rows.removeAll { it.id == t.id }; files.remove(t.fileName) },
        )
    }

    @Test fun killedWithRowsIsFinalizedInPlace() = runBlocking {
        val store = Store(listOf(killed(42, "a.csv")), mutableMapOf("a.csv" to withRows))
        store.sweep()
        val out = store.rows.single()
        assertEquals(42L, out.id)
        assertEquals("uuid-42", out.tripUuid)
        assertNotNull(out.endTime)
        assertTrue(out.endTime!! > out.startTime)
        assertEquals(3, out.sampleCount)
        assertEquals(1.2f, out.distanceKm, 0.05f)
    }

    @Test fun killedBeforeAnyRowIsDroppedWithItsFile() = runBlocking {
        val store = Store(
            listOf(killed(1, "empty.csv"), killed(2, "missing.csv")),
            mutableMapOf("empty.csv" to "Date,Speed,Latitude,Longitude\n"),
        )
        store.sweep()
        assertTrue(store.rows.isEmpty(), "no 0 s trip is left in the list")
        assertTrue(store.files.isEmpty(), "and no empty file")
    }

    @Test fun theLiveRecordingIsLeftAlone() = runBlocking {
        val store = Store(listOf(killed(7, "live.csv")), mutableMapOf("live.csv" to "Date,Speed\n"))
        store.sweep(live = 7)
        assertEquals(null, store.rows.single().endTime)
        assertTrue("live.csv" in store.files)
    }

    @Test fun aSecondSweepChangesNothing() = runBlocking {
        val done = killed(9, "done.csv").copy(endTime = 9_000L, distanceKm = 3f)
        val store = Store(listOf(killed(42, "a.csv"), done), mutableMapOf("a.csv" to withRows))
        store.sweep()
        val first = store.rows.toList()
        store.sweep()
        assertEquals(first, store.rows)
        assertEquals(done, store.rows.first { it.id == 9L })
    }

    @Test fun rowCountIsTheLinesAfterTheHeader() {
        assertEquals(0, tripCsvRowCount("Date\n"))
        assertEquals(0, tripCsvRowCount(""))
        assertEquals(3, tripCsvRowCount(withRows))
    }
}
