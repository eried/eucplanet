package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Holds the live [AppSettings] as an observable [StateFlow] and persists it
 * through a [KeyValueStore] (NSUserDefaults on iOS) as JSON. This is the shared
 * replacement for the Android org.json `SettingsJson`; the Settings UI, alarm
 * engine and automations all read/write through it, so the wheel app keeps the
 * rider's preferences across launches on iOS today.
 */
class SettingsStore(private val kv: KeyValueStore = createKeyValueStore()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    /** Apply a copy-transform and persist (e.g. `update { it.copy(ttsEnabled = true) }`). */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        runCatching { kv.putString(KEY, json.encodeToString(AppSettings.serializer(), next)) }
    }

    private fun load(): AppSettings {
        val raw = kv.getString(KEY) ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrDefault(AppSettings())
    }

    private companion object {
        const val KEY = "app_settings_v1"
    }
}
