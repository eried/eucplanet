package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.ADVANCED_SPECS
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsStore
) {
    // App-scoped so a fire-and-forget write survives a short-lived caller (e.g.
    // the scan sheet being dismissed the instant the rider picks a wheel).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<AppSettings> = store.settings.map { it.sanitized() }

    suspend fun get(): AppSettings = store.get().sanitized()

    suspend fun update(settings: AppSettings) {
        store.update(settings)
    }

    /** Read-modify-write wrapper for callers that only want to change a
     *  field or two without echoing the whole [AppSettings] copy. Single
     *  read + write inside the same coroutine, so there's no torn-write
     *  window against the StateFlow. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        update(transform(get()))
    }

    suspend fun updateLastDevice(address: String, name: String) {
        val current = get()
        update(current.copy(lastDeviceAddress = address, lastDeviceName = name))
    }

    /** Fire-and-forget [updateLastDevice] on the repository's own scope, so the
     *  persist can't be cancelled by a caller whose lifecycle just ended. */
    fun updateLastDeviceAsync(address: String, name: String) {
        scope.launch { updateLastDevice(address, name) }
    }

    private fun AppSettings.sanitized(): AppSettings = copy(
        autoRecordStopIdleSeconds = autoRecordStopIdleSeconds.coerceAtLeast(30),
        // Clamp every Advanced knob to its spec range so a 0 / negative / absurd
        // value (from an imported or Dropbox-synced settings file, not just the
        // steppers) can never busy-loop a delay(), divide by zero, or starve the
        // BLE/IO loops. Every settings read — get() and the settings Flow —
        // passes through here, so consumers never see an unsafe value.
        advanced = ADVANCED_SPECS.fold(advanced) { a, s -> s.set(a, s.get(a).coerceIn(s.range)) },
    )
}
