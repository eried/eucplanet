package com.eried.eucplanet.radar

import com.eried.eucplanet.ble.BleProfile
import com.eried.eucplanet.ble.transport.BleConnState
import com.eried.eucplanet.ble.transport.BleConnection
import com.eried.eucplanet.ble.transport.BleDevice
import com.eried.eucplanet.ble.transport.createBleTransport
import com.eried.eucplanet.data.DecodedThreat
import com.eried.eucplanet.data.RadarThreat
import com.eried.eucplanet.data.ThreatLevel
import com.eried.eucplanet.util.nowEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Garmin Varia rear-view radar on its OWN [createBleTransport] central (separate
 * from the wheel + any external GPS) — Android-parity radar. Scans for Varia,
 * connects to the `…3203` notify channel, decodes triplet frames into tracked
 * vehicles, and classifies each into a [ThreatLevel] from distance + closing rate
 * (a port of Android's RadarRepository.classify — Garmin's own level byte is NDA).
 */
class RadarManager(private val scope: CoroutineScope) {
    private val transport = createBleTransport()
    private val adapter = VariaAdapter()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()
    private val _threats = MutableStateFlow<List<RadarThreat>>(emptyList())
    val threats: StateFlow<List<RadarThreat>> = _threats.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private data class TrackState(val distanceM: Int, val lastSeenMs: Long)
    private val tracks = mutableMapOf<Int, TrackState>()
    private val firstSeen = mutableMapOf<Int, Long>()

    private var scanJob: Job? = null
    private var readJob: Job? = null
    private var stateJob: Job? = null
    private var conn: BleConnection? = null

    fun startScan() {
        _devices.value = emptyList()
        _scanning.value = true
        scanJob?.cancel()
        scanJob = scope.launch {
            transport.scan().collect { d ->
                val name = d.name ?: return@collect
                if (adapter.matches(name)) {
                    _devices.update { cur -> if (cur.any { it.address == d.address }) cur else cur + d }
                }
            }
        }
    }

    fun stopScan() { scanJob?.cancel(); scanJob = null; _scanning.value = false }

    fun connect(address: String) {
        scope.launch {
            stopScan()
            try {
                val c = transport.connect(address, BleProfile.VARIA_RADAR)
                conn = c
                _connected.value = true
                readJob?.cancel()
                readJob = scope.launch {
                    c.incoming.collect { frame -> adapter.decode(frame)?.let { ingest(it) } }
                }
                stateJob?.cancel()
                stateJob = scope.launch {
                    c.state.collect { if (it == BleConnState.Disconnected) { _connected.value = false; _threats.value = emptyList() } }
                }
            } catch (e: Exception) {
                _connected.value = false
            }
        }
    }

    fun disconnect() {
        readJob?.cancel(); readJob = null
        stateJob?.cancel(); stateJob = null
        conn?.close(); conn = null
        _connected.value = false
        _threats.value = emptyList()
        tracks.clear(); firstSeen.clear()
    }

    private fun ingest(decoded: List<DecodedThreat>) {
        val now = nowEpochMillis()
        val result = decoded.map { d ->
            val prev = tracks[d.id]
            val level = classify(prev, d, now)
            val first = firstSeen.getOrPut(d.id) { now }
            RadarThreat(d.id, d.distanceM, d.approachSpeedKmh, level, first)
        }
        // Drop tracks not in this frame (radar lost them), then refresh.
        val ids = decoded.map { it.id }.toSet()
        tracks.keys.retainAll(ids); firstSeen.keys.retainAll(ids)
        decoded.forEach { tracks[it.id] = TrackState(it.distanceM, now) }
        _threats.value = result.sortedBy { it.distanceM }
    }

    private fun classify(prev: TrackState?, d: DecodedThreat, now: Long): ThreatLevel {
        if (d.approachSpeedKmh < STATIC_TARGET_KMH && prev == null) return ThreatLevel.NONE
        if (d.approachSpeedKmh >= FAST_APPROACH_SPEED_KMH) return ThreatLevel.FAST_APPROACH
        if (d.distanceM <= FAST_APPROACH_DISTANCE_M && d.approachSpeedKmh >= STATIC_TARGET_KMH) return ThreatLevel.FAST_APPROACH
        if (prev != null) {
            val closingMeters = (prev.distanceM - d.distanceM).toDouble()
            val elapsedMs = (now - prev.lastSeenMs).coerceAtLeast(MIN_ELAPSED_MS_FOR_RATE)
            val closingMps = closingMeters * 1000.0 / elapsedMs
            if (closingMps >= FALLBACK_CLOSING_MPS) return ThreatLevel.APPROACHING
        }
        return if (d.approachSpeedKmh >= STATIC_TARGET_KMH) ThreatLevel.APPROACHING else ThreatLevel.NONE
    }

    private companion object {
        const val FAST_APPROACH_DISTANCE_M = 50
        const val FAST_APPROACH_SPEED_KMH = 60
        const val STATIC_TARGET_KMH = 3
        const val FALLBACK_CLOSING_MPS = 10.0
        const val MIN_ELAPSED_MS_FOR_RATE = 200L
    }
}
