package com.eried.eucplanet.wear.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wakes up when the phone publishes a new `/euc/state` DataItem. The actual
 * UI state lives in [WatchStateRepository]; this service just decodes the
 * DataMap and forwards. Wear OS routes data events to a service even when
 * no Activity is running, so the in-process repo stays fresh while the user
 * is on a watch face.
 */
class WatchBridgeService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem }
            .filter { it.uri.path == WatchPaths.STATE }
            .forEach { item ->
                val map = DataMapItem.fromDataItem(item).dataMap
                WatchStateRepository.update(
                    WatchState(
                        connected = map.getBoolean(WatchKeys.CONNECTED, false),
                        wheelName = map.getString(WatchKeys.WHEEL_NAME, "") ?: "",
                        speedKmh = map.getFloat(WatchKeys.SPEED, 0f),
                        batteryPercent = map.getInt(WatchKeys.BATTERY, 0),
                        phoneBatteryPercent = map.getInt(WatchKeys.PHONE_BATT, 0),
                        voltage = map.getFloat(WatchKeys.VOLTAGE, 0f),
                        current = map.getFloat(WatchKeys.CURRENT, 0f),
                        pwmPercent = map.getFloat(WatchKeys.PWM, 0f),
                        temperatureC = map.getFloat(WatchKeys.TEMP, 0f),
                        tripKm = map.getFloat(WatchKeys.TRIP_KM, 0f),
                        torque = map.getFloat(WatchKeys.TORQUE, 0f),
                        lightOn = map.getBoolean(WatchKeys.LIGHT_ON, false),
                        maxSpeedKmh = map.getFloat(WatchKeys.MAX_SPEED, 0f),
                        hasHorn = map.getBoolean(WatchKeys.HAS_HORN, false),
                        hasLight = map.getBoolean(WatchKeys.HAS_LIGHT, false),
                        imperialUnits = map.getBoolean(WatchKeys.IMPERIAL, false),
                        accentKey = map.getString(WatchKeys.ACCENT, "default") ?: "default",
                        keepScreenOn = map.getBoolean(WatchKeys.OPT_KEEP_ON, true),
                        showWheelBattery = map.getBoolean(WatchKeys.OPT_SHOW_WHEEL_BATT, true),
                        showPhoneBattery = map.getBoolean(WatchKeys.OPT_SHOW_PHONE_BATT, true),
                        showWatchBattery = map.getBoolean(WatchKeys.OPT_SHOW_WATCH_BATT, true),
                        pwmDisplay = map.getString(WatchKeys.OPT_PWM_DISPLAY, "BOTH") ?: "BOTH",
                        showSpeedUnit = map.getBoolean(WatchKeys.OPT_SHOW_SPEED_UNIT, true),
                        prioritizePwm = map.getBoolean(WatchKeys.OPT_PRIORITIZE_PWM, false),
                        dialRotationDeg = map.getInt(WatchKeys.OPT_DIAL_ROTATION, 0),
                        showGaugeBand = map.getBoolean(WatchKeys.OPT_GAUGE_BAND, false),
                        gaugeOrangeThresholdPct = map.getInt(WatchKeys.OPT_GAUGE_ORANGE, 65),
                        gaugeRedThresholdPct = map.getInt(WatchKeys.OPT_GAUGE_RED, 85),
                        stem1Click = map.getString(WatchKeys.STEM1_CLICK, "NONE") ?: "NONE",
                        stem1Hold = map.getString(WatchKeys.STEM1_HOLD, "NONE") ?: "NONE",
                        stem2Click = map.getString(WatchKeys.STEM2_CLICK, "NONE") ?: "NONE",
                        stem2Hold = map.getString(WatchKeys.STEM2_HOLD, "NONE") ?: "NONE",
                        phoneSynced = true,
                        screen1Click = map.getString(WatchKeys.SCREEN1_CLICK, "HORN") ?: "HORN",
                        screen1Hold = map.getString(WatchKeys.SCREEN1_HOLD, "NONE") ?: "NONE",
                        screen2Click = map.getString(WatchKeys.SCREEN2_CLICK, "LIGHT_TOGGLE") ?: "LIGHT_TOGGLE",
                        screen2Hold = map.getString(WatchKeys.SCREEN2_HOLD, "NONE") ?: "NONE",
                        hapticOnAction = map.getBoolean(WatchKeys.HAPTIC_ON_ACTION, false)
                    )
                )
            }
    }

    /**
     * Phone sends a message on `/euc/wake` whenever its app starts. We launch
     * MainActivity so the watch wakes the dial up immediately — without this
     * the watch keeps showing whatever it was on before, even though the phone
     * has fresh state ready to ship.
     */
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchPaths.WAKE -> {
                val intent = Intent().apply {
                    setClassName(packageName, "com.eried.eucplanet.wear.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try { startActivity(intent) } catch (_: Exception) {
                    // Foreground-launch restrictions can refuse this on some
                    // OEMs; the user will see the app next interaction.
                }
            }
            WatchHints.VIBRATE -> {
                val ms = if (event.data.size >= 4) {
                    ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN).int
                } else 500
                vibrateOnce(ms.coerceIn(50, 5000).toLong())
            }
        }
    }

    private fun vibrateOnce(durationMs: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { /* best effort */ }
    }
}
