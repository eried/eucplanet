package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The `legal_lockdown` preferences file behind [LegalLockdownStore], which is
 * shared code now. Its own file, never the settings store; see the store for
 * why. Excluded from auto-backup in `backup_rules.xml` and
 * `data_extraction_rules.xml`.
 */
val Context.lockdownDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "legal_lockdown")
