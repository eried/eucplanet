package com.eried.eucplanet.data.eucstats

// The leaderboard's answers, as the app and the settings screen read them.

data class RiderCard(
    val displayName: String?, val flag: String?, val hasAvatar: Boolean, val avatarUrl: String?,
    val totalKm: Double, val trips: Int, val topSpeedKmh: Double, val maxGforce: Double,
    val mileageRank: Int?, val country: String?,
)

/**
 * Full rider profile from GET /riders/{storeId}.
 * Includes the `can_change_*` date strings (ISO yyyy-MM-dd or null) that gate
 * how soon the rider may edit each field again after a previous change.
 */
data class RiderProfile(
    val displayName: String?,
    val flag: String?,
    val hasAvatar: Boolean,
    val avatarUrl: String? = null,
    val canChangeNameAfter: String?,
    val canChangeFlagAfter: String?,
    val canChangeAvatarAfter: String?,
)

/**
 * Current server-side verdict for one trip, from GET /trips/{uuid}.
 *
 * The upload response is a snapshot, not a final answer: a trip returned as "flagged" is
 * held for a human to look at, and the verdict changes server-side once they approve it.
 * Without re-reading it, the rider keeps an "under review" cloud forever on a ride that
 * was accepted days ago. Re-uploading cannot clear it either, because the same trip_uuid
 * hits the server's dedupe and returns the same status.
 */
data class TripStatus(
    val validationStatus: String?,   // "validated" | "flagged" | "rejected"
    val verdict: String?,            // "accepted" | "under_review" | "rejected"
    val reasons: List<String>,
)

sealed interface UploadResult {
    data class Ok(val validationStatus: String?, val duplicate: Boolean) : UploadResult
    data class PermanentFailure(val code: Int, val body: String) : UploadResult  // 400/403/413/422
    data class AuthFailure(val code: Int) : UploadResult                          // 401 (re-mint)
    data class Retry(val code: Int, val retryAfterSec: Long?) : UploadResult      // 429/5xx/network
}

/**
 * Result of POST /riders. Per the eucstats rate-limit spec, only a 429
 * (`rate_limited:rider_create`, per IP) is retryable -- re-registering an
 * existing store_id is never limited; anything else is terminal for the attempt.
 */
sealed interface RegisterResult {
    data object Ok : RegisterResult                            // 2xx with a body (store_id is client-generated)
    data object RateLimited : RegisterResult                   // 429 rate_limited:rider_create
    /** Any other failure. [code] is the HTTP status (0 = network / no response);
     *  [detail] is the server's "detail" string when present (e.g. display_name_taken). */
    data class Failed(val code: Int, val detail: String?) : RegisterResult
}
