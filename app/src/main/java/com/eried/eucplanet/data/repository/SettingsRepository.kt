package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.db.SettingsDao
import com.eried.eucplanet.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao
) {
    val settings: Flow<AppSettings> = settingsDao.observe().map { (it ?: AppSettings()).sanitized() }

    suspend fun get(): AppSettings = (settingsDao.get() ?: AppSettings()).sanitized()

    suspend fun update(settings: AppSettings) {
        settingsDao.upsert(settings)
    }

    suspend fun updateLastDevice(address: String, name: String) {
        val current = get()
        update(current.copy(lastDeviceAddress = address, lastDeviceName = name))
    }

    // Clamps values that used to allow ranges we no longer support, so older installs
    // with now-illegal stored values (e.g. idle timeout < 30s from the pre-30s-step UI)
    // read back as valid. The corrected value persists the next time anything saves
    // settings via copy().
    private fun AppSettings.sanitized(): AppSettings =
        if (autoRecordStopIdleSeconds < 30) copy(autoRecordStopIdleSeconds = 30) else this
}
