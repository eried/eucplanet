package com.eried.eucplanet.data.sync

/** What a Dropbox settings sync should do this pass. */
enum class SettingsSyncAction { NONE, UPLOAD, APPLY, CONFLICT }

/**
 * Pure 3-way decision for two-way settings sync. Compares the phone's current
 * settings and Dropbox's copy against the last-synced baseline, so only a
 * genuine both-sides-changed case is a conflict (not every settings edit).
 * All inputs are hashes of the device-stripped settings JSON.
 */
object SettingsSyncPolicy {
    fun decide(phoneHash: String, remoteHash: String?, baseHash: String): SettingsSyncAction = when {
        remoteHash == null -> SettingsSyncAction.UPLOAD   // Dropbox has no settings yet
        phoneHash == remoteHash -> SettingsSyncAction.NONE // already identical
        remoteHash == baseHash -> SettingsSyncAction.UPLOAD // only the phone changed
        phoneHash == baseHash -> SettingsSyncAction.APPLY   // only Dropbox changed
        else -> SettingsSyncAction.CONFLICT                 // both changed
    }
}
