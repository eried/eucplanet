package com.eried.eucplanet.data.sync

/**
 * Result of a settings-backup attempt. [AlreadyExists] is only ever returned
 * when the caller asked not to overwrite.
 */
sealed interface BackupOutcome {
    data object Saved : BackupOutcome
    data object AlreadyExists : BackupOutcome
    data object Failed : BackupOutcome
}

/**
 * One row in the restore picker. [label] = null is the default backup
 * (`eucplanet_settings.json`); non-null is the rider-supplied snapshot name.
 */
data class BackupEntry(val fileName: String, val label: String?, val isFactory: Boolean = false)

/**
 * The rider identity carried by a settings backup, read without applying it.
 * Used to offer "restore your existing rider" when a sync folder is linked.
 */
data class RestorableRider(val fileName: String, val storeId: String)
