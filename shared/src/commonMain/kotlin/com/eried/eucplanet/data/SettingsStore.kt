package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the live [AppSettings] as an observable [StateFlow]. In-memory for now —
 * persistence (kotlinx.serialization to a platform file/UserDefaults) lands with
 * the storage actuals; this is the seam the Settings UI, alarm engine and
 * automations all read/write through, so swapping in a persistent backing later
 * is a one-file change.
 */
class SettingsStore {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    /** Apply a copy-transform to the settings (e.g. `update { it.copy(ttsEnabled = true) }`). */
    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
    }
}
