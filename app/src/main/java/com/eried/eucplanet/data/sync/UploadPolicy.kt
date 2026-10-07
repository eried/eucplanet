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
        val localSec = localModifiedMs / 1000L
        // The file still wears the timestamp Dropbox stored it under, so
        // nothing has rewritten it since. This is the common case and the one
        // worth being exact about: it is an equality, not a comparison of two
        // clocks, so it holds however far apart the phone and Dropbox are.
        if (localSec == remoteModifiedSec) return false
        // Their copy is newer: changed somewhere else, and this phone's is the
        // stale one. A trip renamed in another tool lands here.
        if (remoteModifiedSec > localSec + EDIT_GRACE_SEC) return false
        // Ours is newer: something on this phone wrote the file after Dropbox
        // last stored it.
        //
        // Length alone used to decide this, on the grounds that trip CSVs are
        // append-only - true while recording, false for an edit. Renaming
        // writes the name into the file, so "Coast road" to "Beach ride"
        // changed the content and not one byte of the length, and the rename
        // never left the phone. Verified against a real account: the app said
        // "uploaded 0" and Dropbox kept the old name.
        if (localSec > remoteModifiedSec) return true
        // Neither: fall back to what the old rule knew.
        return remoteSize != localSize
    }
}
