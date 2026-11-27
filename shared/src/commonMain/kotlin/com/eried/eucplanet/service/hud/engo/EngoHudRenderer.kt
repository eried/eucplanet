package com.eried.eucplanet.service.hud.engo

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.data.model.MetricRegistry
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.platform.System
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the ENGO glasses: on a slow tick it builds the ride page from live
 * telemetry, the history buffers and navigation ([EngoPageBuilder]), lays it
 * out with the pure [EngoLayout], and sends only the widgets that changed, so a
 * stationary rider costs almost no BLE traffic and a new speed a few hundred bytes.
 *
 * Started / stopped from the service when the rider enables the ENGO HUD. Reads
 * the same repositories the dashboard uses; the render + encode are pure and
 * already unit-tested, so this class is just the wiring.
 */
class EngoHudRenderer(
    private val wheelRepository: WheelRepository,
    private val navigationEngine: NavigationEngine,
    private val settingsRepository: SettingsRepository,
    private val adapter: EngoGlasses,
) {
    private companion object {
        const val TICK_MS = 300L // ~3 Hz; only sends on change
    }

    /** When the glasses were last searched for, on the uptime clock. */
    @Volatile private var lastSearchAtMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    /** What the glasses show now; null after a (re)connect, so the next frame is whole. */
    @Volatile private var last: List<EngoLayout.EngoWidget>? = null
    @Volatile private var lastColor = false

    /** Begin driving the glasses at [address], the pair the rider paired. */
    fun start(address: String) {
        if (job != null) return
        last = null
        lastSearchAtMs = 0L
        job = scope.launch {
            while (isActive) {
                // Glasses switched off, out of range or Bluetooth off: keep
                // looking, so they come back on their own.
                val now = System.nanoTime() / 1_000_000
                if (EngoLink.searchDue(
                        true,
                        adapter.state.value == EngoGlasses.State.DISCONNECTED,
                        if (lastSearchAtMs == 0L) Long.MAX_VALUE else now - lastSearchAtMs,
                    )
                ) {
                    lastSearchAtMs = now
                    last = null
                    adapter.connect(address)
                }
                val connected = adapter.state.value == EngoGlasses.State.CONNECTED
                if (connected && !EngoLink.roomFor(adapter.backlog())) {
                    // The glasses are behind. `last` stays as it is, so the
                    // next screen is worked out against the last one queued.
                    adapter.send(emptyList())
                } else if (connected) {
                    // The warm copy: this loop runs a few times a second.
                    val settings = settingsRepository.currentOrLoad()
                    val page = buildPage(settings)
                    // The glasses say whether their display is colour; a grey
                    // pair never gets the colour commands it cannot draw.
                    val color = adapter.colorCapable.value
                    val caps = EngoCaps(colorRYG = color)
                    val widgets = EngoLayout.widgets(page, caps)
                    val cmds = EngoLayout.delta(if (color == lastColor) last else null, widgets, caps)
                    // Sent even when nothing changed: an empty list only
                    // gives a refused write another go.
                    adapter.send(cmds)
                    last = widgets
                    lastColor = color
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

    private fun buildPage(settings: AppSettings): EngoPage {
        val d = wheelRepository.wheelData.value
        val wheelConnected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
        val nav = navigationEngine.navState.value
        // The dashboard's own history, cut to its stats length: the statistics
        // and the graphs read it, so the glasses keep no history of their own.
        val h = wheelRepository.fullHistory.value
        return EngoPageBuilder.build(
            engo = settings.engoHud,
            units = EngoMetrics.units(settings),
            orangePct = settings.gaugeOrangeThresholdPct,
            redPct = settings.gaugeRedThresholdPct,
            connected = wheelConnected,
            current = { key -> MetricRegistry.read(key, d) },
            history = { key -> historyOf(h, key) },
            nav = if (nav.active) EngoNav(mapArrow(nav.arrow), nav.distanceText.uppercase()) else null,
            nowMs = System.currentTimeMillis(),
            dashboardWindowSec = settings.dashboardRollingWindowSeconds,
        )
    }

    private fun historyOf(h: com.eried.eucplanet.data.repository.FullMetricHistory, key: String): List<MetricSample> =
        when (key) {
            "BATTERY" -> h.battery
            "TEMPERATURE" -> h.temperature
            "VOLTAGE" -> h.voltage
            "CURRENT" -> h.current
            "LOAD" -> h.load
            "SPEED" -> h.speed
            else -> h.extras[key].orEmpty()
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
