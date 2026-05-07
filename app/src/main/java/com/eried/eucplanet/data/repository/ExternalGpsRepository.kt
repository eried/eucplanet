package com.eried.eucplanet.data.repository

import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsConnectionManager
import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the external-GPS BLE connection and exposes the current sample.
 *
 * State flows:
 *  * [connectionState] mirrors the underlying GATT manager.
 *  * [currentSample] is the latest decoded fix from whichever adapter is active.
 *
 * Pairing persists in three AppSettings columns (address / name / source enum)
 * so [autoConnect] can re-open the paired device on app start without prompting.
 */
@Singleton
class ExternalGpsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionManager: ExternalGpsConnectionManager,
    private val adapters: Set<@JvmSuppressWildcards ExternalGpsAdapter>
) {
    companion object {
        private const val TAG = "ExtGpsRepo"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    private val _currentSample = MutableStateFlow<ExternalGpsSample?>(null)
    val currentSample: StateFlow<ExternalGpsSample?> = _currentSample.asStateFlow()

    init {
        // Bridge the connection manager's sample stream into a StateFlow so the
        // dashboard can render the latest fix without subscribing to a hot
        // SharedFlow each time it recomposes.
        scope.launch {
            connectionManager.samples.collect { _currentSample.value = it }
        }
    }

    /** Address of the paired device, or null if no pairing. Mirrors AppSettings. */
    suspend fun pairedAddress(): String? = settingsRepository.get().externalGpsAddress

    /** Display name of the paired device. */
    suspend fun pairedName(): String? = settingsRepository.get().externalGpsName

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

    /**
     * Open the GATT connection to the currently-paired device. Returns silently
     * if no pairing exists. Picks the matching adapter from the registry by
     * source-family enum; if the family no longer ships an adapter (e.g. user
     * upgraded a build that removed Draggy support) the call is a no-op.
     */
    suspend fun connectPaired(): Boolean {
        val address = pairedAddress() ?: return false
        val source = pairedSource() ?: return false
        val adapter = adapters.firstOrNull { it.source == source } ?: run {
            Log.w(TAG, "No adapter for paired source $source — skipping connect")
            return false
        }
        connectionManager.connect(address, adapter)
        return true
    }

    fun connect(address: String, adapter: ExternalGpsAdapter) {
        connectionManager.connect(address, adapter)
    }

    fun disconnect() {
        _currentSample.value = null
        connectionManager.disconnect()
    }
}
