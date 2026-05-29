package com.eried.eucplanet.hud.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eried.eucplanet.hud.net.HudClient
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.HudDiscovery

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
    enum class Screen { Dashboard, Camera, Map, Nav }

    private val screens: List<Screen> = listOf(
        Screen.Dashboard, Screen.Camera, Screen.Map, Screen.Nav
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
    var status: HudClient.Status by mutableStateOf(HudClient.Status.SEARCHING)
        private set

    fun updateStatus(s: HudClient.Status) { status = s }

    /** Manual-peer editor state. Non-null when the overlay is open; the
     *  five Ints are (oct1, oct2, oct3, oct4, port). The selected field
     *  index (0..4) tracks DPAD focus. Held here so the Activity can route
     *  key events to the editor when it's up. */
    var editor: ManualPeerEditState? by mutableStateOf(null)
        private set

    /** Open the editor seeded from the last saved peer (or 192.168.43.1 :
     *  the default Android hotspot router IP, which is what the phone's
     *  IP almost always is on the rider's own softAP). */
    fun openManualPeerEditor(currentPeer: String?) {
        editor = ManualPeerEditState.fromPeerString(currentPeer)
            ?: ManualPeerEditState(192, 168, 43, 1, HudDiscovery.DEFAULT_PORT, focus = 3)
    }

    fun closeManualPeerEditor() { editor = null }

    fun editorLeft()  { editor = editor?.copy(focus = ((editor?.focus ?: 0) - 1).coerceAtLeast(0)) }
    fun editorRight() { editor = editor?.copy(focus = ((editor?.focus ?: 0) + 1).coerceAtMost(4)) }
    fun editorUp()    { editor = editor?.bump(+1) }
    fun editorDown()  { editor = editor?.bump(-1) }

    /** Return the host:port string the editor currently holds, or null if
     *  the editor has been cleared to 0.0.0.0 (treated as "no manual peer,
     *  fall back to mDNS"). */
    fun editorPeerString(): String? = editor?.toPeerString()

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

/**
 * Draft state of the manual peer editor. Five fields: four IPv4 octets and a
 * port. [focus] is the currently selected field (0..4). All values are kept
 * clamped to their valid ranges so the UI never has to defensively re-clamp.
 *
 * Held as a value class (data class with immutable copy semantics) so the
 * Compose snapshot system sees a clean before/after on every edit -- avoids
 * the recompose-skip trap of mutating a single Int and relying on equality on
 * the same object.
 */
data class ManualPeerEditState(
    val oct1: Int,
    val oct2: Int,
    val oct3: Int,
    val oct4: Int,
    val port: Int,
    val focus: Int = 0
) {
    /** Apply a +/-1 delta to the focused field, clamped to valid ranges. */
    fun bump(delta: Int): ManualPeerEditState = when (focus) {
        0 -> copy(oct1 = (oct1 + delta).coerceIn(0, 255))
        1 -> copy(oct2 = (oct2 + delta).coerceIn(0, 255))
        2 -> copy(oct3 = (oct3 + delta).coerceIn(0, 255))
        3 -> copy(oct4 = (oct4 + delta).coerceIn(0, 255))
        4 -> copy(port = (port + delta).coerceIn(1, 65535))
        else -> this
    }

    /** Render as "a.b.c.d:port", or null if all octets are zero (treated as
     *  "no manual peer set"). */
    fun toPeerString(): String? {
        if (oct1 == 0 && oct2 == 0 && oct3 == 0 && oct4 == 0) return null
        return "$oct1.$oct2.$oct3.$oct4:$port"
    }

    companion object {
        /** Parse "a.b.c.d:port" back into editor state, or null on malformed
         *  input. Used to seed the editor with whatever was previously saved
         *  so the rider can adjust one octet instead of re-typing. */
        fun fromPeerString(s: String?): ManualPeerEditState? {
            if (s.isNullOrBlank()) return null
            val (hostPart, portPart) = s.split(":", limit = 2).let {
                if (it.size != 2) return null
                it[0] to it[1]
            }
            val octs = hostPart.split(".").mapNotNull { it.toIntOrNull() }
            if (octs.size != 4 || octs.any { it !in 0..255 }) return null
            val p = portPart.toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            return ManualPeerEditState(octs[0], octs[1], octs[2], octs[3], p, focus = 3)
        }
    }
}
