package com.eried.eucplanet.data.repository

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
    val fix: () -> Unit
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
        val current = _warnings.value
        if (current.any { it.id == id }) {
            _warnings.value = current.filterNot { it.id == id }
        }
    }

    /**
     * Re-evaluates every permission the dashboard cares about and upserts or
     * dismisses the corresponding warning. Idempotent — safe to call from
     * onResume on every dashboard visit.
     */
    fun refreshPermissionWarnings(pipRequested: Boolean = false) {
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
        // rider changed. Only raised when a PIP mode is actually selected -
        // nobody needs to be told about a permission for a feature they left
        // off.
        if (pipRequested && !pipAllowed()) {
            upsert(
                AppWarning(
                    id = PERM_PIP_ID,
                    titleRes = R.string.warnings_pip_blocked_title,
                    bodyRes = R.string.warnings_pip_blocked_body,
                    fix = { openPipSettings() }
                )
            )
        } else {
            dismiss(PERM_PIP_ID)
        }
    }

    /**
     * Whether Android will honour a picture-in-picture request from us.
     *
     * Devices without the feature at all report allowed: there is no switch to
     * send the rider to, so a warning would be a dead end.
     */
    private fun pipAllowed(): Boolean {
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
    private fun openPipSettings() {
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
    }
}
