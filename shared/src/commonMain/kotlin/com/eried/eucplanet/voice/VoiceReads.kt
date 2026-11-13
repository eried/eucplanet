package com.eried.eucplanet.voice

/**
 * What [EXTRACTORS] reads, in order, and whether the registry's "no value"
 * sentinel is dropped (true) or passed through as held (false). A NaN passed
 * through is answered as "no data yet" just like a null.
 */
val VOICE_READS: List<Pair<String, Boolean>> = listOf(
    "VOLTAGE" to false,
    // Temperature slots as held: a missing slot is null, an implausible one
    // is read out.
    "MOTOR_TEMP" to false,
    "CONTROLLER_TEMP" to false,
    "BATTERY_TEMP" to false,
    "ODOMETER" to false,
    // As held, -1 included.
    "TRIP_METER" to false,
    "PITCH" to false,
    "ROLL" to false,
    "G_FORCE" to false,
    "FORWARD_G" to false,
    "LATERAL_G" to false,
    "TORQUE" to false,
    "PHASE_CURRENT" to false,
    "BATTERY_1" to false,
    "BATTERY_2" to false,
    "BATTERY_ENVELOPE" to false,
    // A wheel with no sensor reads 0, which is not a pressure. hasTirePressure
    // is the wheel saying whether it has one at all, and the rider hears
    // "your wheel does not report tyre pressure" instead of "0".
    "TIRE_PRESSURE" to true,
    "WH_CONSUMED" to false,
    "REGEN_WH" to false,
    "WH_PER_KM" to false,
    "RANGE_ESTIMATE" to false,
    "GPS_ALTITUDE" to false,
    // Negative (-1) means no fix / not reported.
    "GPS_SPEED" to true,
    "DYN_SPEED_LIMIT" to false,
    "DYN_CURRENT_LIMIT" to false,
    // -1 means the wheel does not send it (every family but Begode).
    "AUTO_OFF" to true,
    "WHEEL_MAX_SPEED" to true,
    "WHEEL_ALARM_SPEED" to true,
    // 0 dBm is "not read yet".
    "BT_RSSI" to true,
    "EXTERNAL_GPS_BATTERY" to true,
)

/**
 * Live raw values for the metrics with no spoken report of their own.
 * Callers format them with formatMetric, which adds the rider's units.
 */
val EXTRACTORS: Map<String, (com.eried.eucplanet.data.model.WheelData) -> Float?> =
    VOICE_READS.associate { (key, filtered) ->
        val def = com.eried.eucplanet.data.model.MetricRegistry.def(key)
        key to if (filtered) {
            { w: com.eried.eucplanet.data.model.WheelData -> def.read(w) }
        } else {
            { w: com.eried.eucplanet.data.model.WheelData -> def.readRaw(w) }
        }
    }
