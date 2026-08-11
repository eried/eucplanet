package com.eried.eucplanet.data.repository

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.eried.eucplanet.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single user-actionable warning surfaced in the dashboard top bar.
 *
 * [id] dedupes so the same source can call [AppHealthRepository.upsert] repeatedly
 * without producing duplicates. [fix] runs on the UI thread when the rider taps
 * the Fix button — typically opens the system Settings App-Details page, but
 * future warning sources (failed trip import, …) can pass any handler.
 */
data class AppWarning(
    val id: String,
    val titleRes: Int,
    val bodyRes: Int,
    val fix: () -> Unit = {},
    /**
     * Settings tab to open instead of running [fix], for the warnings whose
     * remedy is inside the app rather than in system settings. The dashboard
     * knows how to navigate; this layer only says where to.
     */
    val settingsTab: Int? = null,
)

/**
 * Aggregates "things the rider should know about" into a single flow that the
 * dashboard top-bar reads. Currently surfaces missing notification permission;
 * structure is deliberately generic so future sources (a failed trip import,
 * a corrupt setting that needs reset, an outdated wheel firmware that we'd
 * like to flag) can call [upsert] / [dismiss] without touching the UI layer.
 *
 * Permission checks run in [refreshPermissionWarnings] and are typically
 * invoked from MainActivity.onResume + after permissionLauncher's callback,
 * so the warning auto-clears when the rider grants the permission in Settings
 * and returns to the app.
 */
@Singleton
class AppHealthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Stored as a list (not a map) so the flow exposes exactly what the UI
    // iterates. Insertion order is preserved so the same warning always
    // appears in the same slot across refreshes.
    private val _warnings = MutableStateFlow<List<AppWarning>>(emptyList())
    val warnings: StateFlow<List<AppWarning>> = _warnings.asStateFlow()

    fun upsert(warning: AppWarning) {
        val current = _warnings.value
        val idx = current.indexOfFirst { it.id == warning.id }
        _warnings.value = if (idx == -1) {
            current + warning
        } else {
            current.toMutableList().also { it[idx] = warning }
        }
    }

    fun dismiss(id: String) {
        // Closing the PIP notice means "understood", so it must not come back
        // on the next resume the way a permission warning does. The rider was
        // told once; the setting has already been turned off to match.
        if (id == PERM_PIP_ID) pipNoticePending = false
        if (id == PERM_OVERLAY_ID) hudNoticePending = false
        val current = _warnings.value
        if (current.any { it.id == id }) {
            _warnings.value = current.filterNot { it.id == id }
        }
    }

    /**
     * Set once PIP has been auto-disabled, so the notice outlives the setting
     * that triggered it. Without this the warning would raise and clear in the
     * same breath: turning pipMode off is exactly what stops us asking.
     */
    @Volatile
    private var pipNoticePending = false

    /** The Phone HUD's equivalent of [pipNoticePending]. */
    @Volatile
    private var hudNoticePending = false

    /**
     * Re-evaluates every permission the dashboard cares about and upserts or
     * dismisses the corresponding warning. Idempotent — safe to call from
     * onResume on every dashboard visit.
     */
    fun refreshPermissionWarnings(
        pipRequested: Boolean = false,
        phoneHudRequested: Boolean = false,
    ) {
        // POST_NOTIFICATIONS only exists on Android 13+. Below TIRAMISU the
        // notification post is implicit, so the warning never applies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                dismiss(PERM_NOTIFICATIONS_ID)
            } else {
                upsert(
                    AppWarning(
                        id = PERM_NOTIFICATIONS_ID,
                        titleRes = R.string.warnings_perm_notifications_title,
                        bodyRes = R.string.warnings_perm_notifications_body,
                        fix = { openAppSettings() }
                    )
                )
            }
        }

        // Android keeps its own per-app picture-in-picture switch, and turning
        // it off there is invisible from in here: the window simply never
        // appears, which reads as a broken feature rather than a setting the
        // rider changed.
        //
        // [pipRequested] is the mismatch - our setting says yes, the system
        // says no - and the caller resolves it by turning our setting off.
        // That would normally clear this warning instantly, so the notice is
        // latched and only released when PIP is allowed again or the rider
        // closes it.
        if (pipAllowed()) {
            pipNoticePending = false
            dismiss(PERM_PIP_ID)
        } else {
            if (pipRequested) pipNoticePending = true
            if (pipNoticePending) {
                upsert(
                    AppWarning(
                        id = PERM_PIP_ID,
                        titleRes = R.string.warnings_pip_blocked_title,
                        bodyRes = R.string.warnings_pip_blocked_body,
                        fix = { openPipSettings() }
                    )
                )
            }
        }

        // The overlay permission has the same shape as PIP: revoked outside the
        // app, invisible from inside it, and the Phone HUD simply stops
        // appearing. Same latch, same reason.
        if (overlayAllowed()) {
            hudNoticePending = false
            dismiss(PERM_OVERLAY_ID)
        } else {
            if (phoneHudRequested) hudNoticePending = true
            if (hudNoticePending) {
                upsert(
                    AppWarning(
                        id = PERM_OVERLAY_ID,
                        titleRes = R.string.warnings_hud_blocked_title,
                        bodyRes = R.string.warnings_hud_blocked_body,
                        fix = { openOverlaySettings() }
                    )
                )
            }
        }

        // Android's battery optimiser will kill the wheel service mid-ride, and
        // what the rider sees is a trip that simply stops recording. Unlike the
        // permission warnings this one is always worth raising: every feature
        // that outlives the screen depends on it.
        if (batteryOptimised()) {
            upsert(
                AppWarning(
                    id = BATTERY_OPT_ID,
                    titleRes = R.string.warnings_battery_opt_title,
                    bodyRes = R.string.warnings_battery_opt_body,
                    fix = { requestIgnoreBatteryOptimizations() }
                )
            )
        } else {
            dismiss(BATTERY_OPT_ID)
        }
    }

    /** True when Android may freeze or kill us in the background. */
    private fun batteryOptimised(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Ask for the battery-optimisation exemption directly.
     *
     * The targeted dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS declared;
     * without it the intent throws and the rider lands on the full list, where
     * the same switch lives a few taps further in.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(direct) }.isSuccess) return
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(list) }.isSuccess) return
        openAppSettings()
    }

    /** Whether the Phone HUD can draw over other apps. */
    fun overlayAllowed(): Boolean = Settings.canDrawOverlays(context)

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
        openAppSettings()
    }

    /**
     * Rides are piling up on the phone with nowhere to go.
     *
     * Nothing is broken yet, which is the point: a rider only discovers this
     * when the phone is lost or wiped and the trips go with it. The existing
     * complaints about a missing folder all fire at the moment you try to sync
     * or save a preset, so someone who never opens those screens is never told.
     *
     * Silent until there is something to lose - a fresh install with no rides
     * has no problem worth naming.
     */
    fun refreshBackupWarning(hasFolder: Boolean, hasTrips: Boolean) {
        if (!hasFolder && hasTrips) {
            upsert(
                AppWarning(
                    id = BACKUP_FOLDER_ID,
                    titleRes = R.string.warnings_no_backup_title,
                    bodyRes = R.string.warnings_no_backup_body,
                    settingsTab = SETTINGS_TAB_BACKUPS,
                )
            )
        } else {
            dismiss(BACKUP_FOLDER_ID)
        }
    }

    /**
     * Whether Android will honour a picture-in-picture request from us.
     *
     * Devices without the feature at all report allowed: there is no switch to
     * send the rider to, so a warning would be a dead end.
     */
    fun pipAllowed(): Boolean {
        if (!context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) return true
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = runCatching {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                context.packageName,
            )
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Open the per-app picture-in-picture switch.
     *
     * The action is not a public constant - android.provider.Settings has no
     * PIP entry - so it is named literally and only used if something answers.
     * App info is the guaranteed fallback: the PIP switch lives inside it on
     * stock Android, so the rider still lands somewhere they can fix this.
     */
    fun openPipSettings() {
        val direct = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS").apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(direct) }.isSuccess) return
        openAppSettings()
    }

    /**
     * Deep-link into the system Settings → App info → Permissions page for
     * EUC Planet. The rider can grant any denied permission there and the
     * warning auto-clears on the next refreshPermissionWarnings() call
     * (MainActivity.onResume will fire it on return).
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    companion object {
        private const val PERM_NOTIFICATIONS_ID = "perm.notifications"
        private const val PERM_PIP_ID = "perm.pip"
        private const val PERM_OVERLAY_ID = "perm.overlay"
        private const val BATTERY_OPT_ID = "power.battery-optimised"
        private const val BACKUP_FOLDER_ID = "backup.folder"

        /** "Backups and leaderboards" in the settings tab order. */
        private const val SETTINGS_TAB_BACKUPS = 4
    }
}
