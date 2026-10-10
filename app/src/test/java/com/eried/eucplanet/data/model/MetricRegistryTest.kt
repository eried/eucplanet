package com.eried.eucplanet.data.model

import com.eried.eucplanet.data.repository.EXTRA_HISTORY_METRICS
import com.eried.eucplanet.diagnostics.rawMetricValue
import com.eried.eucplanet.service.AlarmLogic
import com.eried.eucplanet.service.AlarmWheelPass
import com.eried.eucplanet.ui.dashboard.rawCurrentValueFor
import com.eried.eucplanet.ui.studio.StudioMetric
import com.eried.eucplanet.util.MetricSanity
import com.eried.eucplanet.voice.EXTRACTORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.absoluteValue

/**
 * Drift guard (CONVENTIONS rule 13) and equivalence proof for [MetricRegistry].
 *
 * The consumers moved onto the registry must return exactly what they
 * returned before, sentinels, signs and NaN included. The old extraction code
 * is copied below verbatim as the golden reference, and every migrated
 * consumer is compared against it over a spread of frames: riding, braking,
 * charging, parked, fresh-connect defaults and broken sensors.
 */
class MetricRegistryTest {

    // --- Sample frames ------------------------------------------------------

    private val frames: List<Pair<String, WheelData>> = listOf(
        "defaults" to WheelData(timestamp = 0L),
        "riding" to WheelData(
            speed = 32.4f, voltage = 96.3f, current = 18.7f, batteryPercent = 72,
            battery1Percent = 71.5f, battery2Percent = 73.2f, pwm = 41.2f, torque = 22.5f,
            phaseCurrent = 64.1f, temperatures = listOf(48.5f, 42.25f, 31f), maxTemperature = 48.5f,
            tripDistance = 12.345f, totalDistance = 4321.987f, pitchAngle = 2.35f, rollAngle = -1.2f,
            latitude = 59.91, longitude = 10.75, externalGpsBatteryPercent = 88,
            externalGpsSpeedKmh = 32.1f, gpsSpeedKmh = 31.9f, gpsAltitudeM = 123.4f, tripMeterKm = 5.5f,
            gForce = 1.12f, accelX = 0.21f, accelY = 0.1f, forwardGFromSpeed = 0.08f,
            batteryPower = 1800, motorPower = 1650, whConsumed = 245.6f, whRegen = 12.3f,
            whPerKmRecent = 19.8f, rangeKmEstimate = 41.7f, dynamicSpeedLimit = 55f,
            dynamicCurrentLimit = 80f, autoOffSeconds = 5560, lightOn = true, tirePressureKpa = 241f, hasTirePressure = true,
            pcMode = 1, wheelMaxSpeedKmh = 60f, wheelAlarmSpeedKmh = 50f, rssiDbm = -67,
            batteryEnvelope = 70.4f, timestamp = 1L,
        ),
        "braking" to WheelData(
            speed = -8.6f, voltage = 99.1f, current = -24.3f, batteryPercent = 64, pwm = -33.3f,
            torque = -41.7f, phaseCurrent = -231f, temperatures = listOf(55f, 50f),
            maxTemperature = 55f, pitchAngle = -6.5f, rollAngle = 3.1f, gForce = 0.7f,
            accelX = -0.44f, forwardGFromSpeed = -0.52f, batteryPower = -2400, motorPower = -2100,
            whPerKmRecent = -3.2f, rangeKmEstimate = 0f, dynamicSpeedLimit = -1f,
            dynamicCurrentLimit = -5f, pcMode = 1, wheelMaxSpeedKmh = 0f, rssiDbm = -91,
            timestamp = 2L,
        ),
        "charging" to WheelData(
            voltage = 100.8f, current = -6.2f, batteryPercent = 97, battery1Percent = 97f,
            battery2Percent = 96f, charging = true, batteryPower = -620, motorPower = 0,
            temperatures = listOf(30f), maxTemperature = 30f, pcMode = 3, whRegen = 0f,
            batteryEnvelope = 96.2f, timestamp = 3L,
        ),
        "parked, sensors broken" to WheelData(
            speed = 0f, voltage = 0f, current = 0f, batteryPercent = 0, pwm = Float.NaN,
            temperatures = listOf(-273f, Float.NaN, 150.01f), maxTemperature = -273f,
            gpsSpeedKmh = Float.NaN, gpsAltitudeM = -12.5f, externalGpsBatteryPercent = 0,
            externalGpsSpeedKmh = 0f, tripMeterKm = 0f, tirePressureKpa = 180f,
            hasTirePressure = false, pcMode = 0, lockedReported = true, wheelMaxSpeedKmh = -1f,
            wheelAlarmSpeedKmh = -0.5f, rssiDbm = 0, timestamp = 4L,
        ),
        "flat tyre, edge temps" to WheelData(
            speed = -0f, temperatures = listOf(-40f, 150f, -40.01f, 99f), maxTemperature = 150f,
            tirePressureKpa = 0f, hasTirePressure = true, gpsSpeedKmh = 0f, gpsAltitudeM = Float.NaN,
            whConsumed = Float.NaN, whPerKmRecent = Float.NaN, rangeKmEstimate = Float.NaN,
            batteryEnvelope = Float.NaN, wheelMaxSpeedKmh = 0f, wheelAlarmSpeedKmh = 0f,
            tripMeterKm = -1f, externalGpsSpeedKmh = -1f, timestamp = 5L,
        ),
        "nan everywhere" to WheelData(
            speed = Float.NaN, voltage = Float.NaN, current = Float.NaN, pwm = Float.NaN,
            torque = Float.NaN, phaseCurrent = Float.NaN, battery1Percent = Float.NaN,
            battery2Percent = Float.NaN, maxTemperature = Float.NaN, tripDistance = Float.NaN,
            totalDistance = Float.NaN, pitchAngle = Float.NaN, rollAngle = Float.NaN,
            gForce = Float.NaN, accelX = Float.NaN, forwardGFromSpeed = Float.NaN,
            whConsumed = Float.NaN, whRegen = Float.NaN, dynamicSpeedLimit = Float.NaN,
            dynamicCurrentLimit = Float.NaN, tirePressureKpa = Float.NaN, hasTirePressure = true,
            wheelMaxSpeedKmh = Float.NaN, wheelAlarmSpeedKmh = Float.NaN, tripMeterKm = Float.NaN,
            externalGpsSpeedKmh = Float.NaN, timestamp = 6L,
        ),
    )

    /** Every key any consumer could be asked about, plus two it never is. */
    private val allKeys: List<String> = (
        MetricRegistry.all.flatMap { listOf(it.key) + it.aliases } +
            MetricCatalog.keys +
            AlarmMetric.entries.map { it.name } +
            StudioMetric.entries.map { it.key } +
            WidgetMetricType.entries.map { it.key } +
            listOf("NOPE", "", "power", "M:uuid")
        ).distinct()

    private fun eachFrameAndKey(block: (String, WheelData, String) -> Unit) {
        for ((name, frame) in frames) for (key in allKeys) block(name, frame, key)
    }

    // --- Golden references: the extraction code as it was before ------------

    /** MetricDetailScreen.rawCurrentValueFor before the registry. */
    private fun oldRawCurrentValueFor(key: String, w: WheelData): Float = when (key) {
        "POWER", "BATTERY_POWER" -> w.batteryPower.toFloat()
        "MOTOR_POWER" -> w.motorPower.toFloat()
        "ODOMETER" -> w.totalDistance
        "BATTERY_1" -> w.battery1Percent
        "BATTERY_2" -> w.battery2Percent
        "BATTERY_ENVELOPE" -> w.batteryEnvelope
        "PITCH" -> w.pitchAngle
        "ROLL" -> w.rollAngle
        "G_FORCE" -> w.gForce
        "LATERAL_G" -> w.accelX
        "FORWARD_G" -> w.forwardGFromSpeed
        "TORQUE" -> w.torque
        "PHASE_CURRENT" -> w.phaseCurrent
        "DYN_SPEED_LIMIT" -> w.dynamicSpeedLimit
        "DYN_CURRENT_LIMIT" -> w.dynamicCurrentLimit
        "MOTOR_TEMP" -> w.temperatures.getOrNull(0) ?: 0f
        "CONTROLLER_TEMP" -> w.temperatures.getOrNull(1) ?: 0f
        "BATTERY_TEMP" -> w.temperatures.getOrNull(2) ?: 0f
        "TIRE_PRESSURE" -> w.tirePressureKpa
        else -> 0f
    }

    /** ServiceOverlay.rawMetricValue before the registry. */
    private fun oldRawMetricValue(key: String, wheel: WheelData): String = when (key) {
        "BATTERY" -> wheel.batteryPercent.toString()
        "TEMPERATURE" -> "%.1f".format(wheel.maxTemperature)
        "VOLTAGE" -> "%.2f".format(wheel.voltage)
        "CURRENT" -> "%.2f".format(wheel.current)
        "LOAD" -> "%.1f".format(wheel.pwm)
        "TRIP" -> "%.3f".format(wheel.tripDistance)
        "SPEED" -> "%.2f".format(wheel.speed)
        "POWER" -> "${wheel.batteryPower}"
        "ODOMETER" -> "%.3f".format(wheel.totalDistance)
        "MOTOR_POWER" -> "${wheel.motorPower}"
        "BATTERY_POWER" -> "${wheel.batteryPower}"
        "BATTERY_1" -> "%.1f".format(wheel.battery1Percent)
        "BATTERY_2" -> "%.1f".format(wheel.battery2Percent)
        "PITCH" -> "%.2f".format(wheel.pitchAngle)
        "ROLL" -> "%.2f".format(wheel.rollAngle)
        "G_FORCE" -> "%.3f".format(wheel.gForce)
        "LATERAL_G" -> "%.3f".format(wheel.accelX)
        "FORWARD_G" -> "%.3f".format(wheel.forwardGFromSpeed)
        "TORQUE" -> "%.2f".format(wheel.torque)
        "PHASE_CURRENT" -> "%.2f".format(wheel.phaseCurrent)
        "DYN_SPEED_LIMIT" -> "%.2f".format(wheel.dynamicSpeedLimit)
        "DYN_CURRENT_LIMIT" -> "%.2f".format(wheel.dynamicCurrentLimit)
        "AUTO_OFF" -> "${wheel.autoOffSeconds}"
        "MOTOR_TEMP" -> wheel.temperatures.getOrNull(0)?.let { "%.1f".format(it) } ?: "-"
        "CONTROLLER_TEMP" -> wheel.temperatures.getOrNull(1)?.let { "%.1f".format(it) } ?: "-"
        "BATTERY_TEMP" -> wheel.temperatures.getOrNull(2)?.let { "%.1f".format(it) } ?: "-"
        else -> "-"
    }

    /** VoiceCommandController.EXTRACTORS before the registry. */
    private val oldExtractors: Map<String, (WheelData) -> Float?> = mapOf(
        "VOLTAGE" to { it.voltage },
        "MOTOR_TEMP" to { it.temperatures.firstOrNull() },
        "CONTROLLER_TEMP" to { it.temperatures.getOrNull(1) },
        "BATTERY_TEMP" to { it.temperatures.getOrNull(2) },
        "ODOMETER" to { it.totalDistance },
        "TRIP_METER" to { it.tripMeterKm },
        "PITCH" to { it.pitchAngle },
        "ROLL" to { it.rollAngle },
        "G_FORCE" to { it.gForce },
        "FORWARD_G" to { it.forwardGFromSpeed },
        "LATERAL_G" to { it.accelX },
        "TORQUE" to { it.torque },
        "PHASE_CURRENT" to { it.phaseCurrent },
        "BATTERY_1" to { it.battery1Percent },
        "BATTERY_2" to { it.battery2Percent },
        "BATTERY_ENVELOPE" to { it.batteryEnvelope },
        "TIRE_PRESSURE" to { if (it.hasTirePressure) it.tirePressureKpa else null },
        "WH_CONSUMED" to { it.whConsumed },
        "REGEN_WH" to { it.whRegen },
        "WH_PER_KM" to { it.whPerKmRecent },
        "RANGE_ESTIMATE" to { it.rangeKmEstimate },
        "GPS_ALTITUDE" to { it.gpsAltitudeM },
        "GPS_SPEED" to { it.gpsSpeedKmh.takeIf { v -> v >= 0f } },
        "DYN_SPEED_LIMIT" to { it.dynamicSpeedLimit },
        "DYN_CURRENT_LIMIT" to { it.dynamicCurrentLimit },
        "AUTO_OFF" to { it.autoOffSeconds.toFloat().takeIf { v -> v >= 0f } },
        "WHEEL_MAX_SPEED" to { it.wheelMaxSpeedKmh.takeIf { v -> v >= 0f } },
        "WHEEL_ALARM_SPEED" to { it.wheelAlarmSpeedKmh.takeIf { v -> v >= 0f } },
        "BT_RSSI" to { it.rssiDbm.toFloat().takeIf { v -> v != 0f } },
        "EXTERNAL_GPS_BATTERY" to { it.externalGpsBatteryPercent.toFloat().takeIf { v -> v >= 0f } },
    )

    /** WheelRepository.EXTRA_HISTORY_METRICS before the registry. */
    private val oldExtraHistory: List<Pair<String, (WheelData) -> Float?>> = listOf(
        "MOTOR_POWER" to { it.motorPower.toFloat() },
        "BATTERY_POWER" to { it.batteryPower.toFloat() },
        "POWER" to { it.batteryPower.toFloat() },
        "BATTERY_ENVELOPE" to { it.batteryEnvelope.takeIf { v -> !v.isNaN() } },
        "BATTERY_1" to { it.battery1Percent },
        "BATTERY_2" to { it.battery2Percent },
        "PITCH" to { it.pitchAngle },
        "ROLL" to { it.rollAngle },
        "G_FORCE" to { it.gForce },
        "LATERAL_G" to { it.accelX },
        "FORWARD_G" to { it.forwardGFromSpeed },
        "TORQUE" to { it.torque },
        "PHASE_CURRENT" to { it.phaseCurrent },
        "DYN_SPEED_LIMIT" to { it.dynamicSpeedLimit },
        "DYN_CURRENT_LIMIT" to { it.dynamicCurrentLimit },
        "MOTOR_TEMP" to { it.temperatures.getOrNull(0)?.takeIf { t -> MetricSanity.isPlausibleTempC(t) } },
        "CONTROLLER_TEMP" to { it.temperatures.getOrNull(1)?.takeIf { t -> MetricSanity.isPlausibleTempC(t) } },
        "BATTERY_TEMP" to { it.temperatures.getOrNull(2)?.takeIf { t -> MetricSanity.isPlausibleTempC(t) } },
        "TIRE_PRESSURE" to { w -> w.tirePressureKpa.takeIf { w.hasTirePressure } },
        "BT_RSSI" to { it.rssiDbm.takeIf { r -> r != 0 }?.toFloat() },
        "WH_PER_KM" to { it.whPerKmRecent.takeIf { v -> !v.isNaN() } },
        "RANGE_ESTIMATE" to { it.rangeKmEstimate.takeIf { v -> !v.isNaN() } },
    )

    /** AlarmWheelPass.metricValue before the registry. */
    private fun oldAlarmMetricValue(metric: String, data: WheelData): Float? {
        return try {
            when (AlarmMetric.valueOf(metric)) {
                AlarmMetric.SPEED -> data.speed.absoluteValue
                AlarmMetric.BATTERY -> data.batteryPercent.toFloat()
                AlarmMetric.BATTERY_ENVELOPE -> data.batteryEnvelope.takeIf { !it.isNaN() }
                AlarmMetric.TEMPERATURE -> data.maxTemperature
                AlarmMetric.PWM -> data.pwm.absoluteValue
                AlarmMetric.VOLTAGE -> data.voltage
                AlarmMetric.CURRENT -> data.current.absoluteValue
                AlarmMetric.TORQUE -> data.torque.absoluteValue
                AlarmMetric.PHASE_CURRENT -> data.phaseCurrent.absoluteValue
                AlarmMetric.WH_CONSUMED -> data.whConsumed
                AlarmMetric.WH_PER_KM -> data.whPerKmRecent.takeIf { !it.isNaN() }
                AlarmMetric.TIRE_PRESSURE -> AlarmLogic.tirePressureForAlarm(data)
                AlarmMetric.MOTOR_TEMP -> data.temperatures.getOrNull(0)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.CONTROLLER_TEMP -> data.temperatures.getOrNull(1)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.BATTERY_TEMP -> data.temperatures.getOrNull(2)
                    ?.takeIf { MetricSanity.isPlausibleTempC(it) }
                AlarmMetric.G_FORCE -> data.gForce
                AlarmMetric.LATERAL_G -> data.accelX.absoluteValue
                AlarmMetric.BT_RSSI -> data.rssiDbm.takeIf { it != 0 }?.toFloat()
                AlarmMetric.RANGE_ESTIMATE -> data.rangeKmEstimate.takeIf { !it.isNaN() }
                AlarmMetric.RADAR_DISTANCE,
                AlarmMetric.RADAR_APPROACH_SPEED,
                AlarmMetric.GPS_SPEED,
                AlarmMetric.GPS_ALTITUDE,
                AlarmMetric.EXTERNAL_GPS_SPEED,
                AlarmMetric.EXTERNAL_GPS_BATTERY -> null
            }
        } catch (_: Exception) { null }
    }

    // --- Equivalence: migrated consumers ------------------------------------

    // assertEquals on boxed Float compares with equals(), so NaN equals NaN and
    // 0f differs from -0f: the check is exact to the bit pattern class.

    @Test fun `metric detail reads what it read before`() = eachFrameAndKey { f, w, key ->
        assertEquals("$f / $key", oldRawCurrentValueFor(key, w), rawCurrentValueFor(key, w))
    }

    @Test fun `service overlay prints what it printed before`() = eachFrameAndKey { f, w, key ->
        assertEquals("$f / $key", oldRawMetricValue(key, w), rawMetricValue(key, w))
    }

    @Test fun `voice extractors are the same keys in the same order`() {
        assertEquals(oldExtractors.keys.toList(), EXTRACTORS.keys.toList())
    }

    @Test fun `voice extractors read what they read before`() {
        for ((f, w) in frames) for ((key, old) in oldExtractors) {
            assertEquals("$f / $key", old(w), EXTRACTORS.getValue(key)(w))
        }
    }

    @Test fun `history extras are the same keys in the same order`() {
        assertEquals(oldExtraHistory.map { it.first }, EXTRA_HISTORY_METRICS.map { it.first })
    }

    @Test fun `history extras sample what they sampled before`() {
        val now = EXTRA_HISTORY_METRICS.toMap()
        for ((f, w) in frames) for ((key, old) in oldExtraHistory) {
            assertEquals("$f / $key", old(w), now.getValue(key)(w))
        }
    }

    @Test fun `alarms read what they read before`() = eachFrameAndKey { f, w, key ->
        assertEquals("$f / $key", oldAlarmMetricValue(key, w), AlarmWheelPass.metricValue(key, w))
    }

    // --- Registry shape -----------------------------------------------------

    @Test fun `keys and aliases are unique across the registry`() {
        val names = MetricRegistry.all.flatMap { listOf(it.key) + it.aliases }
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }

    @Test fun `every catalog metric is a canonical registry key`() {
        val canonical = MetricRegistry.all.map { it.key }.toSet()
        assertEquals(emptyList<String>(), MetricCatalog.keys.filterNot { it in canonical })
    }

    @Test fun `alias lookup lands on the canonical entry`() {
        for (def in MetricRegistry.all) for (alias in def.aliases) {
            assertEquals(alias, def, MetricRegistry.byKey(alias))
        }
    }

    @Test fun `read drops only the declared sentinel`() {
        for ((f, w) in frames) for (def in MetricRegistry.all) {
            val raw = def.readRaw(w)
            val read = def.read(w)
            if (raw == null) {
                assertEquals("$f / ${def.key}", null, read)
            } else {
                val expected = if (def.absence.isAbsent(raw, w)) null else raw
                assertEquals("$f / ${def.key}", expected, read)
            }
        }
    }

    // --- Drift guard: every consumer key resolves ---------------------------

    /**
     * Consumer keys that resolve by name to an entry whose value they do NOT
     * read. Preserved on purpose; reconcile them deliberately, then empty this.
     */
    private val knownMeaningConflicts = mapOf(
        // The Overlay Studio's POWER reads motorPower.
        "StudioMetric.POWER" to "MOTOR_POWER",
        // The widget's POWER prints |voltage x current|, which no field holds.
        "WidgetMetricType.POWER" to "|VOLTAGE * CURRENT|",
    )

    /** Consumer keys that are not in the registry at all, and why. Empty today. */
    private val knownUnregistered = emptySet<String>()

    @Test fun `every consumer key resolves in the registry`() {
        val consumerKeys =
            MetricCatalog.keys.map { "MetricCatalog.$it" to it } +
                AlarmMetric.entries.map { "AlarmMetric.${it.name}" to it.name } +
                StudioMetric.entries.map { "StudioMetric.${it.name}" to it.key } +
                WidgetMetricType.entries.map { "WidgetMetricType.${it.name}" to it.key }
        val missing = consumerKeys
            .filter { (label, key) -> MetricRegistry.byKey(key) == null && label !in knownUnregistered }
            .map { it.first }
        assertEquals(emptyList<String>(), missing)
        for (label in knownMeaningConflicts.keys) {
            assertTrue("$label is not a consumer key any more", consumerKeys.any { it.first == label })
        }
    }

    /**
     * The studio's gauges draw the registry value with their own adjustment:
     * magnitude for speed / PWM, 0 for a sentinel. Pinned so a registry entry
     * and its studio twin cannot drift to different fields.
     */
    @Test fun `studio extracts the registry field with its known adjustment`() {
        fun expected(m: StudioMetric, w: WheelData): Float {
            val key = if (m == StudioMetric.POWER) "MOTOR_POWER" else m.key
            val raw = MetricRegistry.readRaw(key, w)
            assertNotNull("${m.name} has no registry read", raw)
            val v = raw!!
            return when (m) {
                StudioMetric.SPEED, StudioMetric.PWM -> v.absoluteValue
                StudioMetric.BATTERY_ENVELOPE, StudioMetric.WH_PER_KM,
                StudioMetric.RANGE_ESTIMATE, StudioMetric.GPS_ALTITUDE -> if (v.isNaN()) 0f else v
                StudioMetric.TRIP_METER, StudioMetric.EXTERNAL_GPS_SPEED,
                StudioMetric.GPS_SPEED -> v.coerceAtLeast(0f)
                else -> v
            }
        }
        for ((f, w) in frames) for (m in StudioMetric.entries.filterNot { it.textOnly }) {
            assertEquals("$f / ${m.name}", expected(m, w), m.extract(w))
        }
    }
}
