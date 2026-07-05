package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.eucstats.FakeTripDao
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.util.TripCsv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the startup recovery of a recording a previous session was killed
 * mid-flight (force-close / crash). The finalize path must UPDATE the existing
 * row in place -- same id / file / uuid -- so a recovered trip can NEVER become
 * a duplicate, and must DROP rows whose CSV holds nothing usable.
 */
class TripRecoveryTest {

    private val killed = TripRecord(
        id = 42L,
        fileName = "trip_killed.csv",
        tripUuid = "uuid-abc",
        startTime = 1_000L,
        endTime = null,          // the tell-tale of a killed recording
        distanceKm = 0f,
        sampleCount = 0,
    )

    /** Mirror finalizeUnfinishedTrips()'s per-trip decision on a CSV string. */
    private fun recover(trip: TripRecord, csv: String?): TripRecord? {
        val rows = csv?.let { (it.count { c -> c == '\n' } - 1).coerceAtLeast(0) } ?: 0
        val metrics = csv?.let { TripCsv.metricsFrom(parseTripQuads(it)) }
        return finalizedTripOrNull(trip, metrics, rows)
    }

    @Test
    fun `killed recording with data is finalized in place, never duplicated`() {
        val csv = "Date,Speed,Latitude,Longitude,Total mileage\n" +
            "25.06.2026 15:30:00.000,10,37.400,-122.100,1000.0\n" +
            "25.06.2026 15:30:01.000,12,37.401,-122.100,1000.5\n" +
            "25.06.2026 15:30:04.000,14,37.402,-122.100,1001.2\n"
        val out = recover(killed, csv)
        assertNotNull("a recording with rows must be finalized, not dropped", out)
        out!!
        // SAME row -> the caller UPDATEs it, so it can never become a duplicate.
        assertEquals(42L, out.id)
        assertEquals("trip_killed.csv", out.fileName)
        assertEquals("uuid-abc", out.tripUuid)
        // Reconstructed from the CSV.
        assertNotNull("end time filled in from the last row", out.endTime)
        assertTrue("end after start", out.endTime!! > out.startTime)
        assertEquals("wheel-odometer delta (1001.2 - 1000.0)", 1.2f, out.distanceKm, 0.05f)
        assertEquals(3, out.sampleCount)
    }

    @Test
    fun `header-only CSV (killed at the first instant) is dropped`() {
        val out = recover(killed, "Date,Speed,Latitude,Longitude,Total mileage\n")
        assertNull("nothing usable -> drop the zombie row", out)
    }

    @Test
    fun `rows with no parseable timestamp are dropped`() {
        val csv = "Date,Latitude,Longitude,Total mileage\n" +
            ",37.400,-122.100,1000.0\n" +
            ",37.401,-122.100,1000.5\n"
        assertNull(recover(killed, csv))
    }

    @Test
    fun `missing CSV file is dropped`() {
        assertNull(recover(killed, null))
    }

    @Test
    fun `finalize is pure and same-id, so re-running recovery cannot fork a row`() {
        val m = TripCsv.Metrics(startMs = 1_000L, endMs = 5_000L, distanceKm = 2.5f, valid = true)
        val a = finalizedTripOrNull(killed, m, 10)!!
        val b = finalizedTripOrNull(killed, m, 10)!!
        assertEquals(a.id, b.id)
        assertEquals(killed.id, a.id)
        assertEquals(5_000L, a.endTime)
        assertEquals(2.5f, a.distanceKm, 0.001f)
        assertEquals(10, a.sampleCount)
    }

    @Test
    fun `GPS-only trip (no odometer) still finalizes via great-circle distance`() {
        // No mileage column -> distance falls back to GPS; still a valid finalize.
        val csv = "Date,Latitude,Longitude\n" +
            "2026-06-25 15:30:00,37.40000,-122.10000\n" +
            "2026-06-25 15:30:02,37.40100,-122.10000\n"
        val out = recover(killed, csv)
        assertNotNull(out)
        assertEquals(42L, out!!.id)
        assertTrue("some GPS distance accumulated", out.distanceKm > 0f)
    }

    // ---- DAO-level: the finalize sweep against a real in-memory DAO ----

    /** Mirror finalizeUnfinishedTrips()'s DB operations (UPDATE finalized rows,
     *  DELETE empty ones) against the fake DAO, feeding CSV text per file. */
    private suspend fun runSweep(dao: FakeTripDao, csvByFile: Map<String, String?>) {
        for (trip in dao.getUnfinished()) {
            val csv = csvByFile[trip.fileName]
            val rows = csv?.let { (it.count { c -> c == '\n' } - 1).coerceAtLeast(0) } ?: 0
            val metrics = csv?.let { TripCsv.metricsFrom(parseTripQuads(it)) }
            val out = finalizedTripOrNull(trip, metrics, rows)
            if (out != null) dao.update(out) else dao.delete(trip)
        }
    }

    @Test
    fun `repeated startup sweeps never duplicate a recovered trip`() = runBlocking {
        val dao = FakeTripDao()
        dao.trips += killed
        val csv = "Date,Latitude,Longitude,Total mileage\n" +
            "25.06.2026 15:30:00.000,37.400,-122.100,1000.0\n" +
            "25.06.2026 15:30:02.000,37.401,-122.100,1000.9\n"
        // Three cold starts in a row -> must stay exactly one row.
        repeat(3) { runSweep(dao, mapOf("trip_killed.csv" to csv)) }
        assertEquals("exactly one row, never duplicated across restarts", 1, dao.trips.size)
        assertEquals(42L, dao.trips[0].id)
        assertNotNull("finalized (endTime set)", dao.trips[0].endTime)
        // Only the FIRST sweep touched it; later sweeps see endTime != null.
        assertEquals("finalized once, then left alone", 1, dao.updates.size)
    }

    @Test
    fun `empty killed recording is removed from the DB entirely`() = runBlocking {
        val dao = FakeTripDao()
        dao.trips += killed
        repeat(2) { runSweep(dao, mapOf("trip_killed.csv" to "Date,Latitude,Longitude\n")) }
        assertTrue("zombie row dropped", dao.trips.isEmpty())
    }

    @Test
    fun `a normal finished trip is untouched by the sweep`() = runBlocking {
        val dao = FakeTripDao()
        val finished = killed.copy(id = 7L, fileName = "done.csv", endTime = 9_000L, distanceKm = 3f)
        dao.trips += finished
        runSweep(dao, mapOf("done.csv" to "Date,Latitude,Longitude\nx"))
        assertEquals(1, dao.trips.size)
        assertEquals("finished trips are not in getUnfinished, so no update", 0, dao.updates.size)
        assertEquals(9_000L, dao.trips[0].endTime)
    }
}
