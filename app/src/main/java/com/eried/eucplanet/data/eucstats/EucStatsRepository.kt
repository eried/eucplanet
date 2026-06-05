package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a single [EucStatsRepository.uploadTrip] call. */
enum class Outcome { UPLOADED, FAILED_PERMANENT, NEEDS_RETRY }

/**
 * Narrow settings port: exposes only the eucstats-relevant AppSettings
 * fields so [EucStatsRepository] stays JVM-testable without a DataStore
 * or Android Context.
 */
interface EucStatsSettingsPort {
    suspend fun get(): AppSettings
    suspend fun update(settings: AppSettings)
}

@Singleton
class EucStatsRepository @Inject constructor(
    private val api: EucStatsApiContract,
    private val attestation: Attestation,
    private val settings: EucStatsSettingsPort,
    private val tripDao: TripDao,
    /** Reads the raw CSV bytes for a given trip — injected so no Android FS needed in tests. */
    @EucStatsTripFileBytes private val tripFileBytes: @JvmSuppressWildcards (TripRecord) -> ByteArray,
    @EucStatsAppVersion private val appVersion: String,
    @EucStatsOsVersion private val osVersion: String,
    /** Millisecond clock, injectable for tests. */
    @EucStatsClock private val clock: @JvmSuppressWildcards () -> Long = { System.currentTimeMillis() },
) {

    private val _card = MutableStateFlow<RiderCard?>(null)
    val card: StateFlow<RiderCard?> = _card

    /** True once a card fetch has completed (success or failure), so the UI can
     *  tell "still loading" apart from "tried and got nothing" and stop spinning. */
    private val _cardLoaded = MutableStateFlow(false)
    val cardLoaded: StateFlow<Boolean> = _cardLoaded

    /** True when the backend says this rider no longer exists (404) -- e.g. the
     *  dataset was reset or the account was deleted server-side. The UI uses this
     *  to offer re-registration instead of a generic "couldn't load". */
    private val _cardMissing = MutableStateFlow(false)
    val cardMissing: StateFlow<Boolean> = _cardMissing

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Register a new rider (or re-register if not yet persisted).
     * Generates a new UUIDv4 store_id when one doesn't exist yet.
     * Returns true on success.
     */
    suspend fun register(
        displayName: String,
        flag: String,
        avatarPngBase64: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val current = settings.get()
        val storeId = current.eucstatsStoreId ?: UUID.randomUUID().toString()

        // Build the payload without attestation first so we can hash it.
        val payload = JSONObject().apply {
            put("store_id", storeId)
            put("platform", "google_play")
            put("display_name", displayName)
            put("flag", flag)
            put("avatar_png_base64", avatarPngBase64)
            put("consent_public", true)
        }

        // Compute canonical hash over the payload (no attestation key yet) and mint token.
        val hash = CanonicalJson.requestHash(payload)
        val att = attestation.token(hash)
        payload.put(
            "attestation",
            JSONObject().apply {
                put("type", att.type)
                put("token", att.token)
                put("request_hash", att.requestHash)
            }
        )

        val response = api.registerRider(payload) ?: return@withContext false

        // Persist everything on success (non-null response body).
        val now = clock()
        settings.update(
            current.copy(
                eucstatsStoreId = storeId,
                eucstatsDisplayName = displayName,
                eucstatsFlag = flag,
                eucstatsConsentPublic = true,
                eucstatsRegisteredAt = now,
                onlineUploadEnabled = true,
            )
        )
        refreshCard()
        true
    }

    // -------------------------------------------------------------------------
    // Card
    // -------------------------------------------------------------------------

    suspend fun refreshCard() = withContext(Dispatchers.IO) {
        val storeId = settings.get().eucstatsStoreId ?: return@withContext
        try {
            val card = api.getCard(storeId)
            _card.value = card
            // If the card didn't load, find out whether it's because the rider is
            // gone (404) vs a transient/other failure, so the UI can offer to
            // re-register only when the profile truly no longer exists.
            _cardMissing.value = card == null && api.riderExists(storeId) == false
        } finally {
            // Mark the attempt done either way so the UI can stop spinning and
            // show a short fallback when the card came back null.
            _cardLoaded.value = true
        }
    }

    // -------------------------------------------------------------------------
    // Trip upload
    // -------------------------------------------------------------------------

    suspend fun uploadTrip(trip: TripRecord): Outcome = withContext(Dispatchers.IO) {
        val storeId = settings.get().eucstatsStoreId
            ?: return@withContext Outcome.NEEDS_RETRY

        val csv = tripFileBytes(trip)
        val meta = MetaBuilder.build(
            trip = trip,
            storeId = storeId,
            appVersion = appVersion,
            osVersion = osVersion,
            tz = TimeZone.getDefault(),
            csvBytes = csv,
        )

        val hash = CanonicalJson.requestHash(meta)
        val att = attestation.token(hash)
        meta.put(
            "attestation",
            JSONObject().apply {
                put("type", att.type)
                put("token", att.token)
                put("request_hash", att.requestHash)
            }
        )

        val gz = gzip(csv)

        suspend fun doUpload(): UploadResult = api.uploadTrip(meta.toString(), gz)

        when (val result = doUpload()) {
            is UploadResult.Ok -> {
                tripDao.update(
                    trip.copy(
                        eucstatsStatus = 2,
                        eucstatsUploadedAt = clock(),
                        eucstatsValidation = result.validationStatus,
                    )
                )
                refreshCard()
                Outcome.UPLOADED
            }
            is UploadResult.PermanentFailure -> {
                tripDao.update(trip.copy(eucstatsStatus = 3))
                Outcome.FAILED_PERMANENT
            }
            is UploadResult.AuthFailure -> {
                // Re-mint token once and retry.
                val att2 = attestation.token(hash)
                meta.put(
                    "attestation",
                    JSONObject().apply {
                        put("type", att2.type)
                        put("token", att2.token)
                        put("request_hash", att2.requestHash)
                    }
                )
                when (val result2 = api.uploadTrip(meta.toString(), gz)) {
                    is UploadResult.Ok -> {
                        tripDao.update(
                            trip.copy(
                                eucstatsStatus = 2,
                                eucstatsUploadedAt = clock(),
                                eucstatsValidation = result2.validationStatus,
                            )
                        )
                        refreshCard()
                        Outcome.UPLOADED
                    }
                    else -> Outcome.NEEDS_RETRY
                }
            }
            is UploadResult.Retry -> Outcome.NEEDS_RETRY
        }
    }

    /**
     * Foreground sync of every pending eucstats trip, reporting progress via
     * [onProgress] (done, total) so the UI can show a determinate bar like the
     * trips-backup "Sync all". Returns the number of trips that were pending
     * (0 = nothing to sync). Trips that fail stay pending and retry later.
     */
    suspend fun syncPendingNow(onProgress: (done: Int, total: Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val pending = tripDao.getPendingEucstatsUploads()
            val total = pending.size
            if (total == 0) return@withContext 0
            onProgress(0, total)
            var done = 0
            for (trip in pending) {
                runCatching { uploadTrip(trip) }
                done++
                onProgress(done, total)
            }
            total
        }

    // -------------------------------------------------------------------------
    // Profile management
    // -------------------------------------------------------------------------

    /** GET the full rider profile including can_change_* gating dates. */
    suspend fun fetchProfile(): RiderProfile? = withContext(Dispatchers.IO) {
        val storeId = settings.get().eucstatsStoreId ?: return@withContext null
        api.getProfile(storeId)
    }

    /** PATCH the rider profile. Returns the HTTP status code. */
    suspend fun editProfile(
        displayName: String? = null,
        flag: String? = null,
        avatarPngBase64: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        val storeId = settings.get().eucstatsStoreId ?: return@withContext 400
        val payload = JSONObject().apply {
            displayName?.let { put("display_name", it) }
            flag?.let { put("flag", it) }
            avatarPngBase64?.let { put("avatar_png_base64", it) }
        }
        api.patchRider(storeId, payload)
    }

    /** DELETE the rider account, then clear local eucstats settings. */
    suspend fun deleteAccount(): Boolean = withContext(Dispatchers.IO) {
        val current = settings.get()
        val storeId = current.eucstatsStoreId ?: return@withContext false
        val ok = api.deleteRider(storeId)
        if (ok) {
            settings.update(
                current.copy(
                    eucstatsStoreId = null,
                    eucstatsDisplayName = null,
                    eucstatsFlag = null,
                    eucstatsConsentPublic = false,
                    eucstatsRegisteredAt = null,
                    onlineUploadEnabled = false,
                )
            )
            _card.value = null
        }
        ok
    }

    /** GET the rider's exported data JSON. */
    suspend fun exportData(): String? = withContext(Dispatchers.IO) {
        val storeId = settings.get().eucstatsStoreId ?: return@withContext null
        api.exportRider(storeId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }
}
