package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ---------------------------------------------------------------------------
// In-memory fakes
// ---------------------------------------------------------------------------

/** In-memory fake for EucStatsApiContract. Records calls and returns programmed results. */
class FakeEucStatsApi : EucStatsApiContract {
    var registerResult: RegisterResult = RegisterResult.Ok
    var cardResult: RiderCard? = RiderCard(
        displayName = "Fake", flag = "NO", hasAvatar = false, avatarUrl = null,
        totalKm = 100.0, trips = 1, topSpeedKmh = 40.0, maxGforce = 0.0,
        mileageRank = 5, country = "NO",
    )
    var uploadResult: UploadResult = UploadResult.Ok("validated", false)
    var patchResult: Int = 200
    var deleteResult: Boolean = true
    var exportResult: String? = """{"data": []}"""
    var profileResult: RiderProfile? = RiderProfile(
        displayName = "Fake", flag = "NO", hasAvatar = false,
        canChangeNameAfter = null, canChangeFlagAfter = null, canChangeAvatarAfter = null,
    )

    // Call tracking
    val registerCalls = mutableListOf<JSONObject>()
    val uploadCalls = mutableListOf<Pair<String, ByteArray>>()
    var getCardCallCount = 0
    val patchCalls = mutableListOf<Pair<String, JSONObject>>()
    var deleteCalled = false
    var exportCalled = false
    var getProfileCallCount = 0

    // Support programmed sequence of upload results for AuthFailure re-mint test
    private val uploadResultQueue = mutableListOf<UploadResult>()

    fun enqueueUploadResult(vararg results: UploadResult) {
        uploadResultQueue.addAll(results)
    }

    override fun registerRider(payload: JSONObject): RegisterResult {
        registerCalls += payload
        return registerResult
    }

    override fun getCard(storeId: String): RiderCard? {
        getCardCallCount++
        return cardResult
    }

    override fun getProfile(storeId: String): RiderProfile? {
        getProfileCallCount++
        return profileResult
    }

    override fun patchRider(storeId: String, payload: JSONObject): Int {
        patchCalls += storeId to payload
        return patchResult
    }

    override fun deleteRider(storeId: String): Boolean {
        deleteCalled = true
        return deleteResult
    }

    override fun exportRider(storeId: String): String? {
        exportCalled = true
        return exportResult
    }

    override fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult {
        uploadCalls += metaJson to gzippedCsv
        return if (uploadResultQueue.isNotEmpty()) uploadResultQueue.removeAt(0) else uploadResult
    }
}

/** In-memory fake for EucStatsSettingsPort. The rider id is held separately
 *  from AppSettings (real impl reads it from `eucstats_riderid.txt` via
 *  SyncManager), so tests can seed an initial id without leaning on a field
 *  that no longer exists on AppSettings. */
class FakeSettingsPort(
    initial: AppSettings = AppSettings(),
    initialStoreId: String? = null,
) : EucStatsSettingsPort {
    private var current = initial
    private var storeId: String? = initialStoreId

    override suspend fun get(): AppSettings = current
    override suspend fun update(settings: AppSettings) { current = settings }
    override fun riderStoreId(): String? = storeId
    override suspend fun writeRiderId(storeId: String): Boolean {
        this.storeId = storeId; return true
    }
    override suspend fun deleteRiderId(): Boolean {
        val had = storeId != null; storeId = null; return had
    }
}

/** In-memory fake for TripDao (Room @Dao interface). */
class FakeTripDao : TripDao {
    val trips = mutableListOf<TripRecord>()
    val updates = mutableListOf<TripRecord>()

    override fun observeAll(): Flow<List<TripRecord>> = flowOf(trips)
    override suspend fun insert(trip: TripRecord): Long {
        trips += trip; return trip.id
    }
    override suspend fun update(trip: TripRecord) {
        updates += trip
        val idx = trips.indexOfFirst { it.id == trip.id }
        if (idx >= 0) trips[idx] = trip else trips += trip
    }
    override suspend fun delete(trip: TripRecord) { trips.removeIf { it.id == trip.id } }
    override fun observeCount(): Flow<Int> = flowOf(trips.size)
    override suspend fun deleteAll() { trips.clear() }
    override suspend fun getPendingUploads(): List<TripRecord> =
        trips.filter { it.endTime != null && it.uploadStatus in listOf(1, 3) }
    override suspend fun getById(id: Long): TripRecord? = trips.firstOrNull { it.id == id }
    override suspend fun findByFileName(name: String): TripRecord? =
        trips.firstOrNull { it.fileName == name }
    override suspend fun getPendingEucstatsUploads(): List<TripRecord> =
        trips.filter { it.endTime != null && it.tripUuid != null && it.eucstatsStatus in listOf(1, 3) }
    override suspend fun resetUnfinishedEucstatsStatuses() {
        for (i in trips.indices) {
            if (trips[i].eucstatsStatus in listOf(1, 3)) trips[i] = trips[i].copy(eucstatsStatus = 0)
        }
    }
    override suspend fun resetAllEucstatsStatuses() {
        for (i in trips.indices) {
            if (trips[i].eucstatsStatus != 0) trips[i] = trips[i].copy(eucstatsStatus = 0)
        }
    }
}

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

private val SAMPLE_TRIP = TripRecord(
    id = 1L,
    startTime = 1_717_273_471_000L,
    endTime = 1_717_276_202_000L,
    fileName = "trip_test.csv",
    distanceKm = 18.0f,
    tripUuid = "uuid-test-1",
    isMockLocation = false,
    sampleCount = 100,
    eucstatsStatus = 1,
)

private val SAMPLE_CSV = "Date,Speed\n01.06.2026 20:00:00.000,10\n".toByteArray()

private fun makeRepo(
    api: FakeEucStatsApi = FakeEucStatsApi(),
    attestation: Attestation = StubAttestation(),
    settings: FakeSettingsPort = FakeSettingsPort(initialStoreId = "store-1"),
    tripDao: FakeTripDao = FakeTripDao(),
    clock: () -> Long = { 1_000_000L },
): Triple<EucStatsRepository, FakeEucStatsApi, FakeSettingsPort> {
    val repo = EucStatsRepository(
        api = api,
        attestation = attestation,
        settings = settings,
        tripDao = tripDao,
        tripFileBytes = { SAMPLE_CSV },
        appVersion = "1.0.0",
        osVersion = "14",
        clock = clock,
    )
    return Triple(repo, api, settings)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class EucStatsRepositoryTest {

    private lateinit var api: FakeEucStatsApi
    private lateinit var settingsPort: FakeSettingsPort
    private lateinit var tripDao: FakeTripDao
    private lateinit var repo: EucStatsRepository

    @Before fun setUp() {
        api = FakeEucStatsApi()
        settingsPort = FakeSettingsPort()
        tripDao = FakeTripDao()
        repo = EucStatsRepository(
            api = api,
            attestation = StubAttestation(),
            settings = settingsPort,
            tripDao = tripDao,
            tripFileBytes = { SAMPLE_CSV },
            appVersion = "1.0.0",
            osVersion = "14",
            clock = { 12345L },
        )
    }

    // -----------------------------------------------------------------------
    // register()
    // -----------------------------------------------------------------------

    @Test fun register_persistsStoreIdAndEnablesUpload() = runBlocking {
        val result = repo.register("Alice", "NO", "base64avatar==")

        assertEquals(RegisterResult.Ok, result)
        val saved = settingsPort.get()
        // The store_id is now persisted to the rider-id file (here: the
        // FakeSettingsPort's in-memory slot), NOT to AppSettings. Display
        // name / flag / consent / registered-at all live on the server now
        // and are fetched via api.getCard, so they have no settings home.
        assertNotNull("store_id must be persisted to the rider-id file",
            settingsPort.riderStoreId())
        assertTrue("onlineUploadEnabled must be true", saved.onlineUploadEnabled)
    }

    @Test fun register_callsApiWithAttestationObject() = runBlocking {
        repo.register("Bob", "US", "avatar==")

        assertEquals(1, api.registerCalls.size)
        val payload = api.registerCalls.first()
        assertTrue("payload must contain attestation", payload.has("attestation"))
        val att = payload.getJSONObject("attestation")
        assertTrue("attestation must have type", att.has("type"))
        assertTrue("attestation must have token", att.has("token"))
        assertTrue("attestation must have request_hash", att.has("request_hash"))
    }

    @Test fun register_attestationRequestHashMatchesCanonicalHash() = runBlocking {
        repo.register("Carol", "DE", "avatar==")

        val payload = api.registerCalls.first()
        val att = payload.getJSONObject("attestation")
        val sentHash = att.getString("request_hash")

        // Reproduce what the repo does: build payload without attestation, hash it.
        val payloadWithoutAtt = JSONObject(payload.toString()).also { it.remove("attestation") }
        val expectedHash = CanonicalJson.requestHash(payloadWithoutAtt)

        assertEquals(
            "request_hash must equal CanonicalJson.requestHash of payload-minus-attestation",
            expectedHash,
            sentHash,
        )
    }

    @Test fun register_usesExistingStoreIdIfAlreadySet() = runBlocking {
        settingsPort.writeRiderId("existing-id")
        repo.register("Dave", "FR", "avatar==")

        val payload = api.registerCalls.first()
        assertEquals("existing-id", payload.getString("store_id"))
        assertEquals("existing-id", settingsPort.riderStoreId())
    }

    @Test fun register_returnsFailedWhenApiFails() = runBlocking {
        api.registerResult = RegisterResult.Failed(409, "display_name_taken")
        val result = repo.register("Eve", "SE", "avatar==")
        assertTrue(result is RegisterResult.Failed)
        // Settings must NOT be updated on failure.
        assertFalse(settingsPort.get().onlineUploadEnabled)
    }

    @Test fun register_refreshesCardOnSuccess() = runBlocking {
        repo.register("Frank", "NO", "avatar==")
        // getCard is called once during refreshCard inside register.
        assertTrue(api.getCardCallCount >= 1)
    }

    // -----------------------------------------------------------------------
    // uploadTrip() — Ok
    // -----------------------------------------------------------------------

    @Test fun uploadTrip_okSetsStatus2AndReturnsUploaded() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.Ok("validated", false)
        tripDao.trips.add(SAMPLE_TRIP)

        val outcome = repo.uploadTrip(SAMPLE_TRIP)

        assertEquals(Outcome.UPLOADED, outcome)
        val updated = tripDao.updates.last()
        assertEquals(2, updated.eucstatsStatus)
        assertEquals(12345L, updated.eucstatsUploadedAt)
        assertEquals("validated", updated.eucstatsValidation)
    }

    @Test fun uploadTrip_okCallsRefreshCard() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.Ok("validated", false)
        tripDao.trips.add(SAMPLE_TRIP)
        val beforeCount = api.getCardCallCount

        repo.uploadTrip(SAMPLE_TRIP)

        assertTrue(api.getCardCallCount > beforeCount)
    }

    // -----------------------------------------------------------------------
    // uploadTrip() — PermanentFailure
    // -----------------------------------------------------------------------

    @Test fun uploadTrip_permanentFailureSetsStatus3() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.PermanentFailure(422, "bad schema")
        tripDao.trips.add(SAMPLE_TRIP)

        val outcome = repo.uploadTrip(SAMPLE_TRIP)

        assertEquals(Outcome.FAILED_PERMANENT, outcome)
        val updated = tripDao.updates.last()
        assertEquals(3, updated.eucstatsStatus)
    }

    // -----------------------------------------------------------------------
    // uploadTrip() — Retry
    // -----------------------------------------------------------------------

    @Test fun uploadTrip_retryLeavesStatusUnchangedAndReturnsNeedsRetry() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.Retry(503, null)
        tripDao.trips.add(SAMPLE_TRIP)

        val outcome = repo.uploadTrip(SAMPLE_TRIP)

        assertEquals(Outcome.NEEDS_RETRY, outcome)
        // tripDao.update must NOT have been called (status stays at 1).
        assertTrue(
            "No update should be written for Retry",
            tripDao.updates.none { it.eucstatsStatus != 1 && it.id == SAMPLE_TRIP.id }
        )
    }

    // -----------------------------------------------------------------------
    // uploadTrip() — AuthFailure → re-mint once
    // -----------------------------------------------------------------------

    @Test fun uploadTrip_authFailureRemintsTokenOnce() = runBlocking {
        settingsPort.writeRiderId("store-1")
        // First call → AuthFailure, second call (re-mint) → Retry.
        api.enqueueUploadResult(UploadResult.AuthFailure(401), UploadResult.Retry(503, null))
        tripDao.trips.add(SAMPLE_TRIP)

        val outcome = repo.uploadTrip(SAMPLE_TRIP)

        assertEquals(Outcome.NEEDS_RETRY, outcome)
        // Two upload attempts must have been made.
        assertEquals(2, api.uploadCalls.size)
        // Status must NOT be updated to 2 or 3.
        assertTrue(tripDao.updates.none { it.eucstatsStatus in listOf(2, 3) })
    }

    @Test fun uploadTrip_authFailureThenOkUploads() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.enqueueUploadResult(UploadResult.AuthFailure(401), UploadResult.Ok("validated", false))
        tripDao.trips.add(SAMPLE_TRIP)

        val outcome = repo.uploadTrip(SAMPLE_TRIP)

        assertEquals(Outcome.UPLOADED, outcome)
        assertEquals(2, api.uploadCalls.size)
        assertTrue(tripDao.updates.any { it.eucstatsStatus == 2 })
    }

    // -----------------------------------------------------------------------
    // uploadTrip() — meta attestation object verification
    // -----------------------------------------------------------------------

    @Test fun uploadTrip_postedMetaContainsAttestationWithCorrectRequestHash() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.Ok("validated", false)
        tripDao.trips.add(SAMPLE_TRIP)

        repo.uploadTrip(SAMPLE_TRIP)

        val (metaJson, _) = api.uploadCalls.first()
        val meta = JSONObject(metaJson)
        assertTrue("meta must contain attestation", meta.has("attestation"))
        val att = meta.getJSONObject("attestation")

        // Strip attestation from meta and recompute hash — must match the sent request_hash.
        val metaWithoutAtt = JSONObject(metaJson).also { it.remove("attestation") }
        val expectedHash = CanonicalJson.requestHash(metaWithoutAtt)
        assertEquals(expectedHash, att.getString("request_hash"))
    }

    @Test fun uploadTrip_csvIsGzippedBeforeSend() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.uploadResult = UploadResult.Ok(null, false)
        tripDao.trips.add(SAMPLE_TRIP)

        repo.uploadTrip(SAMPLE_TRIP)

        val (_, gz) = api.uploadCalls.first()
        // GZIP magic bytes: 1f 8b
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])
    }

    // -----------------------------------------------------------------------
    // refreshCard()
    // -----------------------------------------------------------------------

    @Test fun refreshCard_updatesStateFlow() = runBlocking {
        settingsPort.writeRiderId("store-x")
        api.cardResult = RiderCard(
            displayName = "Test", flag = "NO", hasAvatar = false, avatarUrl = null,
            totalKm = 50.0, trips = 5, topSpeedKmh = 30.0, maxGforce = 0.0,
            mileageRank = null, country = null,
        )
        repo.refreshCard()
        assertEquals("Test", repo.card.value?.displayName)
    }

    @Test fun refreshCard_doesNothingWhenNoStoreId() = runBlocking {
        settingsPort.deleteRiderId()
        repo.refreshCard()
        assertEquals(0, api.getCardCallCount)
        assertNull(repo.card.value)
    }

    // -----------------------------------------------------------------------
    // editProfile()
    // -----------------------------------------------------------------------

    @Test fun editProfile_returnsHttpCode() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.patchResult = 200
        val code = repo.editProfile(displayName = "New Name")
        assertEquals(200, code)
        assertEquals(1, api.patchCalls.size)
    }

    @Test fun editProfile_returns400WhenNoStoreId() = runBlocking {
        settingsPort.deleteRiderId()
        val code = repo.editProfile(displayName = "Name")
        assertEquals(400, code)
        assertTrue(api.patchCalls.isEmpty())
    }

    // -----------------------------------------------------------------------
    // deleteAccount()
    // -----------------------------------------------------------------------

    @Test fun deleteAccount_clearsLocalSettingsAndDisablesUpload() = runBlocking {
        settingsPort.writeRiderId("store-del")
        settingsPort.update(AppSettings(onlineUploadEnabled = true))
        api.deleteResult = true

        val ok = repo.deleteAccount()

        assertTrue(ok)
        // After delete the rider-id file is gone and the upload toggle is off.
        // No other eucstats local state exists anymore. Display name / flag /
        // consent all live on the server, which the API call already wiped.
        assertNull(settingsPort.riderStoreId())
        assertFalse(settingsPort.get().onlineUploadEnabled)
        assertNull(repo.card.value)
    }

    @Test fun deleteAccount_returnsFalseWhenApiFails() = runBlocking {
        settingsPort.writeRiderId("store-1")
        settingsPort.update(AppSettings(onlineUploadEnabled = true))
        api.deleteResult = false

        val ok = repo.deleteAccount()

        assertFalse(ok)
        // Settings must be unchanged.
        assertTrue(settingsPort.get().onlineUploadEnabled)
    }

    // -----------------------------------------------------------------------
    // exportData()
    // -----------------------------------------------------------------------

    @Test fun exportData_returnsJsonFromApi() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.exportResult = """{"trips":[]}"""

        val data = repo.exportData()

        assertEquals("""{"trips":[]}""", data)
        assertTrue(api.exportCalled)
    }

    @Test fun exportData_returnsNullWhenNoStoreId() = runBlocking {
        settingsPort.deleteRiderId()
        val data = repo.exportData()
        assertNull(data)
        assertFalse(api.exportCalled)
    }

    // -----------------------------------------------------------------------
    // fetchProfile()
    // -----------------------------------------------------------------------

    @Test fun fetchProfile_returnsProfileFromApi() = runBlocking {
        settingsPort.writeRiderId("store-1")
        api.profileResult = RiderProfile(
            displayName = "Alice", flag = "NO", hasAvatar = true, avatarUrl = null,
            canChangeNameAfter = null, canChangeFlagAfter = "2026-12-01", canChangeAvatarAfter = null,
        )

        val profile = repo.fetchProfile()

        assertNotNull(profile)
        assertEquals("Alice", profile!!.displayName)
        assertEquals("NO", profile.flag)
        assertEquals("2026-12-01", profile.canChangeFlagAfter)
        assertEquals(1, api.getProfileCallCount)
    }

    @Test fun fetchProfile_returnsNullWhenNoStoreId() = runBlocking {
        settingsPort.deleteRiderId()

        val profile = repo.fetchProfile()

        assertNull(profile)
        assertEquals(0, api.getProfileCallCount)
    }
}

// ---------------------------------------------------------------------------
// Gating helper tests
// ---------------------------------------------------------------------------

class ProfileEditGatingTest {

    @Test fun isEditable_nullCanChangeAfter_isEditable() {
        assertTrue(com.eried.eucplanet.ui.settings.eucstats.isEditableOn(null, java.time.LocalDate.of(2026, 6, 4)))
    }

    @Test fun isEditable_todayIsExactlyGateDate_isEditable() {
        assertTrue(com.eried.eucplanet.ui.settings.eucstats.isEditableOn("2026-06-04", java.time.LocalDate.of(2026, 6, 4)))
    }

    @Test fun isEditable_gateDateInPast_isEditable() {
        assertTrue(com.eried.eucplanet.ui.settings.eucstats.isEditableOn("2026-01-01", java.time.LocalDate.of(2026, 6, 4)))
    }

    @Test fun isEditable_gateDateInFuture_notEditable() {
        assertFalse(com.eried.eucplanet.ui.settings.eucstats.isEditableOn("2026-12-31", java.time.LocalDate.of(2026, 6, 4)))
    }

    @Test fun isEditable_unparsableDate_treatedAsEditable() {
        assertTrue(com.eried.eucplanet.ui.settings.eucstats.isEditableOn("not-a-date", java.time.LocalDate.of(2026, 6, 4)))
    }
}
