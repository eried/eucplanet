package com.eried.eucplanet.wear

import android.content.Context
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.repository.WheelRepository
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion to the watch app.
 *
 * Subscribes to [WheelRepository] state and publishes a compact snapshot to the
 * Wearable Data Layer at `/euc/state`, throttled to 5 Hz so the watch face
 * stays smooth without burning Bluetooth bandwidth. The watch reads the same
 * [WatchKeys] this writer uses; keep them in sync if you add a field.
 *
 * Started from [com.eried.eucplanet.EucPlanetApplication] so it lives for the
 * whole app process. No Activity lifecycle required, since the watch app
 * deserves data even when the user is on the lock screen looking at their
 * Watch Ultra.
 *
 * Control messages flow the other direction through [PhoneWearListenerService]
 * which routes them back to the repository.
 */
@Singleton
class WearBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository
) {
    companion object {
        private const val TAG = "WearBridge"
        // Throttle so the watch doesn't get hammered at the 250 ms BLE poll rate.
        // 200 ms = 5 Hz, smooth on the gauge, fits the ambient screen budget.
        private const val PUBLISH_INTERVAL_MS = 200L

        private const val PATH_STATE = "/euc/state"
        private const val K_CONNECTED = "c"
        private const val K_SPEED = "s"
        private const val K_BATTERY = "b"
        private const val K_VOLTAGE = "v"
        private const val K_CURRENT = "i"
        private const val K_PWM = "p"
        private const val K_TEMP = "t"
        private const val K_TRIP_KM = "tr"
        private const val K_TORQUE = "tq"
        private const val K_LIGHT_ON = "l"
        private const val K_MAX_SPEED = "ms"
        private const val K_WHEEL_NAME = "n"
        private const val K_HAS_HORN = "ch"
        private const val K_HAS_LIGHT = "cl"
        private const val K_TIMESTAMP = "ts"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /**
     * Idempotent. Begins streaming snapshots to any paired Wear OS device.
     * Safe to call from Application.onCreate even if no watch is paired; the
     * Data Layer just keeps a local cache that future watches sync from.
     */
    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Wear bridge starting (publish=${PUBLISH_INTERVAL_MS} ms)")

        scope.launch {
            combine(
                wheelRepository.wheelData,
                wheelRepository.connectionState,
                wheelRepository.modelName,
                wheelRepository.maxSpeedCap
            ) { data, state, name, maxSpeed ->
                Quad(data, state, name, maxSpeed)
            }
                .sample(PUBLISH_INTERVAL_MS)
                .distinctUntilChanged()
                .collect { (data, state, name, maxSpeed) ->
                    publish(data, state, name, maxSpeed)
                }
        }
    }

    private fun publish(
        data: com.eried.eucplanet.data.model.WheelData,
        state: ConnectionState,
        name: String?,
        maxSpeed: Float
    ) {
        try {
            val request = PutDataMapRequest.create(PATH_STATE).apply {
                dataMap.putBoolean(K_CONNECTED, state == ConnectionState.CONNECTED)
                dataMap.putString(K_WHEEL_NAME, name ?: "")
                dataMap.putFloat(K_SPEED, data.speed)
                dataMap.putInt(K_BATTERY, data.batteryPercent)
                dataMap.putFloat(K_VOLTAGE, data.voltage)
                dataMap.putFloat(K_CURRENT, data.current)
                dataMap.putFloat(K_PWM, data.pwm)
                dataMap.putFloat(K_TEMP, data.maxTemperature)
                dataMap.putFloat(K_TRIP_KM, data.tripDistance)
                dataMap.putFloat(K_TORQUE, data.torque)
                dataMap.putBoolean(K_LIGHT_ON, data.lightOn)
                dataMap.putFloat(K_MAX_SPEED, maxSpeed)
                // V14 family always has horn + light; if/when we add wheels that
                // don't, gate these on WheelAdapter.capabilities. For now true.
                dataMap.putBoolean(K_HAS_HORN, true)
                dataMap.putBoolean(K_HAS_LIGHT, true)
                // DataItems dedupe by content. Bumping a timestamp guarantees
                // the watch sees every snapshot when the values stop changing
                // (e.g. wheel idle, but we want the connection-state heartbeat).
                dataMap.putLong(K_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request)
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
