package com.eried.eucplanet.data.sync

/**
 * Whether this phone's copy of a trip should go up to Dropbox.
 *
 * Pulled out of the worker so it can be tested with actual numbers. It lived
 * inline as a comparison between a timestamp in seconds and one in
 * milliseconds - a comparison that is never true - and the tests, which read
 * the source rather than ran it, all passed. Only watching the app overwrite a
 * renamed file on a real Dropbox account showed it.
 */
object UploadPolicy {

    /**
     * How far Dropbox's copy must be ahead of this phone's before it counts as
     * someone else's edit rather than clock skew.
     *
     * In seconds, matching what Dropbox reports. An edit made in another tool
     * is hours or days later, so slack this small still lets a local change win
     * when the two happen close together.
     */
    const val EDIT_GRACE_SEC = 2L * 60

    /**
     * @param remoteSize bytes Dropbox holds, or null when it holds nothing
     * @param remoteModifiedSec Dropbox's own timestamp, in SECONDS
     * @param localSize bytes on this phone
     * @param localModifiedMs when this phone last wrote the file, in MILLISECONDS
     */
    fun needsUpload(
        remoteSize: Long?,
        remoteModifiedSec: Long,
        localSize: Long,
        localModifiedMs: Long,
    ): Boolean {
        // Dropbox has nothing by that name.
        if (remoteSize == null) return true
        // Trip CSVs are append-only, so equal length means equal content.
        if (remoteSize == localSize) return false
        // The two differ, and "ours is different" is not a reason to overwrite
        // theirs: renaming a trip in another tool writes the name into the file
        // and changes its length. Ask which copy is newer instead.
        return remoteModifiedSec <= localModifiedMs / 1000L + EDIT_GRACE_SEC
    }
}
