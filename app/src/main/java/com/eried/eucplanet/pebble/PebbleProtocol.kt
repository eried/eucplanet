package com.eried.eucplanet.pebble

/**
 * Wire vocabulary shared between the phone and the Pebble C-SDK companion in
 * `pebble-watch-app/` (its `keys.h` mirrors these ids). Mirrors [GarminKeys] /
 * the Wear `WatchKeys` semantics on purpose, so a future refactor can collapse
 * all three into a single `:protocol` module without a wire-format break.
 *
 * Pebble AppMessage keys are integers (unlike Garmin's string keys), so these
 * are stable small ints. Keys stay low/dense because every byte rides
 * Bluetooth and the watch enumerates them in `messageKeys`.
 *
 * Public (not internal) so the flavor-independent unit test in
 * `app/src/test` can assert against them without the SDK on the classpath.
 */
object PebbleKeys {
    const val CONNECTED = 0
    const val SPEED = 1        // km/h * 10, canonical metric (int)
    const val BATTERY = 2      // percent (int)
    const val VOLTAGE = 3      // volts * 10 (int)
    const val CURRENT = 4      // amps * 10 (int)
    const val PWM = 5          // percent (int)
    const val TEMP = 6         // °C * 10, canonical metric (int)
    const val UNIT_SPEED = 7   // "kmh" | "mph" | "ms" | "kn"
    const val UNIT_TEMP = 8    // "C" | "F" | "K"
    const val ACCENT = 9       // active theme primary as 0xAARRGGBB (int)
}

/**
 * Pure, SDK-free assembly of the Pebble telemetry dict from a wheel snapshot.
 *
 * Lives in `src/main` (not the `pebbleEnabled` source set) so it is
 * flavor-independent and unit-testable WITHOUT PebbleKitAndroid2 on the
 * classpath. The enabled [PebbleBridge] converts this `Map<Int, Any>` into the
 * SDK's `PebbleDictionary` (`Map<UInt, PebbleDictionaryItem>`) just before
 * sending.
 *
 * Values are CANONICAL METRIC (speed/temp scaled to ints, *10) plus unit
 * codes, mirroring how the Garmin/HUD companions hand the watch metric +
 * unit so the wrist converts locally. Ints only (plus the unit strings); the
 * watch reads them straight out of its AppMessage inbox.
 */
object PebbleTelemetry {
    /**
     * @param data live wheel telemetry snapshot
     * @param connected whether a wheel is currently connected (live fields
     *   read as zeroed/stale on the watch when false)
     * @param settings rider settings, for the unit codes
     */
    fun build(
        data: com.eried.eucplanet.data.model.WheelData,
        connected: Boolean,
        settings: com.eried.eucplanet.data.model.AppSettings
    ): Map<Int, Any> {
        val speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
        val tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings)
        return buildMap {
            put(PebbleKeys.CONNECTED, if (connected) 1 else 0)
            put(PebbleKeys.SPEED, (data.speed * 10).toInt())
            put(PebbleKeys.BATTERY, data.batteryPercent)
            put(PebbleKeys.VOLTAGE, (data.voltage * 10).toInt())
            put(PebbleKeys.CURRENT, (data.current * 10).toInt())
            put(PebbleKeys.PWM, data.pwm.toInt())
            put(PebbleKeys.TEMP, (data.maxTemperature * 10).toInt())
            put(PebbleKeys.UNIT_SPEED, speedUnit)
            put(PebbleKeys.UNIT_TEMP, tempUnit)
        }
    }
}
