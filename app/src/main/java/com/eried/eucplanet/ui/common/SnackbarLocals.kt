package com.eried.eucplanet.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Per-screen snackbar host plumbing exposed through composition locals so deeply
 * nested composables (status icons, trip-row cells, dialog buttons, etc.) can
 * fire a snackbar without prop-drilling the host through every layer.
 *
 * The screen's root provides both locals, the snackbar host lives inside the
 * Scaffold's `snackbarHost` slot, and consumers call [showSnackbarOrToast] to
 * post a message. Following the project convention every user-facing transient
 * uses a Material 3 Snackbar rather than the native Android Toast (which sits
 * behind the soft keyboard and doesn't match the rest of the UI).
 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }
val LocalSnackbarScope = staticCompositionLocalOf<CoroutineScope?> { null }

/**
 * Fire a snackbar via [LocalSnackbar] when both locals are provided. No-op if
 * the screen forgot to provide them (so a missing provider can't crash a
 * release build); in debug we surface the gap via a Logcat warning.
 */
fun showSnackbar(
    host: SnackbarHostState?,
    scope: CoroutineScope?,
    message: String
) {
    if (host == null || scope == null) return
    scope.launch { host.showSnackbar(message) }
}
