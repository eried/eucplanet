package com.eried.eucplanet.data.sync

/** Outcome of SyncManager.ensureRiderIdFile. */
enum class RiderFileResult {
    /** No folder or no registered rider, nothing to do. */
    SKIPPED,
    /** The file already holds this rider's id. */
    ALREADY_PRESENT,
    /** The file was absent and has now been written with this rider's id. */
    WROTE,
    /** A DIFFERENT rider's id is in the file; left untouched so the caller can warn. */
    MISMATCH,
}
