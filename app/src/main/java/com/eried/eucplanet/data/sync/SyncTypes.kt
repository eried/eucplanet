package com.eried.eucplanet.data.sync

/** User decision when the app DB and backup folder share trip file names. */
enum class SyncChoice { APP, FOLDER, IGNORE, CANCEL }

/** Terminal outcome of a sync run, consumed by the UI to show a toast/snackbar. */
sealed interface SyncResult {
    data object NoFolder : SyncResult
    data class Finished(val count: Int) : SyncResult
}
