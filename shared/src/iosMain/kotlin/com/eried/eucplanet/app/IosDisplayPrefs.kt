package com.eried.eucplanet.app

import com.eried.eucplanet.util.AppLanguages
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIApplication

/**
 * The display settings MainActivity applies on Android: the screen kept awake
 * while the app is in front, and the language picker kept in step with the
 * language the app actually runs in.
 */
internal object IosDisplayPrefs {
    fun start() {
        val scope = MainScope()
        scope.launch {
            IosAppGraph.settings.settings
                .map { it.phoneKeepScreenOn }
                .distinctUntilChanged()
                .collect { UIApplication.sharedApplication.idleTimerDisabled = it }
        }
        // As MainActivity does: a blank language (a fresh install) or one the
        // rider changed in the iPhone's own Settings shows the applied one, so
        // the picker is never empty or stale. iOS applies a pick from the
        // picker at the next start, so a launch is the time to read it.
        scope.launch {
            val applied = NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String ?: ""
            val tag = AppLanguages.normalizeToSupportedTag(applied)
                .takeIf { t -> AppLanguages.SUPPORTED.any { it.tag == t } } ?: "en"
            IosAppGraph.settings.update { if (it.language == tag) it else it.copy(language = tag) }
        }
    }
}
