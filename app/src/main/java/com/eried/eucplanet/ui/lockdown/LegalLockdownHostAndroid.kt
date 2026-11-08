package com.eried.eucplanet.ui.lockdown

import androidx.compose.runtime.Composable
import com.eried.eucplanet.BuildConfig

/** The locked screen's Android side: BuildConfig's version and the activity's back dispatcher. */
object AndroidLegalLockdownHost : LegalLockdownHost {
    override val versionName: String = BuildConfig.VERSION_NAME

    @Composable
    override fun BackHandler(onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    }
}
