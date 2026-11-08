package com.eried.eucplanet.ui.lockdown

import androidx.compose.runtime.Composable

/**
 * What the locked screen asks of its platform: the version line it shows and
 * the system back gesture it swallows. Android's is built from BuildConfig
 * and the activity's back dispatcher; iOS has its own.
 */
interface LegalLockdownHost {
    val versionName: String

    /** Swallows the system back gesture or button while the screen shows. */
    @Composable
    fun BackHandler(onBack: () -> Unit)
}
