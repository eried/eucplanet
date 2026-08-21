package com.eried.eucplanet.data.sync

/** User decision when the app DB and backup folder share trip file names. */
enum class SyncChoice { APP, FOLDER, IGNORE, CANCEL }

/** Which destination the conflict dialog is currently resolving — drives the
 *  button labels ("Copy Backup to App" vs "Copy Dropbox to App"). */
enum class SyncConflictKind { FOLDER, DROPBOX }

/** Terminal outcome of a sync run, consumed by the UI to show a toast/snackbar. */
sealed interface SyncResult {
    data object NoFolder : SyncResult
    data class Finished(val count: Int) : SyncResult

    /** Nothing to transfer: every trip is already backed up (a settings/themes
     *  mirror may still have run silently). Shown instead of "0 trips". */
    data object UpToDate : SyncResult

    /**
     * Dropbox rate-limited the account and kept doing so through the retries,
     * so [done] of [total] trips made it and the rest were left for later.
     *
     * Worth its own result rather than a count: a rider pulling a big library
     * sees a number that stops climbing, and without this the app cannot tell
     * them whether their trips are missing, their network died, or Dropbox is
     * simply asking everyone to slow down.
     */
    data class RateLimited(val done: Int, val total: Int) : SyncResult
}
