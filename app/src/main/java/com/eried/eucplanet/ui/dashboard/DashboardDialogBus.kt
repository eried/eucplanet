package com.eried.eucplanet.ui.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets surfaces that live outside the dashboard composition — specifically the
 * floating service-mode debug overlay — ask the dashboard to open one of its
 * local dialogs (About, Service Mode). Those dialogs are dashboard-local state,
 * so the overlay can't toggle them directly; it posts a request here and
 * navigates to the dashboard, which observes [pending], opens the matching
 * dialog, and calls [consume].
 *
 * Values: "about" → About dialog, "service" → Service Mode / diagnostics dialog.
 */
object DashboardDialogBus {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun open(which: String) { _pending.value = which }
    fun consume() { _pending.value = null }
}
