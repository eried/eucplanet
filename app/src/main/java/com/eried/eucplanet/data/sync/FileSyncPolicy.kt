package com.eried.eucplanet.data.sync

/** Result of reconciling one folder both ways. Names are as-listed by the caller. */
data class FileSyncPlan(
    val upload: List<String>,    // on phone only -> send up
    val download: List<String>,  // on Dropbox only -> pull down
    val conflicts: List<String>, // same name, different byte size
)

/**
 * Pure per-file reconciliation for a folder that syncs both ways (themes,
 * overlays), mirroring the trip rule: a name on one side only transfers; a name
 * on both with a different byte size is a conflict; same size is already synced.
 */
object FileSyncPolicy {
    fun decide(localSizes: Map<String, Long>, remoteSizes: Map<String, Long>): FileSyncPlan {
        val local = localSizes.keys
        val remote = remoteSizes.keys
        val upload = (local - remote).sorted()
        val download = (remote - local).sorted()
        val conflicts = (local intersect remote).filter { localSizes[it] != remoteSizes[it] }.sorted()
        return FileSyncPlan(upload, download, conflicts)
    }
}
