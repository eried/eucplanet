package com.eried.eucplanet.pebble

import android.content.Context
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion to the Pebble C-SDK watchapp in `pebble-watch-app/`.
 *
 * Mirrors [com.eried.eucplanet.garmin.GarminBridge] in DI shape and lifecycle:
 * a process-lifetime `@Singleton` that [start]s once from `EucPlanetApp`, then
 * pushes a small telemetry dict over PebbleKitAndroid2. The transport differs:
 * AppMessage routes through the Pebble mobile app, and pushing only happens
 * while the watchapp is actually OPEN — [PebbleListenerService] flips
 * [onWatchAppOpened] / [onWatchAppClosed] from the watch's open/close events,
 * so we never spend BLE/battery when nothing is on-wrist to render it.
 *
 * The pump samples `wheelData` at ~3.5 Hz (Pebble AppMessage bandwidth is
 * tighter than the HUD's 5 Hz WiFi). It builds the canonical-metric dict with
 * the SDK-free [PebbleTelemetry] (so the assembly is unit-testable) and only
 * converts to the SDK's [PebbleDictionary] right before sending.
 */
@Singleton
class PebbleBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "PebbleBridge"

        /** ~3.5 Hz. Starting point per the design; tune on hardware. */
        private const val SAMPLE_INTERVAL_MS = 280L

        /**
         * UUID of the watchapp in `pebble-watch-app/package.json`. Must match
         * that manifest verbatim or AppMessages are dropped, mirroring how
         * [com.eried.eucplanet.garmin.GARMIN_APP_UUID] is the watch contract.
         */
        val PEBBLE_APP_UUID: UUID = UUID.fromString("71cc8578-8aad-4179-8d5c-98bb0b13c2e1")
    }

    private val sender = DefaultPebbleSender(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    /** Flipped by [PebbleListenerService] on watchapp open/close. We only push
     *  telemetry while a watchapp is actually on-screen, and the Settings
     *  "Paired devices" card mirrors this as the Pebble surface's presence —
     *  PebbleKitAndroid2 gives us no passive paired-watch query, so "watchapp
     *  open" is the connection signal. */
    private val _watchAppOpen = MutableStateFlow(false)
    val watchAppOpen: StateFlow<Boolean> = _watchAppOpen.asStateFlow()

    /** Wall-clock ms of the last successful AppMessage send. Drives the card's
     *  Active/Idle dot the same way [com.eried.eucplanet.garmin.GarminBridge]'s
     *  `lastSuccessAtMs` does. */
    private val _lastSentAtMs = MutableStateFlow(0L)
    val lastSentAtMs: StateFlow<Long> = _lastSentAtMs.asStateFlow()

    @Volatile private var started = false

    /**
     * Inject-and-forget, mirroring GarminBridge.start(). The pump itself stays
     * dormant until the watchapp opens ([onWatchAppOpened]); there's nothing to
     * spin up eagerly here, but we keep the method so the publish site
     * (`EucPlanetApp.onCreate`) treats Pebble exactly like the other bridges.
     */
    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Pebble bridge ready (sample=${SAMPLE_INTERVAL_MS} ms)")
    }

    /** Called by [PebbleListenerService] when the watchapp opens on a watch. */
    fun onWatchAppOpened() {
        _watchAppOpen.value = true
        startPump()
    }

    /** Called by [PebbleListenerService] when the watchapp closes. */
    fun onWatchAppClosed() {
        _watchAppOpen.value = false
        pumpJob?.cancel()
        pumpJob = null
    }

    private fun startPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch {
            wheelRepository.wheelData
                .sample(SAMPLE_INTERVAL_MS)
                .collect { data ->
                    if (!_watchAppOpen.value) return@collect
                    val s = settingsRepository.get()
                    if (!s.pebbleEnabled) return@collect
                    val connected =
                        wheelRepository.connectionState.value == ConnectionState.CONNECTED
                    val dict = PebbleTelemetry.build(data, connected, s).toPebbleDictionary()
                    runCatching { sender.sendDataToPebble(PEBBLE_APP_UUID, dict) }
                        .onSuccess { _lastSentAtMs.value = System.currentTimeMillis() }
                        .onFailure { Log.d(TAG, "send failed: ${it.message}") }
                }
        }
    }

    /**
     * Convert the SDK-free `Map<Int, Any>` from [PebbleTelemetry] into the
     * PebbleKitAndroid2 wire type (`Map<UInt, PebbleDictionaryItem>`). Ints
     * become Int32, strings become Text — the only SDK-coupled glue, kept here
     * so the dict assembly stays testable without the SDK.
     */
    private fun Map<Int, Any>.toPebbleDictionary(): PebbleDictionary =
        entries.associate { (key, value) ->
            val item = when (value) {
                is Int -> PebbleDictionaryItem.Int32(value)
                is String -> PebbleDictionaryItem.Text(value)
                else -> PebbleDictionaryItem.Text(value.toString())
            }
            key.toUInt() to item
        }
}
