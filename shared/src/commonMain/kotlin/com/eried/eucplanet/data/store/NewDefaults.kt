package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings

/**
 * Defaults that changed after installs already had the old one stored.
 *
 * Every setting is saved with its value, so changing a default in the code
 * reaches new installs only. Where the old default was costing every rider
 * something, an existing install is moved to the new one, once, and only when
 * it still holds exactly the old default. A value the rider chose is left
 * alone, as far as that can be told: one who had deliberately picked the old
 * default is moved too, and can set it back.
 */
object NewDefaults {
    /** Raise by one with each new entry in [upgrade]. */
    const val REV = 1

    private const val UPLOAD_CHECK_OLD_MIN = 15
    private const val UPLOAD_CHECK_NEW_MIN = 360

    fun upgrade(settings: AppSettings, fromRev: Int): AppSettings {
        var s = settings
        // 1: the background check for uploads left pending. Every quarter of
        // an hour woke the app 96 times a day to find nothing to do; trip end,
        // app start and return to the app already start a pass of their own.
        if (fromRev < 1 && s.pendingUploadIntervalMin == UPLOAD_CHECK_OLD_MIN) {
            s = s.copy(advanced = s.advanced.copy(pendingUploadIntervalMin = UPLOAD_CHECK_NEW_MIN))
        }
        return s
    }
}
