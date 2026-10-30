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
 * configuration change, screen is locked landscape, there's no rotation, no
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

    private var currentState: Screen by mutableStateOf(Screen.Dashboard)

    var current: Screen
        get() = currentState
        private set(value) {
            // Leaving the screen ends the hand-off; the next camera screen
            // opens its camera as usual.
            if (value != currentState) {
                cameraHandedOff = false
                cameraTakeBack = false
            }
            currentState = value
        }

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

    /** Which camera the camera screens show, as a position in the HUD's
     *  camera list (back cameras first, see RearCameraPreview). It wraps at
     *  the number of cameras the device has. A MotoEye can report its rear
     *  camera as the front one, so the rider picks it with UP. */
    var cameraIndex: Int by mutableStateOf(0)
        private set

    /** How many cameras the preview found; UP wraps here so every camera
     *  keeps one index, and with it one rotation. Unknown until the first
     *  preview binds. */
    private var cameraCount: Int = MAX_CAMERAS

    /** Each camera's clockwise turn, a multiple of 90. A front and a rear
     *  sensor sit differently, so each keeps its own. */
    private var cameraRotations: Map<Int, Int> by mutableStateOf(emptyMap())

    /** Clockwise turn of the camera on screen. 270 is what a MotoEye E6
     *  needs; other models mount the sensor another way round, so the rider
     *  turns it with DOWN. */
    val cameraRotation: Int
        get() = cameraRotations[cameraIndex] ?: DEFAULT_CAMERA_ROTATION

    /** Called after UP or DOWN changes the camera so the Activity can keep
     *  the choice for the next start: the camera now shown and its turn. */
    var onCameraChanged: ((index: Int, rotation: Int) -> Unit)? = null

    /** The camera screens let go of the camera for the MotoEye. Only one app
     *  can open a camera at a time, and the MotoEye recorder opens every
     *  camera: while a camera screen was up it failed on each. It comes
     *  back by itself once the MotoEye lets go (see [onOtherAppCamera]), or
     *  with UP, DOWN or another screen. */
    var cameraHandedOff: Boolean by mutableStateOf(false)
        private set

    /** The rider asked for the picture with UP or DOWN, so the preview
     *  opens the camera even while another app holds it. */
    var cameraTakeBack: Boolean by mutableStateOf(false)
        private set

    /** The MotoEye camera button has reached the HUD, so its next press
     *  frees the camera again and the preview may take it back meanwhile.
     *  Without it the preview stays off: taking the camera back would block
     *  the recorder's next start. */
    private var cameraKeyHeard by mutableStateOf(false)

    /** Another app opened a camera since this hand-off. */
    private var takenSinceHandOff by mutableStateOf(false)

    /** ...and has closed it again, so the preview may come back. */
    private var freeAgain by mutableStateOf(false)

    /** The HUD is in front. The MotoEye rear view, or a camera app, may let
     *  go of the camera for a moment while it is still on screen. */
    private var hudFocused by mutableStateOf(true)

    /** What freed the camera last, and the last remote key the HUD saw. */
    private var handOffReason by mutableStateOf("")
    private var lastKey by mutableStateOf(-1)

    /** The camera button presses since the HUD started, each short (rear
     *  view) or long (recorder start or stop). Josh: both reach the HUD as
     *  the same key, so only how long it was held tells them apart. */
    private val cameraPresses = CameraPressLog()
    private var cameraPressText by mutableStateOf("none")

    /** The Activity saw a key press, ours or not. [down] false is its
     *  release; [atMs] is the event's uptime. */
    fun noteKey(keyCode: Int, down: Boolean = true, atMs: Long = 0L) {
        if (down) lastKey = keyCode
        if (HudKeys.handsOffCamera(keyCode)) {
            cameraPresses.note(down, atMs)
            cameraPressText = cameraPresses.summary()
        }
    }

    /** One line on the handed-off screen saying what keeps the picture off,
     *  so a tester's photo shows which step the MotoEye skips. */
    val cameraDiag: String
        get() = "freed by $handOffReason, last key $lastKey, presses $cameraPressText, " +
            "button ${if (cameraKeyHeard) "seen" else "not seen"}, " +
            "other app ${if (freeAgain) "done" else if (takenSinceHandOff) "has camera" else "not seen"}, " +
            "HUD ${if (hudFocused) "in front" else "behind"}"

    /** Something else wants the camera: the MotoEye camera button (rear
     *  view or recorder), its overlay covering the HUD, or another app that
     *  already has the camera open. A camera screen lets go and stays off,
     *  so the preview does not grab the camera back when the recorder
     *  stops and block its next start. [byKey] is the camera button. */
    fun handOffCamera(byKey: Boolean = false, reason: String = if (byKey) "button" else "camera in use") {
        if (byKey) cameraKeyHeard = true
        if (current != Screen.Camera && current != Screen.CustomCam) return
        cameraTakeBack = false
        if (!cameraHandedOff) {
            takenSinceHandOff = false
            freeAgain = false
            handOffReason = reason
        }
        cameraHandedOff = true
    }

    /** The HUD window gained or lost focus. */
    fun onHudFocus(focused: Boolean) {
        hudFocused = focused
        resumeIfFree()
    }

    /** While handed off: whether other apps hold a camera, reported once
     *  it has settled. When the MotoEye recorder or rear view lets go, the
     *  preview comes back (Josh: restarting it by hand after each clip). */
    fun onOtherAppCamera(inUse: Boolean) {
        if (!cameraHandedOff) return
        if (inUse) takenSinceHandOff = true
        freeAgain = !inUse && takenSinceHandOff
        resumeIfFree()
    }

    private fun resumeIfFree() {
        if (cameraHandedOff && freeAgain && cameraKeyHeard && hudFocused) cameraHandedOff = false
    }

    /** Put back the camera choice saved on an earlier start. */
    fun restoreCamera(index: Int, rotations: Map<Int, Int>) {
        cameraIndex = index.coerceAtLeast(0)
        cameraRotations = rotations
            .filterKeys { it >= 0 }
            .mapValues { normalizeRotation(it.value) }
    }

    /** The preview reports how many cameras the device has. A saved index
     *  past the end (a different HUD, a camera gone) falls back to the
     *  first camera. */
    fun onCamerasFound(count: Int) {
        if (count <= 0) return
        cameraCount = count
        if (cameraIndex >= count) cameraIndex = 0
    }

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
            Screen.Camera, Screen.CustomCam -> if (cameraHandedOff) {
                cameraHandedOff = false
                cameraTakeBack = true
            } else {
                cameraIndex = (cameraIndex + 1) % cameraCount
                onCameraChanged?.invoke(cameraIndex, cameraRotation)
            }
            else -> Unit
        }
    }

    fun downAction() {
        when (current) {
            Screen.Dashboard -> dashboardGpsView = true
            Screen.Map, Screen.MapNav -> mapZoom = (mapZoom - 1f).coerceAtLeast(MAP_ZOOM_MIN)
            Screen.Camera, Screen.CustomCam -> if (cameraHandedOff) {
                cameraHandedOff = false
                cameraTakeBack = true
            } else {
                cameraRotations = cameraRotations +
                    (cameraIndex to normalizeRotation(cameraRotation + 90))
                onCameraChanged?.invoke(cameraIndex, cameraRotation)
            }
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
            Screen.Camera, Screen.CustomCam ->
                if (cameraHandedOff) "Show camera" to "Show camera" else "Next camera" to "Rotate"
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
        const val DEFAULT_CAMERA_ROTATION: Int = 270
        /** More cameras than any HUD has; the wrap until the preview has
         *  counted the real ones. */
        const val MAX_CAMERAS: Int = 8

        /** Any angle to 0, 90, 180 or 270; anything else falls back to the
         *  default so a corrupt saved value cannot tilt the picture. */
        fun normalizeRotation(deg: Int): Int {
            val r = ((deg % 360) + 360) % 360
            return if (r % 90 == 0) r else DEFAULT_CAMERA_ROTATION
        }
    }
}
