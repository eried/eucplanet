package com.eried.eucplanet.data.repository

import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the external-GPS BLE connection and exposes the current sample.
 *
 * Phase 1 is a state-holder skeleton: persists pairing settings and exposes
 * connection state, but doesn't actually open a GATT connection — that lands
 * in Phase 2 along with the RaceBox UBX parser. UI can already query
 * [pairedAddress] / [pairedSource] to render the Integration tab card.
 */
@Singleton
class ExternalGpsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "ExtGpsRepo"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentSample = MutableStateFlow<ExternalGpsSample?>(null)
    val currentSample: StateFlow<ExternalGpsSample?> = _currentSample.asStateFlow()

    /** Address of the paired device, or null if no pairing. Mirrors AppSettings. */
    suspend fun pairedAddress(): String? = settingsRepository.get().externalGpsAddress

    /** Source family of the paired device (RaceBox today; more later). */
    suspend fun pairedSource(): ExternalGpsSource? =
        settingsRepository.get().externalGpsSource?.let {
            runCatching { ExternalGpsSource.valueOf(it) }.getOrNull()
        }

    suspend fun setPairing(address: String, name: String, source: ExternalGpsSource) {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                externalGpsAddress = address,
                externalGpsName = name,
                externalGpsSource = source.name
            )
        )
        Log.i(TAG, "Paired ${source.displayName}: $name ($address)")
    }

    suspend fun clearPairing() {
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                externalGpsAddress = null,
                externalGpsName = null,
                externalGpsSource = null
            )
        )
        disconnect()
        Log.i(TAG, "Pairing cleared")
    }

    fun connect(@Suppress("UNUSED_PARAMETER") address: String) {
        // Phase 2 wires the actual GATT connection through ExternalGpsConnectionManager.
        // For now just flip state so the UI can render "connecting" / "connected".
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "connect() invoked but Phase 2 not implemented; state forced DISCONNECTED")
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun disconnect() {
        _currentSample.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
