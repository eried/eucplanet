package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsStore
) {
    val settings: Flow<AppSettings> = store.settings.map { it.sanitized() }

    suspend fun get(): AppSettings = store.get().sanitized()

    suspend fun update(settings: AppSettings) {
        store.update(settings)
    }

    suspend fun updateLastDevice(address: String, name: String) {
        val current = get()
        update(current.copy(lastDeviceAddress = address, lastDeviceName = name))
    }

    private fun AppSettings.sanitized(): AppSettings =
        if (autoRecordStopIdleSeconds < 30) copy(autoRecordStopIdleSeconds = 30) else this
}
