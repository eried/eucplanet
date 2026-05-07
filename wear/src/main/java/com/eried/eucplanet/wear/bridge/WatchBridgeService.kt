package com.eried.eucplanet.wear.bridge

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
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
                        accentKey = map.getString(WatchKeys.ACCENT, "default") ?: "default"
                    )
                )
            }
    }
}
