package com.eried.eucplanet.data.model

/**
 * One metric value, formatted with its unit, in the rider's units.
 *
 * Lifted out of the dashboard because it stopped being the dashboard's alone:
 * a rider asking for a value out loud wants "eighty point six volts", not
 * "eighty point six". Two copies of this would drift, and the one that drifted
 * would be the spoken one, which nobody is looking at to notice.
 *
 * Free of Android and of Compose, so it can be tested directly and called from
 * a service.
 */
object MetricValueFormat {

    /**
     * @param key   catalog metric key
     * @param raw   the value in its stored unit (km/h, degrees C, kPa, metres)
     */
    fun format(
        key: String,
        raw: Float,
        speedUnit: String,
        speedUnitLabel: String,
        tempUnit: String,
        tempUnitLabel: String,
        distanceUnit: String,
        pressureUnit: String,
    ): String = when (key) {

    "BATTERY", "BATTERY_ENVELOPE" -> "${raw.toInt()}%"
    // Round to match the live LOAD tile (which uses %.0f), not truncate.
    "LOAD" -> "%.0f%%".format(raw)
    "BATTERY_1", "BATTERY_2", "PHONE_BATTERY", "EXTERNAL_GPS_BATTERY" -> "%.0f%%".format(raw)
    // Temp buffers store raw °C; convert to the rider's unit like the tile.
    // Round (not toInt) and carry the °C/°F label so it reads like the tile.
    "TEMPERATURE", "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP" ->
        "%.0f%s".format(com.eried.eucplanet.util.Units.temperature(raw, tempUnit), tempUnitLabel)
    "VOLTAGE" -> "%.1fV".format(raw)
    "CURRENT", "DYN_CURRENT_LIMIT" -> "%.1fA".format(raw)
    // Speed buffers store raw km/h; convert to the rider's speed unit.
    "SPEED", "DYN_SPEED_LIMIT" ->
        "%.0f %s".format(com.eried.eucplanet.util.Units.speed(raw, speedUnit), speedUnitLabel)
    // GPS speed keeps 1 decimal to match its live tile (displayValueFor).
    "GPS_SPEED" ->
        "%.1f %s".format(com.eried.eucplanet.util.Units.speed(raw, speedUnit), speedUnitLabel)
    "MOTOR_POWER", "BATTERY_POWER", "POWER" -> "%.0fW".format(raw)
    "PITCH", "ROLL" -> "%.1f°".format(raw)
    "G_FORCE", "LATERAL_G", "FORWARD_G" -> "%.2fg".format(raw)
    "TORQUE" -> "%.1fNm".format(raw)
    "PHASE_CURRENT" -> "%.1fA".format(raw)
    // Tire pressure stored raw in kPa, printed in the rider's own pressure
    // unit - a setting, not the distance unit it used to be guessed from.
    "TIRE_PRESSURE" -> com.eried.eucplanet.util.Units.formatPressure(raw, pressureUnit)
    // Altitude / accuracy stored raw in metres; feet for imperial riders.
    "GPS_ALTITUDE", "GPS_ACCURACY" -> if (distanceUnit == "mi")
        "%.0fft".format(raw * 3.28084f)
    else
        "%.0fm".format(raw)
    "BT_RSSI" -> "%.0f dBm".format(raw)
    // Distances stored raw in km. Without this they fell to the bare "%.1f"
    // below: a spoken odometer was a naked number, and an imperial rider was
    // read kilometres with nothing to say so.
    "ODOMETER", "TRIP", "TRIP_METER" -> "%.1f %s".format(
        com.eried.eucplanet.util.Units.distance(raw, distanceUnit),
        com.eried.eucplanet.util.Units.distanceUnit(distanceUnit),
    )
    "WH_PER_KM" -> formatWhPerDistance(raw, distanceUnit) ?: "-"
    "RANGE_ESTIMATE" -> "%.0f %s".format(
        com.eried.eucplanet.util.Units.distance(raw, distanceUnit),
        com.eried.eucplanet.util.Units.distanceUnit(distanceUnit),
    )
    else -> "%.1f".format(raw)
    }

    /**
     * Energy per distance, in the rider's distance unit. Null when there is
     * nothing to say: NaN, or a rate that has not been measured yet.
     */
    private fun formatWhPerDistance(whPerKm: Float, distanceUnit: String): String? {
        if (whPerKm.isNaN() || whPerKm <= 0f) return null
        val unitsPerKm = com.eried.eucplanet.util.Units.distance(1f, distanceUnit)
        if (unitsPerKm <= 0f) return null
        return "%.0f Wh/%s".format(
            whPerKm / unitsPerKm,
            com.eried.eucplanet.util.Units.distanceUnit(distanceUnit),
        )
    }
}
