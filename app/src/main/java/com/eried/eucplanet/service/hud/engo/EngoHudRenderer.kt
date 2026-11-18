package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.util.Units
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the ENGO glasses: on a slow tick it maps live telemetry + navigation
 * into an [EngoSnapshot], renders it with the pure [EngoLayout], and pushes the
 * frames through [EngoAdapter] - but only when the snapshot actually changed, so
 * a stationary rider costs almost no BLE traffic.
 *
 * Started / stopped from the service when the rider enables the ENGO HUD. Reads
 * the same repositories the dashboard uses; the render + encode are pure and
 * already unit-tested, so this class is just the wiring.
 */
class EngoHudRenderer(
    private val wheelRepository: WheelRepository,
    private val navigationEngine: NavigationEngine,
    private val settingsRepository: SettingsRepository,
    private val adapter: EngoAdapter,
) {
    private companion object {
        const val TICK_MS = 300L // ~3 Hz; only sends on change
    }

    /** When the glasses were last searched for, on the uptime clock. */
    @Volatile private var lastSearchAtMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var last: EngoSnapshot? = null
    @Volatile private var lastColor = false

    /** Begin driving the glasses. [autoConnect] triggers a BLE scan/connect. */
    fun start(autoConnect: Boolean) {
        if (job != null) return
        last = null
        lastSearchAtMs = 0L
        job = scope.launch {
            while (isActive) {
                // Glasses switched off, out of range or Bluetooth off: keep
                // looking, so they come back on their own.
                val now = android.os.SystemClock.elapsedRealtime()
                if (EngoLink.searchDue(
                        autoConnect,
                        adapter.state.value == EngoAdapter.State.DISCONNECTED,
                        if (lastSearchAtMs == 0L) Long.MAX_VALUE else now - lastSearchAtMs,
                    )
                ) {
                    lastSearchAtMs = now
                    last = null
                    adapter.connect()
                }
                if (adapter.state.value == EngoAdapter.State.CONNECTED) {
                    val settings = settingsRepository.get()
                    val snap = buildSnapshot(settings)
                    // The rider says which model it is; the glasses do not
                    // tell us reliably yet.
                    val color = settings.engoHud.color || adapter.colorCapable.value
                    if (snap != last || color != lastColor) {
                        adapter.send(EngoLayout.render(snap, EngoCaps(colorRYG = color)))
                        last = snap
                        lastColor = color
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        last = null
        adapter.disconnect()
    }

    val connectionState get() = adapter.state

    private fun buildSnapshot(settings: AppSettings): EngoSnapshot {
        val d = wheelRepository.wheelData.value
        val wheelConnected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
        val nav = navigationEngine.navState.value
        val speedUnit = Units.effectiveSpeedUnit(settings)
        val tempUnit = Units.effectiveTempUnit(settings)
        val distUnit = Units.effectiveDistanceUnit(settings)
        val engo = settings.engoHud
        fun cell(key: String, fallback: String) = EngoMetrics.cell(
            EngoMetrics.known(key, fallback),
            batteryPct = d.batteryPercent,
            temp = Units.temperature(d.maxTemperature, tempUnit).roundToInt(),
            tempUnit = tempUnit, // "C" / "F" / "K" are already ASCII
            voltage = d.voltage,
            current = d.current,
            trip = Units.distance(d.tripDistance, distUnit),
            distanceUnit = Units.distanceUnit(distUnit),
        )
        return EngoSnapshot(
            connected = wheelConnected,
            speed = Units.speed(d.speed, speedUnit).roundToInt(),
            speedUnit = asciiSpeedUnit(speedUnit),
            pwmPct = d.pwm.roundToInt().coerceIn(0, 100),
            left = cell(engo.metricLeft, EngoMetrics.BATTERY),
            right = cell(engo.metricRight, EngoMetrics.TEMPERATURE),
            // With turns switched off the ride page stays through a route.
            navActive = nav.active && engo.navTakeover,
            navDistanceText = nav.distanceText,
            navManeuver = mapArrow(nav.arrow),
            navStreet = nav.nextStreet,
        )
    }

    /** ASCII speed-unit label (the glasses render ASCII only). */
    private fun asciiSpeedUnit(unit: String): String = when (unit) {
        "mph" -> "mph"
        "ms" -> "m/s"
        "kn" -> "kn"
        else -> "km/h"
    }

    private fun mapArrow(a: ArrowDir): EngoManeuver = when (a) {
        ArrowDir.LEFT, ArrowDir.SHARP_LEFT -> EngoManeuver.LEFT
        ArrowDir.SLIGHT_LEFT -> EngoManeuver.SLIGHT_LEFT
        ArrowDir.RIGHT, ArrowDir.SHARP_RIGHT -> EngoManeuver.RIGHT
        ArrowDir.SLIGHT_RIGHT -> EngoManeuver.SLIGHT_RIGHT
        ArrowDir.REVERSE -> EngoManeuver.UTURN
        ArrowDir.STRAIGHT -> EngoManeuver.STRAIGHT
    }
}
