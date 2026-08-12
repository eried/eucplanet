package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.util.Units
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
class EngoHudRenderer @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val navigationEngine: NavigationEngine,
    private val settingsRepository: SettingsRepository,
    private val adapter: EngoAdapter,
) {
    private companion object {
        const val TICK_MS = 300L // ~3 Hz; only sends on change
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var last: EngoSnapshot? = null

    /** Begin driving the glasses. [autoConnect] triggers a BLE scan/connect. */
    fun start(autoConnect: Boolean) {
        if (job != null) return
        if (autoConnect && adapter.state.value == EngoAdapter.State.DISCONNECTED) adapter.connect()
        last = null
        job = scope.launch {
            while (isActive) {
                if (adapter.state.value == EngoAdapter.State.CONNECTED) {
                    val snap = buildSnapshot(settingsRepository.get())
                    if (snap != last) {
                        adapter.send(
                            EngoLayout.render(snap, EngoCaps(colorRYG = adapter.colorCapable.value)),
                        )
                        last = snap
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
        return EngoSnapshot(
            connected = wheelConnected,
            speed = Units.speed(d.speed, speedUnit).roundToInt(),
            speedUnit = asciiSpeedUnit(speedUnit),
            batteryPct = d.batteryPercent,
            pwmPct = d.pwm.roundToInt().coerceIn(0, 100),
            temp = Units.temperature(d.maxTemperature, tempUnit).roundToInt(),
            tempUnit = tempUnit, // "C" / "F" / "K" are already ASCII
            navActive = nav.active,
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
