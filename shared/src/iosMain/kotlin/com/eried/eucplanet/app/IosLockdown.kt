package com.eried.eucplanet.app

import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.eried.eucplanet.data.repository.LegalLockdownController
import com.eried.eucplanet.data.store.LegalLockdownStore
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.lockdown.LegalLockdownHost
import com.eried.eucplanet.ui.lockdown.LegalLockdownViewModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Legal Mode Lockdown on iOS: the shared controller over its own preferences
 * file, as Android's legal_lockdown store.
 *
 * The file sits in Application Support, in a folder marked excluded from
 * backup, so the lock does not ride an iCloud or Finder backup onto a new
 * phone, as Android's backup rules keep it out of auto-backup. A reinstall is
 * clean, which is the documented way back when the code is lost.
 */
internal object IosLockdown {
    private const val TAG = "IosLockdown"

    @OptIn(ExperimentalForeignApi::class)
    private val folder: String by lazy {
        val base = NSFileManager.defaultManager.URLForDirectory(
            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
        ) as NSURL
        val dir = base.URLByAppendingPathComponent("lockdown", isDirectory = true)!!
        NSFileManager.defaultManager.createDirectoryAtURL(dir, true, null, null)
        val excluded = dir.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        if (!excluded) Log.w(TAG, "could not exclude the lockdown folder from backup")
        dir.path!!
    }

    private val store: LegalLockdownStore by lazy {
        LegalLockdownStore(
            PreferenceDataStoreFactory.createWithPath(
                produceFile = { "$folder/legal_lockdown.preferences_pb".toPath() },
            ),
        )
    }

    /**
     * Seeded with a read of the file, so a phone that was locked when the app
     * closed opens on the locked screen, never a frame of the dashboard.
     */
    val controller: LegalLockdownController by lazy {
        LegalLockdownController(store, initial = runBlocking { store.get() })
    }

    /**
     * What EucPlanetApp does on Android: service mode and diagnostics stop
     * while the mode runs (DiagnosticsLogger is an object with no injection).
     * Trip recording and the voice read the controller themselves.
     */
    fun start() {
        MainScope().launch {
            controller.engaged.collect { engaged ->
                DiagnosticsLogger.lockedDown = engaged
                if (engaged) DiagnosticsLogger.disable()
            }
        }
    }

    fun viewModel(): LegalLockdownViewModel = LegalLockdownViewModel(
        wheelRepository = IosAppGraph.wheel,
        settingsRepository = IosAppGraph.settings,
        tripMeterRepository = IosTripMeter.meter,
        lockdown = controller,
    )

    /** The locked screen's iOS side. The left-edge swipe is swallowed, as Android's back is. */
    object Host : LegalLockdownHost {
        override val versionName: String =
            NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "?"

        @Composable
        override fun BackHandler(onBack: () -> Unit) = IosBackHandler(onBack = onBack)
    }
}
