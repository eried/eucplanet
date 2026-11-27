package com.eried.eucplanet.app

/**
 * Whether this failure means the phone is out of storage, the iPhone's half
 * of Android's util/StorageFull.kt.
 *
 * iOS has no exception type for it, so the message decides: DataStore's file
 * writes carry the POSIX text for ENOSPC, the trip file carries NSError's
 * description of the same ("isn't enough space"), and the bundled SQLite says "database or disk is
 * full" (SQLITE_FULL).
 */
internal fun Throwable.isOutOfStorage(): Boolean {
    var t: Throwable? = this
    repeat(8) {
        val cur = t ?: return false
        val message = cur.message.orEmpty()
        if (STORAGE_FULL_MARKS.any { message.contains(it, ignoreCase = true) }) return true
        t = cur.cause
    }
    return false
}

private val STORAGE_FULL_MARKS = listOf(
    "ENOSPC", "SQLITE_FULL", "SQLITE_IOERR_SHMSIZE",
    "No space left on device", "disk is full", "enough space",
)
