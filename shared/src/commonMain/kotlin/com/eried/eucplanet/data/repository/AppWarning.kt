package com.eried.eucplanet.data.repository

import org.jetbrains.compose.resources.StringResource

/**
 * A single user-actionable warning surfaced in the dashboard top bar.
 *
 * [id] dedupes so the same source can call [AppHealthRepository.upsert] repeatedly
 * without producing duplicates. [fix] runs on the UI thread when the rider taps
 * the Fix button, typically opens the system Settings App-Details page, but
 * future warning sources (failed trip import, …) can pass any handler.
 */
data class AppWarning(
    val id: String,
    val titleRes: StringResource,
    val bodyRes: StringResource,
    val fix: () -> Unit = {},
    /**
     * Settings tab to open instead of running [fix], for the warnings whose
     * remedy is inside the app rather than in system settings. The dashboard
     * knows how to navigate; this layer only says where to.
     */
    val settingsTab: Int? = null,
)
