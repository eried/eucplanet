package com.eried.eucplanet.hud.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eried.eucplanet.hud.net.HudServer
import com.eried.eucplanet.hud.protocol.HudCommand

/**
 * UI navigation state for the HUD's four-screen carousel.
 *
 * Held by the Activity (not a ViewModel) because the HUD app never undergoes
 * configuration change — screen is locked landscape, there's no rotation, no
 * dark/light mode toggle, no font-scale toggle from the OS. Keeping it here
 * keeps the screen carousel deterministic between key events.
 *
 * Per-screen "up/down" semantics live on the screen objects themselves
 * (see `Screen.onUp/onDown`) so adding a fifth screen later is one entry in
 * the [screens] list, not a switch statement here.
 */
class HudUiController {

    /** Stable identity of the screens, in display order. */
    enum class Screen { Dashboard, Camera, Telemetry, Map, Nav }

    private val screens: List<Screen> = listOf(
        Screen.Dashboard, Screen.Camera, Screen.Telemetry, Screen.Map, Screen.Nav
    )

    var current: Screen by mutableStateOf(Screen.Dashboard)
        private set

    /** Sub-mode of the dashboard: false = EUC view, true = GPS view. Mirrors
     *  the up/down toggle the competitor's main screen uses. */
    var dashboardGpsView: Boolean by mutableStateOf(false)
        private set

    /** Map zoom level, clamped to [MAP_ZOOM_MIN]..[MAP_ZOOM_MAX]. */
    var mapZoom: Float by mutableStateOf(15f)
        private set

    /** Most recent HUD connection status, surfaced as a banner. */
    var status: HudServer.Status by mutableStateOf(HudServer.Status.LISTENING)
        private set

    /** True once the rider has pressed any button to dismiss the
     *  disconnected modal. The modal shrinks to a corner badge so the
     *  rider can keep using the screens (camera, map) without the IP
     *  splash covering everything. Reset back to false on every fresh
     *  transition into LISTENING so a reconnect-disconnect cycle restores
     *  the full modal. */
    var disconnectedModalDismissed: Boolean by mutableStateOf(false)
        private set

    fun updateStatus(s: HudServer.Status) {
        // Reset the dismiss flag every time we re-enter the disconnected
        // state -- the rider should see the full IP splash again on a
        // new disconnect, not a stale "I dismissed it" carryover.
        if (s == HudServer.Status.LISTENING && status != HudServer.Status.LISTENING) {
            disconnectedModalDismissed = false
        }
        status = s
    }

    /** Called by the Activity on any DPAD key while the disconnected modal
     *  is on screen. Collapses the modal to a small corner badge. */
    fun dismissDisconnectedModal() {
        if (status != HudServer.Status.CONNECTED) {
            disconnectedModalDismissed = true
        }
    }

    fun nextScreen() {
        val idx = screens.indexOf(current)
        current = screens[(idx + 1) % screens.size]
    }

    fun previousScreen() {
        val idx = screens.indexOf(current)
        current = screens[(idx - 1 + screens.size) % screens.size]
    }

    fun upAction() {
        when (current) {
            Screen.Dashboard -> dashboardGpsView = false
            Screen.Map -> mapZoom = (mapZoom + 1f).coerceAtMost(MAP_ZOOM_MAX)
            else -> Unit
        }
    }

    fun downAction() {
        when (current) {
            Screen.Dashboard -> dashboardGpsView = true
            Screen.Map -> mapZoom = (mapZoom - 1f).coerceAtLeast(MAP_ZOOM_MIN)
            else -> Unit
        }
    }

    /** Optional sink wired up by HudActivity so the controller can forward
     *  commands back to the phone over the SSE peer connection. */
    var onCommand: ((HudCommand) -> Unit)? = null

    /** Hook for the OK button. Behaviour is screen-dependent:
     *  - Dashboard: toggle the wheel headlight on the phone
     *  - Nav (active route): stop navigation
     *  - everything else: no-op (reserved for re-pair retry on a future
     *    pairing screen) */
    fun centerAction() {
        val sink = onCommand ?: return
        when (current) {
            Screen.Dashboard -> sink(HudCommand.ToggleLight)
            Screen.Nav -> sink(HudCommand.StopNavigation)
            else -> Unit
        }
    }

    companion object {
        const val MAP_ZOOM_MIN: Float = 3f
        const val MAP_ZOOM_MAX: Float = 19f
    }
}
