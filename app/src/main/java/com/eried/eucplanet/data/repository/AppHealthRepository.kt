package com.eried.eucplanet.data.repository

import android.Manifest
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
    fun refreshPermissionWarnings() {
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
    }
}
