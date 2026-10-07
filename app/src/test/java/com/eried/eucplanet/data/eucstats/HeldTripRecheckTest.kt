package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * A trip held for review must be able to stop being held.
 *
 * The upload response was the app's only look at a verdict, and nothing ever asked again.
 * When a moderator approved a held trip the phone never found out, so the rider kept an
 * "under review" cloud forever on a ride that was already counting on the leaderboard.
 * Re-uploading could not clear it either: the same trip_uuid hits the server's dedupe and
 * returns the same verdict.
 */
class HeldTripRecheckTest {

    private lateinit var api: FakeEucStatsApi
    private lateinit var tripDao: FakeTripDao
    private lateinit var repo: EucStatsRepository

    private fun held(id: Long, uuid: String) = TripRecord(
        id = id,
        endTime = 1_717_276_202_000L,
        fileName = "trip_$id.csv",
        distanceKm = 18.0f,
        tripUuid = uuid,
        eucstatsStatus = 2,
        eucstatsValidation = "flagged",
    )

    @Before fun setUp() {
        api = FakeEucStatsApi()
        tripDao = FakeTripDao()
        repo = EucStatsRepository(
            api = api,
            attestation = StubAttestation(),
            settings = FakeSettingsPort(initialStoreId = "store-1"),
            tripDao = tripDao,
            tripFileBytes = { ByteArray(0) },
            appVersion = "1.0.0",
            osVersion = "14",
            clock = { 1_000_000L },
        )
    }

    @Test
    fun `approved trip stops being held`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))
        api.tripStatusResult = TripStatus("validated", "accepted", emptyList())

        assertEquals("validated", repo.refreshTripVerdict(tripDao.trips[0]))
        assertEquals("validated", tripDao.trips[0].eucstatsValidation)
        assertEquals(2, tripDao.trips[0].eucstatsStatus)   // still uploaded, just no longer held
    }

    @Test
    fun `a trip still under review is left alone`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))
        api.tripStatusResult = TripStatus("flagged", "under_review", listOf("teleport"))

        assertEquals("flagged", repo.refreshTripVerdict(tripDao.trips[0]))
        assertEquals("flagged", tripDao.trips[0].eucstatsValidation)
        assertTrue("nothing changed, so nothing should be written", tripDao.updates.isEmpty())
    }

    @Test
    fun `a rejected verdict is recorded, not silently treated as accepted`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))
        api.tripStatusResult = TripStatus("rejected", "rejected", listOf("mock_location"))

        assertEquals("rejected", repo.refreshTripVerdict(tripDao.trips[0]))
        assertEquals("rejected", tripDao.trips[0].eucstatsValidation)
    }

    @Test
    fun `an unreachable server reports null rather than clearing the hold`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))
        api.tripStatusResult = null

        assertNull(repo.refreshTripVerdict(tripDao.trips[0]))
        assertEquals("flagged", tripDao.trips[0].eucstatsValidation)
    }

    @Test
    fun `sweep only asks about held trips`() = runBlocking {
        tripDao.insert(held(1L, "uuid-held"))
        tripDao.insert(held(2L, "uuid-held-2").copy(eucstatsValidation = "validated"))  // already shared
        tripDao.insert(held(3L, "uuid-pending").copy(eucstatsStatus = 1, eucstatsValidation = null))
        tripDao.insert(held(4L, "uuid-imported").copy(tripUuid = null))                 // imported
        api.tripStatusResult = TripStatus("validated", "accepted", emptyList())

        assertEquals(1, repo.refreshHeldTrips())
        assertEquals(listOf("uuid-held"), api.tripStatusCalls)
    }

    @Test
    fun `sweep counts only the trips whose verdict actually moved`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))
        tripDao.insert(held(2L, "uuid-2"))
        api.tripStatusResult = TripStatus("flagged", "under_review", listOf("teleport"))

        assertEquals(0, repo.refreshHeldTrips())
        assertEquals(2, api.tripStatusCalls.size)
    }

    @Test
    fun `a background sweep is capped to the most recent held trips`() = runBlocking {
        // A rider whose trips are never reviewed would otherwise re-ask about all of them,
        // every sweep, forever. Anything past the cap is still reachable by tapping it, or
        // by the rider-initiated "Sync all".
        repeat(30) { i -> tripDao.insert(held(i.toLong() + 1, "uuid-$i").copy(startTime = i.toLong())) }
        api.tripStatusResult = TripStatus("flagged", "under_review", listOf("teleport"))

        repo.refreshHeldTrips()
        assertEquals(BACKGROUND_HELD_LIMIT, api.tripStatusCalls.size)
        assertTrue("newest first", api.tripStatusCalls.contains("uuid-29"))
        assertFalse("oldest dropped", api.tripStatusCalls.contains("uuid-0"))
    }

    @Test
    fun `an explicit sync re-checks every held trip`() = runBlocking {
        repeat(30) { i -> tripDao.insert(held(i.toLong() + 1, "uuid-$i").copy(startTime = i.toLong())) }
        api.tripStatusResult = TripStatus("flagged", "under_review", listOf("teleport"))

        repo.refreshHeldTrips(ALL_HELD_TRIPS)
        assertEquals(30, api.tripStatusCalls.size)
    }

    @Test
    fun `sync all reports cleared verdicts rather than nothing to sync`() = runBlocking {
        tripDao.insert(held(1L, "uuid-1"))          // held, nothing pending to upload
        api.tripStatusResult = TripStatus("validated", "accepted", emptyList())

        val result = repo.syncPendingNow { _, _ -> }
        assertEquals(0, result.total)               // no uploads were due
        assertEquals(1, result.cleared)             // but a verdict did move
    }

    @Test
    fun `a trip with no uuid is never asked about`() = runBlocking {
        val noUuid = held(1L, "x").copy(tripUuid = null)
        tripDao.insert(noUuid)

        assertNull(repo.refreshTripVerdict(noUuid))
        assertTrue(api.tripStatusCalls.isEmpty())
    }
}
