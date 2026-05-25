package com.eried.eucplanet.ui.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists whether the user has seen the experimental-banner explainer dialog.
 *
 * Backed by SharedPreferences (not Room) so flipping it doesn't trigger a
 * destructive Room migration that would wipe the user's settings, alarms,
 * Flic mappings, and trip data. The flag is one boolean, a database column
 * for it would be overkill.
 *
 * Set to true when the user actually taps "Report an issue". Cancelling the
 * dialog leaves it false so the explainer shows again next click, that's
 * the user's stated UX preference.
 */
@Singleton
class ExperimentalBannerState @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var explainerSeen: Boolean
        get() = prefs.getBoolean(KEY_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN, value).apply()
        }

    companion object {
        private const val PREFS = "experimental_banner"
        private const val KEY_SEEN = "explainer_seen"
    }
}
