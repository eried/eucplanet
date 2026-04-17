package com.eried.evendarkerbot.data.repository

import com.eried.evendarkerbot.data.db.SettingsDao
import com.eried.evendarkerbot.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {
    val settings: Flow<AppSettings> = settingsDao.observe().map { it ?: AppSettings() }

    suspend fun get(): AppSettings = settingsDao.get() ?: AppSettings()

    suspend fun update(settings: AppSettings) {
        settingsDao.upsert(settings)
    }

    suspend fun updateLastDevice(address: String, name: String) {
        val current = get()
        update(current.copy(lastDeviceAddress = address, lastDeviceName = name))
    }

    suspend fun updateWheelSpeedSettings(tiltbackKmh: Float, beepKmh: Float) {
        val current = get()
        update(current.copy(normalTiltbackKmh = tiltbackKmh, normalBeepKmh = beepKmh))
    }
}
