package com.eried.eucplanet.data.model

import com.eried.eucplanet.util.MetricSanity

/**
 * What a metric's value measures, so a consumer can pick the unit setting it
 * converts against. Declarative only: nothing here converts or formats yet.
 */
enum class MetricUnitKind {
    SPEED,
    TEMPERATURE,
    DISTANCE,
    PRESSURE,
    /** Metres, shown as m or ft. GPS accuracy is held the same way. */
    ALTITUDE,
    PERCENT,
    VOLTAGE,
    CURRENT,
    POWER,
    ENERGY,
    /** Energy per distance, held as Wh per km. */
    CONSUMPTION,
    TORQUE,
    G,
    ANGLE,
    RPM,
    /** Link strength in dBm. */
    SIGNAL,
    TIME,
    /** A state rather than a quantity (ride mode, light on or off). */
    STATE,
    /** A value that is not a single number, such as a lat / long pair. */
    NONE,
}

/** Where a metric's value comes from. */
enum class MetricSource {
    /** Wheel telemetry, as the adapter decoded it. */
    WHEEL,
    /** The phone's own fused location. */
    PHONE_GPS,
    /** A paired GPS box (RaceBox / Dragy). */
    EXTERNAL_GPS,
    /** The phone's accelerometer. */
    PHONE_IMU,
    /** Something else the phone knows: its battery, the BLE link, the trip meter. */
    PHONE,
    /** Worked out from other values (energy integrals, range, trip aggregates). */
    DERIVED,
    /** A tyre pressure sensor, either a valve cap or one the wheel relays. */
    TPMS,
    /** A paired rear radar. */
    RADAR,
}

/**
 * The value a field holds when it has nothing to say, per the convention that
 * field already follows in [WheelData]. [MetricDef.read] turns it into null;
 * [MetricDef.readRaw] leaves it in place.
 */
enum class MetricAbsence {
    /** Every value is a reading, including 0. */
    NEVER,
    /** NaN means "not yet" (battery envelope, consumption, range, altitude). */
    NAN,
    /**
     * A negative value means "not paired / no sample"; the field defaults to
     * -1. NaN counts as absent too, matching the `>= 0f` guards consumers use.
     */
    NEGATIVE,
    /** 0 means "not read yet" (BLE RSSI: 0 dBm is not a perfect link). */
    ZERO,
    /** Absent while nothing measures the tyre. 0 kPa with a sensor is a flat tyre, a reading. */
    NO_TIRE_SENSOR,
    /** Outside the plausible sensor window, or NaN. See [MetricSanity.isPlausibleTempC]. */
    IMPLAUSIBLE_TEMP;

    fun isAbsent(value: Float, data: WheelData): Boolean = when (this) {
        NEVER -> false
        NAN -> value.isNaN()
        NEGATIVE -> !(value >= 0f)
        ZERO -> value == 0f
        NO_TIRE_SENSOR -> !data.hasTirePressure
        IMPLAUSIBLE_TEMP -> !MetricSanity.isPlausibleTempC(value)
    }
}

/**
 * One metric's value extraction, declared once.
 *
 * [raw] reads the field exactly as [WheelData] holds it: signed, in the
 * wheel's base unit (km/h, km, °C, kPa, W, Wh, m), with no abs, clamp or
 * conversion. It is null only where the value is structurally missing (a
 * temperature slot the wheel did not send) or where the metric is not on
 * [WheelData] at all (trip aggregates, phone battery, radar).
 *
 * Consumers still apply their own adjustments at the call site (abs for
 * alarms, a 0 stand-in for gauges, unit conversion for display). Those
 * differences are deliberate and are listed on each entry in
 * [MetricRegistry.all] so they can be reconciled on purpose later.
 */
data class MetricDef(
    /** Canonical key, the one [MetricCatalog] and the alarm engine use. */
    val key: String,
    /** Other keys that mean the same reading in some consumer (widget, studio, legacy). */
    val aliases: List<String> = emptyList(),
    val unit: MetricUnitKind,
    val source: MetricSource,
    val absence: MetricAbsence = MetricAbsence.NEVER,
    /** The raw field read. Null when the metric has no [WheelData] field. */
    val raw: ((WheelData) -> Float?)? = null,
) {
    /** True when the value can be read from a [WheelData] frame at all. */
    val onWheelData: Boolean get() = raw != null

    /** The field as held, sentinels included, or null when it is structurally missing. */
    fun readRaw(data: WheelData): Float? = raw?.invoke(data)

    /** The field as held, or null when it holds this metric's "no value" sentinel. */
    fun read(data: WheelData): Float? =
        readRaw(data)?.takeUnless { absence.isAbsent(it, data) }
}

/**
 * Single source of truth for pulling a metric's raw value out of a
 * [WheelData] frame. [MetricCatalog] keeps the UI metadata; this keeps the
 * extraction, so the dashboard, detail graphs, history buffers, alarms,
 * voice and the service overlay stop each carrying their own copy.
 *
 * Known disagreements between consumers, preserved on purpose for now:
 *  - "POWER" resolves here to [WheelData.batteryPower] (dashboard, detail,
 *    history buffer, service overlay). The Overlay Studio's POWER reads
 *    [WheelData.motorPower], and the home screen widget's POWER shows
 *    |voltage x current|. Neither of those is migrated onto this alias.
 *  - Alarms and the studio compare speed and PWM by magnitude; the tiles
 *    print them signed.
 *  - The studio draws 0 for a NaN / negative sentinel; the tiles show "--".
 *
 * MetricRegistryTest guards that every catalog, alarm, studio and widget
 * key resolves here or is listed as a known exception.
 */
object MetricRegistry {

    private fun temp(index: Int): (WheelData) -> Float? = { it.temperatures.getOrNull(index) }

    val all: List<MetricDef> = listOf(
        // ---- Wheel telemetry ----

        // Tile: "--" at 0. Alarm, studio: as is.
        MetricDef("BATTERY", unit = MetricUnitKind.PERCENT, source = MetricSource.WHEEL,
            raw = { it.batteryPercent.toFloat() }),
        // NaN for the first half minute of a ride. Studio draws 0 instead;
        // voice and the detail screen take the NaN as is.
        MetricDef("BATTERY_ENVELOPE", unit = MetricUnitKind.PERCENT, source = MetricSource.DERIVED,
            absence = MetricAbsence.NAN, raw = { it.batteryEnvelope }),
        // The hottest sensor. Tile and widget show "--" at 0 or below; the
        // alarm reads it as is.
        MetricDef("TEMPERATURE", aliases = listOf("TEMP"), unit = MetricUnitKind.TEMPERATURE,
            source = MetricSource.WHEEL, raw = { it.maxTemperature }),
        // Tile: "--" at 0.
        MetricDef("VOLTAGE", unit = MetricUnitKind.VOLTAGE, source = MetricSource.WHEEL,
            raw = { it.voltage }),
        // Signed (negative on regen). Alarm and widget use the magnitude.
        MetricDef("CURRENT", unit = MetricUnitKind.CURRENT, source = MetricSource.WHEEL,
            raw = { it.current }),
        // PWM, signed on some families. Alarm and studio use the magnitude;
        // the widget shows "--" on NaN.
        MetricDef("LOAD", aliases = listOf("PWM"), unit = MetricUnitKind.PERCENT,
            source = MetricSource.WHEEL, raw = { it.pwm }),
        MetricDef("TRIP", unit = MetricUnitKind.DISTANCE, source = MetricSource.WHEEL,
            raw = { it.tripDistance }),
        // Signed (negative riding backwards). Alarm and studio use the magnitude.
        MetricDef("SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.WHEEL,
            raw = { it.speed }),
        MetricDef("ODOMETER", aliases = listOf("ODO"), unit = MetricUnitKind.DISTANCE,
            source = MetricSource.WHEEL, raw = { it.totalDistance }),
        // Studio POWER reads this field, not the POWER alias below.
        MetricDef("MOTOR_POWER", unit = MetricUnitKind.POWER, source = MetricSource.WHEEL,
            raw = { it.motorPower.toFloat() }),
        // POWER is the pre-rename dashboard key for this tile.
        MetricDef("BATTERY_POWER", aliases = listOf("POWER"), unit = MetricUnitKind.POWER,
            source = MetricSource.WHEEL, raw = { it.batteryPower.toFloat() }),
        // Tile: "--" at 0.
        MetricDef("BATTERY_1", unit = MetricUnitKind.PERCENT, source = MetricSource.WHEEL,
            raw = { it.battery1Percent }),
        MetricDef("BATTERY_2", unit = MetricUnitKind.PERCENT, source = MetricSource.WHEEL,
            raw = { it.battery2Percent }),
        MetricDef("PITCH", unit = MetricUnitKind.ANGLE, source = MetricSource.WHEEL,
            raw = { it.pitchAngle }),
        MetricDef("ROLL", unit = MetricUnitKind.ANGLE, source = MetricSource.WHEEL,
            raw = { it.rollAngle }),
        // Signed torque and phase current. Alarm and widget use the magnitude.
        MetricDef("TORQUE", unit = MetricUnitKind.TORQUE, source = MetricSource.WHEEL,
            raw = { it.torque }),
        MetricDef("PHASE_CURRENT", unit = MetricUnitKind.CURRENT, source = MetricSource.WHEEL,
            raw = { it.phaseCurrent }),
        // Tile: "--" at 0 or below.
        MetricDef("DYN_SPEED_LIMIT", unit = MetricUnitKind.SPEED, source = MetricSource.WHEEL,
            raw = { it.dynamicSpeedLimit }),
        MetricDef("DYN_CURRENT_LIMIT", unit = MetricUnitKind.CURRENT, source = MetricSource.WHEEL,
            raw = { it.dynamicCurrentLimit }),
        // Seconds to the wheel's auto power-off; -1 on wheels that do not send it.
        MetricDef("AUTO_OFF", unit = MetricUnitKind.TIME, source = MetricSource.WHEEL,
            absence = MetricAbsence.NEGATIVE, raw = { it.autoOffSeconds.toFloat() }),
        // Per-sensor temperatures. Null when the wheel sends fewer slots. The
        // tile, history buffer and alarm also drop implausible readings
        // (read); voice, the detail screen and the service overlay take the
        // slot as is (readRaw).
        MetricDef("MOTOR_TEMP", unit = MetricUnitKind.TEMPERATURE, source = MetricSource.WHEEL,
            absence = MetricAbsence.IMPLAUSIBLE_TEMP, raw = temp(0)),
        MetricDef("CONTROLLER_TEMP", unit = MetricUnitKind.TEMPERATURE, source = MetricSource.WHEEL,
            absence = MetricAbsence.IMPLAUSIBLE_TEMP, raw = temp(1)),
        MetricDef("BATTERY_TEMP", unit = MetricUnitKind.TEMPERATURE, source = MetricSource.WHEEL,
            absence = MetricAbsence.IMPLAUSIBLE_TEMP, raw = temp(2)),
        // Firmware-reported limits, -1 when the adapter does not surface them.
        // The tile shows "--" at 0 or below; voice drops only negatives.
        MetricDef("WHEEL_MAX_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.WHEEL,
            absence = MetricAbsence.NEGATIVE, raw = { it.wheelMaxSpeedKmh }),
        MetricDef("WHEEL_ALARM_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.WHEEL,
            absence = MetricAbsence.NEGATIVE, raw = { it.wheelAlarmSpeedKmh }),
        // 0 lock, 1 drive, 2 shutdown, 3 idle, -1 unknown. A state, not a quantity.
        MetricDef("PC_MODE", unit = MetricUnitKind.STATE, source = MetricSource.WHEEL,
            absence = MetricAbsence.NEGATIVE, raw = { it.pcMode.toFloat() }),
        // A Boolean on WheelData, read here as 1 for on and 0 for off.
        MetricDef("LIGHT_ON", unit = MetricUnitKind.STATE, source = MetricSource.WHEEL,
            raw = { if (it.lightOn) 1f else 0f }),

        // ---- Tyre pressure ----

        // kPa. Only a reading while hasTirePressure is true; the detail screen
        // and the studio read the field regardless (readRaw).
        MetricDef("TIRE_PRESSURE", unit = MetricUnitKind.PRESSURE, source = MetricSource.TPMS,
            absence = MetricAbsence.NO_TIRE_SENSOR, raw = { it.tirePressureKpa }),

        // ---- Phone IMU ----

        // Magnitude, never negative.
        MetricDef("G_FORCE", aliases = listOf("G-FORCE"), unit = MetricUnitKind.G,
            source = MetricSource.PHONE_IMU, raw = { it.gForce }),
        // Signed (+right). The alarm uses the magnitude.
        MetricDef("LATERAL_G", unit = MetricUnitKind.G, source = MetricSource.PHONE_IMU,
            raw = { it.accelX }),
        // From wheel-speed change, not the IMU forward axis (accelY), so the
        // tile, the graph and the voice read the same quantity.
        MetricDef("FORWARD_G", unit = MetricUnitKind.G, source = MetricSource.DERIVED,
            raw = { it.forwardGFromSpeed }),

        // ---- Phone ----

        // 0 dBm is "not read yet".
        MetricDef("BT_RSSI", unit = MetricUnitKind.SIGNAL, source = MetricSource.PHONE,
            absence = MetricAbsence.ZERO, raw = { it.rssiDbm.toFloat() }),
        // -1 until merged in (studio / HUD / voice). Studio draws 0; voice
        // reads the -1 as is.
        MetricDef("TRIP_METER", unit = MetricUnitKind.DISTANCE, source = MetricSource.PHONE,
            absence = MetricAbsence.NEGATIVE, raw = { it.tripMeterKm }),
        // Not on WheelData: the dashboard reads the phone battery receiver.
        MetricDef("PHONE_BATTERY", unit = MetricUnitKind.PERCENT, source = MetricSource.PHONE),

        // ---- Phone GPS ----

        // -1 with no fix. Studio draws 0; voice drops it. The dashboard tile
        // reads the Location, not this field.
        MetricDef("GPS_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.PHONE_GPS,
            absence = MetricAbsence.NEGATIVE, raw = { it.gpsSpeedKmh }),
        // NaN with no fix (below sea level is a reading). Studio draws 0;
        // voice takes the NaN as is.
        MetricDef("GPS_ALTITUDE", unit = MetricUnitKind.ALTITUDE, source = MetricSource.PHONE_GPS,
            absence = MetricAbsence.NAN, raw = { it.gpsAltitudeM }),
        // Not on WheelData: the dashboard reads the Location.
        MetricDef("GPS_HEADING", unit = MetricUnitKind.ANGLE, source = MetricSource.PHONE_GPS),
        MetricDef("GPS_ACCURACY", unit = MetricUnitKind.ALTITUDE, source = MetricSource.PHONE_GPS),
        // Two doubles, not one number, so there is no scalar read. The studio
        // calls it GPS.
        MetricDef("LAT_LONG", aliases = listOf("GPS"), unit = MetricUnitKind.NONE,
            source = MetricSource.PHONE_GPS),

        // ---- External GPS ----

        // -1 when no box is paired. Studio and alarm label it EXT_GPS_*.
        MetricDef("EXTERNAL_GPS_BATTERY", aliases = listOf("EXT_GPS_BATTERY"),
            unit = MetricUnitKind.PERCENT, source = MetricSource.EXTERNAL_GPS,
            absence = MetricAbsence.NEGATIVE, raw = { it.externalGpsBatteryPercent.toFloat() }),
        MetricDef("EXTERNAL_GPS_SPEED", aliases = listOf("EXT_GPS_SPEED"),
            unit = MetricUnitKind.SPEED, source = MetricSource.EXTERNAL_GPS,
            absence = MetricAbsence.NEGATIVE, raw = { it.externalGpsSpeedKmh }),

        // ---- Energy and range ----

        // Running totals since connect. Tile: "--" at 0.
        MetricDef("WH_CONSUMED", unit = MetricUnitKind.ENERGY, source = MetricSource.DERIVED,
            raw = { it.whConsumed }),
        MetricDef("REGEN_WH", unit = MetricUnitKind.ENERGY, source = MetricSource.DERIVED,
            raw = { it.whRegen }),
        // NaN until the rolling window holds enough distance. Studio draws 0;
        // voice takes the NaN as is.
        MetricDef("WH_PER_KM", unit = MetricUnitKind.CONSUMPTION, source = MetricSource.DERIVED,
            absence = MetricAbsence.NAN, raw = { it.whPerKmRecent }),
        MetricDef("RANGE_ESTIMATE", unit = MetricUnitKind.DISTANCE, source = MetricSource.DERIVED,
            absence = MetricAbsence.NAN, raw = { it.rangeKmEstimate }),

        // ---- Not on WheelData: trip recorder, graphs, radar ----

        MetricDef("HEADROOM", unit = MetricUnitKind.SPEED, source = MetricSource.DERIVED),
        MetricDef("TRIP_TIME", unit = MetricUnitKind.TIME, source = MetricSource.DERIVED),
        MetricDef("TRIP_MAX_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.DERIVED),
        MetricDef("AVG_TRIP_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.DERIVED),
        MetricDef("SLOPE", unit = MetricUnitKind.PERCENT, source = MetricSource.DERIVED),
        MetricDef("ASCENT", unit = MetricUnitKind.ALTITUDE, source = MetricSource.DERIVED),
        MetricDef("DESCENT", unit = MetricUnitKind.ALTITUDE, source = MetricSource.DERIVED),
        MetricDef("MOTOR_RPM", unit = MetricUnitKind.RPM, source = MetricSource.WHEEL),
        // Radar frames reach the alarm engine through their own entry point.
        MetricDef("RADAR_DISTANCE", unit = MetricUnitKind.ALTITUDE, source = MetricSource.RADAR),
        MetricDef("RADAR_APPROACH_SPEED", unit = MetricUnitKind.SPEED, source = MetricSource.RADAR),
    )

    private val byKeyMap: Map<String, MetricDef> = buildMap {
        for (def in all) {
            put(def.key, def)
            for (alias in def.aliases) put(alias, def)
        }
    }

    /** The definition for [key], by canonical key or alias. */
    fun byKey(key: String): MetricDef? = byKeyMap[key]

    /** Must-exist lookup for code that names a key it knows is registered. */
    fun def(key: String): MetricDef =
        byKeyMap[key] ?: error("Metric $key is not in MetricRegistry")

    /** [MetricDef.read] for [key], or null when the key is unknown. */
    fun read(key: String, data: WheelData): Float? = byKey(key)?.read(data)

    /** [MetricDef.readRaw] for [key], or null when the key is unknown. */
    fun readRaw(key: String, data: WheelData): Float? = byKey(key)?.readRaw(data)
}
