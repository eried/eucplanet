package com.eried.eucplanet.probe

import com.eried.eucplanet.ble.BleWriteQueue
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.DecodeResult
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.ble.WheelAdapter
import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Milestone 1 stand-in for WheelRepository: the same init and poll loop
 * (init sequence, then realtime polls with a settings poll every 12th and a
 * stats poll every 18th, every 250 ms), folding telemetry into [wheelData].
 * Goes away when WheelRepository moves to the shared module.
 */
class ProbeSession(private val link: IosWheelLink, private val adapter: WheelAdapter) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private val _wheelData = MutableStateFlow(WheelData())
    val wheelData: StateFlow<WheelData> = _wheelData.asStateFlow()
    private val _modelName = MutableStateFlow<String?>(null)
    val modelName: StateFlow<String?> = _modelName.asStateFlow()

    init {
        scope.launch {
            link.decodedResults.collect { r ->
                when (r) {
                    is DecodeResult.Telemetry -> _wheelData.value = r.data
                    is DecodeResult.ModelName -> _modelName.value = r.name
                    else -> Unit
                }
            }
        }
        scope.launch {
            link.connectionState.collect { s ->
                pollJob?.cancel()
                if (s == ConnectionState.CONNECTED) {
                    pollJob = scope.launch { pollLoop() }
                } else if (s == ConnectionState.DISCONNECTED) {
                    _wheelData.value = WheelData()
                    _modelName.value = null
                }
            }
        }
    }

    private suspend fun pollLoop() {
        val poll = BleWriteQueue.Kind.POLL
        val init = adapter.initSequence()
        for (cmd in init) {
            link.writeCommand(cmd, BleWriteQueue.Kind.COMMAND)
            delay(POLL_INTERVAL_MS)
        }
        var cycle = 0
        while (true) {
            when {
                cycle > 0 && cycle % STATS_REFRESH_INTERVAL == 0 ->
                    link.writeCommand(adapter.pollStats() ?: adapter.pollRealtime(), poll)
                cycle % SETTINGS_REFRESH_INTERVAL == 0 -> link.writeCommand(adapter.pollSettings(), poll)
                else -> link.writeCommand(adapter.pollRealtime(), poll)
            }
            cycle++
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val SETTINGS_REFRESH_INTERVAL = 12
        const val STATS_REFRESH_INTERVAL = 18
    }
}
