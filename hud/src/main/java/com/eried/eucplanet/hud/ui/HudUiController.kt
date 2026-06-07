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

    /** Stable identity of the screens. The first seven are the
     *  "default" carousel that ships enabled on every HUD; the rest
     *  are opt-in via the phone-side Personalize list. */
    enum class Screen {
        // Default-on
        Dashboard, Camera, Telemetry, Custom, CustomCam, MapNav,
        // Opt-in (default OFF, rider enables on the phone)
        Map, Nav, Power, TripStats, Compass, Safety, BigClock
    }

    /** Default order, used when the rider hasn't customised the carousel
     *  on the phone side. The new combined MapNav screen replaces the
     *  standalone Map + Nav in the default set — riders who prefer the
     *  dedicated full-screen turn arrow can opt them back in via the
     *  phone Personalize list. */
    private val defaultScreens: List<Screen> = listOf(
        Screen.Dashboard, Screen.Camera, Screen.Telemetry,
        Screen.Custom, Screen.CustomCam, Screen.MapNav
    )

    /** Active carousel, kept observable so when [applyEnabledScreens] swaps
     *  it from a wire frame the N/M counter on the toast updates too.
     *  Starts EMPTY: until the phone ships a real list, the HUD renders a
     *  "waiting for phone data" splash rather than the compiled default 7.
     *  This keeps the rider from briefly seeing screens they may have
     *  disabled on the phone every time the HUD reboots. */
    var screens: List<Screen> by mutableStateOf(emptyList())
        private set

    /** True once we've applied at least one wire frame's enabled list.
     *  Drives the "waiting for phone data" splash in HudApp. */
    var hasReceivedCarousel: Boolean by mutableStateOf(false)
        private set

    /** Update the visible screen carousel from a list of stable string
     *  ids (matching enum names). The phone always ships a non-empty list
     *  (the default 7 if the rider hasn't customised), so an empty list
     *  here means "no wire frame yet" -- we keep [screens] empty and
     *  [hasReceivedCarousel] false so the splash stays up.
     *
     *  Called by [HudActivity] from the state-flow collector so the
     *  carousel updates within ~200 ms of the rider toggling a checkbox
     *  on the phone -- no power cycle needed. */
    fun applyEnabledScreens(ids: List<String>) {
        val parsed = ids.mapNotNull { id ->
            runCatching { Screen.valueOf(id) }.getOrNull()
        }
        if (parsed.isEmpty()) return
        hasReceivedCarousel = true
        if (parsed == screens) return
        screens = parsed
        if (current !in parsed) current = parsed.first()
    }

    var current: Screen by mutableStateOf(Screen.Dashboard)
        private set

    /** Monotonic counter bumped by the Activity on every d-pad press so the
     *  button-action guide overlay can re-show itself even when the screen
     *  hasn't changed. The overlay keys its show/auto-hide effect on this in
     *  addition to [current]: a press on a screen the rider is already on
     *  flashes the guide so they can recall their bound actions without
     *  switching screens first. */
    var guidePulse: Int by mutableStateOf(0)
        private set

    /** Called by the Activity on any d-pad key-down to flash the button-action
     *  guide overlay (see [guidePulse]). */
    fun pulseGuide() {
        guidePulse++
    }

    /** Sub-mode of the dashboard: false = EUC view, true = GPS view. Mirrors
     *  the up/down toggle the competitor's main screen uses. */
    var dashboardGpsView: Boolean by mutableStateOf(false)
        private set

    /** Map zoom level, clamped to [MAP_ZOOM_MIN]..[MAP_ZOOM_MAX]. Default
     *  17 reads as "neighborhood block" detail -- the rider can see the
     *  next two or three intersections at a glance, which is what a HUD
     *  map is for. UP/DOWN on the remote steps in/out from there. */
    var mapZoom: Float by mutableStateOf(17f)
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
        // Clamp at the end -- no wrap-around. The N/M counter on the
        // screen toast lets the rider see they've hit the boundary.
        val idx = screens.indexOf(current)
        if (idx in 0 until screens.size - 1) current = screens[idx + 1]
    }

    fun previousScreen() {
        val idx = screens.indexOf(current)
        if (idx > 0) current = screens[idx - 1]
    }

    /** 1-based screen index, useful for "3/6" overlays. */
    fun currentIndex1Based(): Int = screens.indexOf(current) + 1
    /** Total screen count. */
    fun totalScreens(): Int = screens.size

    fun upAction() {
        when (current) {
            Screen.Dashboard -> dashboardGpsView = false
            Screen.Map, Screen.MapNav -> mapZoom = (mapZoom + 1f).coerceAtMost(MAP_ZOOM_MAX)
            else -> Unit
        }
    }

    fun downAction() {
        when (current) {
            Screen.Dashboard -> dashboardGpsView = true
            Screen.Map, Screen.MapNav -> mapZoom = (mapZoom - 1f).coerceAtLeast(MAP_ZOOM_MIN)
            else -> Unit
        }
    }

    /** Short-press behaviour labels for the button-action guide overlay
     *  (line A). Each describes what a SHORT d-pad press does on the CURRENT
     *  screen, derived from the same logic as [upAction] / [downAction] /
     *  [previousScreen] / [nextScreen] so the two never drift. A null entry
     *  means the direction has no screen-specific short action here (the
     *  overlay then omits line A for that arm).
     *
     *  LEFT/RIGHT only carry a label when there's somewhere to go: null on the
     *  first screen (no prev) and on the last (no next); a single screen has
     *  neither. The overlay hides an arm with no short line and no long action. */
    class ShortActions(
        val up: String?,
        val down: String?,
        val left: String?,
        val right: String?
    )

    fun shortActions(): ShortActions {
        val idx = screens.indexOf(current)
        // Prev only when not on the first screen, Next only when not on the last
        // -- the carousel clamps (no wrap); a single screen has neither.
        val left = if (idx > 0) "Prev screen" else null
        val right = if (idx in 0 until screens.size - 1) "Next screen" else null
        val (up, down) = when (current) {
            Screen.Map, Screen.MapNav -> "Zoom in" to "Zoom out"
            Screen.Dashboard -> "Speed view" to "GPS view"
            else -> null to null
        }
        return ShortActions(up = up, down = down, left = left, right = right)
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
