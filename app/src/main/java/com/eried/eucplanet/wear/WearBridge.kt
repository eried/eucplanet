package com.eried.eucplanet.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository
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
        private const val K_PHONE_BATT = "b2"
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
        private const val K_IMPERIAL = "im"
        private const val K_ACCENT = "ac"
        private const val K_TIMESTAMP = "ts"
        // Watch-display option keys mirror WatchKeys.OPT_* on the wear side.
        private const val K_OPT_KEEP_ON = "wko"
        private const val K_OPT_SHOW_WHEEL_BATT = "wsb"
        private const val K_OPT_SHOW_PHONE_BATT = "wpb"
        private const val K_OPT_SHOW_WATCH_BATT = "wwb"
        private const val K_OPT_PWM_DISPLAY = "wpd"
        private const val K_OPT_SHOW_SPEED_UNIT = "wsu"

        private const val PATH_WAKE = "/euc/wake"
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

        // Auto-start watch app: ping every paired node so the watch wakes its
        // MainActivity. Gated by the user setting; if a tester turns it off,
        // they can still open the watch app manually. Best-effort — failures
        // are logged but don't block bridge startup.
        scope.launch {
            try {
                val settings = settingsRepository.get()
                if (!settings.watchAutoStart) return@launch
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                nodes.forEach { node: Node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_WAKE, ByteArray(0))
                }
            } catch (e: Exception) {
                Log.d(TAG, "watch wake skipped: ${e.message}")
            }
        }

        // Periodic publisher rather than a Flow combine. Reasoning: when the
        // wheel is disconnected the upstream flows don't emit, so a sample +
        // distinctUntilChanged pipeline goes silent — and the watch ends up
        // in the "phone not here" placeholder even though the phone app is
        // running and paired. Polling at PUBLISH_INTERVAL_MS keeps the
        // watch's freshness signal alive without per-emission complexity.
        scope.launch {
            while (true) {
                try {
                    publish(
                        data = wheelRepository.wheelData.value,
                        state = wheelRepository.connectionState.value,
                        name = wheelRepository.modelName.value,
                        maxSpeed = wheelRepository.maxSpeedCap.value,
                        settings = settingsRepository.get()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "publish loop error", e)
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    private fun publish(
        data: com.eried.eucplanet.data.model.WheelData,
        state: ConnectionState,
        name: String?,
        maxSpeed: Float,
        settings: AppSettings
    ) {
        try {
            val request = PutDataMapRequest.create(PATH_STATE).apply {
                dataMap.putBoolean(K_CONNECTED, state == ConnectionState.CONNECTED)
                dataMap.putString(K_WHEEL_NAME, name ?: "")
                dataMap.putFloat(K_SPEED, data.speed)
                dataMap.putInt(K_BATTERY, data.batteryPercent)
                dataMap.putInt(K_PHONE_BATT, readPhoneBatteryPercent())
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
                dataMap.putBoolean(K_IMPERIAL, settings.imperialUnits)
                dataMap.putString(K_ACCENT, settings.accentColor)
                dataMap.putBoolean(K_OPT_KEEP_ON, settings.watchKeepScreenOn)
                dataMap.putBoolean(K_OPT_SHOW_WHEEL_BATT, settings.watchShowWheelBattery)
                dataMap.putBoolean(K_OPT_SHOW_PHONE_BATT, settings.watchShowPhoneBattery)
                dataMap.putBoolean(K_OPT_SHOW_WATCH_BATT, settings.watchShowWatchBattery)
                dataMap.putString(K_OPT_PWM_DISPLAY, settings.watchPwmDisplay)
                dataMap.putBoolean(K_OPT_SHOW_SPEED_UNIT, settings.watchShowSpeedUnit)
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

    private fun readPhoneBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { lvl ->
            if (lvl in 0..100) return lvl
        }
        // Fallback to the sticky ACTION_BATTERY_CHANGED broadcast on devices where
        // BATTERY_PROPERTY_CAPACITY isn't reliable.
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }

}
