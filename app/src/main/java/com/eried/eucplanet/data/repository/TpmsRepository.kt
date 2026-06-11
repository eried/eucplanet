package com.eried.eucplanet.data.repository

import com.eried.eucplanet.ble.tpms.TpmsDiscoveredSensor
import com.eried.eucplanet.ble.tpms.TpmsReading
import com.eried.eucplanet.ble.tpms.TpmsScanner
import com.eried.eucplanet.ble.tpms.TpmsSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the rider's TPMS-sensor list + the rolling map of latest readings.
 *
 * Persistence: bound sensors are serialised to AppSettings.tpmsSensors as
 * a JSON array, same idiom as the rest of the list-shaped settings.
 *
 * Scanning: a single low-latency BLE scan runs whenever there is at least
 * one bound sensor; we lazily start/stop it as the bound list flips
 * empty/non-empty so an idle rider isn't burning radio when nothing's
 * being watched. Every incoming [TpmsScanner.scan] match updates
 * [readings] in place, keyed by sensor id6Hex.
 *
 * Bind UI (Settings -> TPMS -> Add) uses [discoverNearby] which is a
 * separate, one-shot view of every sensor the radio currently sees,
 * including unbound ones -- so the rider can tap a row to bind it.
 */
@Singleton
class TpmsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val scanner: TpmsScanner,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The rider's bound sensors, decoded from settings. */
    val bound: StateFlow<List<TpmsSensor>> =
        settingsRepository.settings
            .map { decodeBound(it.tpmsSensors) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Latest reading per sensor id, only for currently-bound sensors. */
    private val _readings = MutableStateFlow<Map<String, TpmsReading>>(emptyMap())
    val readings: StateFlow<Map<String, TpmsReading>> = _readings.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Start / stop the long-running scan based on whether we have
        // any bound sensors. Eagerly collected so the first bind kicks
        // the scan immediately without waiting for a UI subscriber.
        scope.launch {
            bound.collect { list ->
                if (list.isNotEmpty() && scanJob == null) {
                    startScan()
                } else if (list.isEmpty() && scanJob != null) {
                    stopScan()
                }
            }
        }
    }

    private fun startScan() {
        scanJob = scope.launch {
            scanner.scan().collect { hit ->
                // Only persist readings for sensors the rider has bound.
                // The discoverNearby() flow is the only place unbound
                // sensors surface.
                val boundIds = bound.value.map { it.id6Hex }.toSet()
                if (hit.id6Hex !in boundIds) return@collect
                _readings.value = _readings.value + (hit.id6Hex to hit.reading)
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanner.stop()
        _readings.value = emptyMap()
    }

    /**
     * Live stream of every sensor currently advertising, bound or not.
     * Used by the "Add sensor" bottom sheet -- collect it while the
     * sheet is open, cancel on close. Re-uses the long-running scan if
     * one is already up (because the rider has other bound sensors),
     * otherwise starts an ephemeral scan that auto-stops when the flow
     * is no longer collected.
     */
    fun discoverNearby(): Flow<TpmsDiscoveredSensor> = scanner.scan()
        .map { TpmsDiscoveredSensor(id6Hex = it.id6Hex, rssi = it.rssi, reading = it.reading) }

    suspend fun bind(id6Hex: String, label: String = "") {
        val normalised = id6Hex.uppercase()
        val current = bound.value
        if (current.any { it.id6Hex == normalised }) return
        val next = current + TpmsSensor(
            id6Hex = normalised,
            label = label,
            addedAtMs = System.currentTimeMillis(),
        )
        save(next)
    }

    suspend fun unbind(id6Hex: String) {
        val normalised = id6Hex.uppercase()
        val next = bound.value.filterNot { it.id6Hex == normalised }
        if (next.size == bound.value.size) return
        save(next)
        // Drop any cached reading so the dashboard tile clears.
        _readings.value = _readings.value - normalised
    }

    suspend fun rename(id6Hex: String, label: String) {
        val normalised = id6Hex.uppercase()
        val next = bound.value.map {
            if (it.id6Hex == normalised) it.copy(label = label) else it
        }
        save(next)
    }

    private suspend fun save(list: List<TpmsSensor>) {
        val json = Json.encodeToString(ListSerializer(TpmsSensor.serializer()), list)
        settingsRepository.update(settingsRepository.get().copy(tpmsSensors = json))
    }

    private fun decodeBound(jsonString: String): List<TpmsSensor> =
        runCatching {
            Json.decodeFromString(ListSerializer(TpmsSensor.serializer()), jsonString)
        }.getOrDefault(emptyList())
}
