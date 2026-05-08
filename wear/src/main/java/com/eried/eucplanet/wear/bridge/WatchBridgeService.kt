package com.eried.eucplanet.wear.bridge

import android.content.Intent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

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
                        gpsSpeedEnabled = map.getBoolean(WatchKeys.OPT_GPS_SPEED, false)
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
        if (event.path != WatchPaths.WAKE) return
        val intent = Intent().apply {
            setClassName(packageName, "com.eried.eucplanet.wear.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Foreground-launch restrictions can refuse this on some OEMs;
            // the user will see the app on their next watch interaction.
        }
    }
}
