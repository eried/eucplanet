package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.AppSettings

/**
 * Which of the three background upload passes have anywhere to send to.
 *
 * Each pass used to be registered for every rider, every quarter of an hour,
 * and found out it had nothing to do only once the phone had woken the app to
 * run it. The questions here are the same ones each worker asks first, so a
 * pass that would return at once is simply not scheduled.
 */
data class PeriodicUploads(
    val intervalMin: Int,
    /** A backup folder is chosen. */
    val folder: Boolean,
    /** Leaderboard uploads are on. */
    val leaderboard: Boolean,
    /** Dropbox is linked. */
    val dropbox: Boolean,
) {
    companion object {
        fun of(settings: AppSettings) = PeriodicUploads(
            intervalMin = settings.pendingUploadIntervalMin,
            folder = settings.syncFolderUri != null,
            leaderboard = settings.onlineUploadEnabled,
            dropbox = settings.dropboxAccessToken.isNotBlank(),
        )
    }
}
