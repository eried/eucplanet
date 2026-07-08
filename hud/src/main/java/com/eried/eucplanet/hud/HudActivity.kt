package com.eried.eucplanet.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.hud.net.HudServer
import com.eried.eucplanet.hud.net.HudTileCache
import com.eried.eucplanet.hud.ui.HudApp
import com.eried.eucplanet.hud.ui.HudSessionState
import com.eried.eucplanet.hud.ui.HudUiController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single-Activity host for the HUD companion app.
 *
 * The HUD is a kiosk-style device: app is always foreground, no system bars,
 * navigation is exclusively via the IR remote (LEFT/RIGHT/UP/DOWN/CENTER/ESC).
 * We intercept key events at the Activity level so even Compose-internal focus
 * handling doesn't swallow them — the four screens are not "focusable" widgets
 * in the Compose sense, they're a custom carousel driven by [HudUiController].
 */
class HudActivity : ComponentActivity() {

    private lateinit var server: HudServer
    private lateinit var controller: HudUiController
    // DPAD gesture state machine for the four directions. The remote reports a
    // HOLD not as one sustained press but as a stream of ~30 ms down/up PAIRS
    // (each UP followed ~1 ms later by the next DOWN). A TAP is one isolated
    // pair (~150 ms held). A DOUBLE-TAP is two taps with a human "lift" gap
    // (~100-250 ms) between them.
    //
    //   single tap   -> the screen action (carousel / zoom / view)
    //   double tap    -> the configured HudCommand.Action (NO screen switch)
    //   hold session  -> nothing (left for the MotoEye device)
    //
    // We use ONE shared handler and per-keyCode maps. pressStart records the
    // uptime a session began; releasePending debounces an UP so a follow-up
    // DOWN inside PRESS_DEBOUNCE_MS keeps the session alive (hold pairs / fast
    // taps); singleTapPending holds a deferred screen action while we wait
    // DOUBLE_TAP_MS for a possible 2nd tap; consumed marks a session whose
    // double-tap already fired so its press-end is a no-op.
    private val dpadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pressStart = HashMap<Int, Long>()
    private val releasePending = HashMap<Int, Runnable>()
    private val singleTapPending = HashMap<Int, Runnable>()
    private val consumed = java.util.HashSet<Int>()
    // One shared tile cache for the lifetime of the activity. Both the
    // Map screen and the Custom overlay's MAP element read from this, so
    // tiles fetched on either side stay warm when the rider navigates
    // away and back. Without this lift, each screen used to allocate a
    // fresh empty HudTileCache on entry -- the rider saw the checker-
    // board placeholder every time they returned to a map view.
    private val tileCache = HudTileCache()
    // Activity-scoped session accumulators (Power sparkline buffer, trip
    // stats, safety-screen sag baseline). Survive screen switches so the
    // buffers keep growing whether or not the screen that reads them is
    // currently composed; reset only on HUD process restart.
    private val sessionState = HudSessionState()

    /**
     * One-shot launcher for the CAMERA runtime permission. Triggered on
     * first launch so the rear-camera screen has a working preview without
     * the rider having to dig into device settings. The result is read by
     * [com.eried.eucplanet.hud.ui.screens.CameraScreen] via the activity
     * context, so we don't need to thread the result through Compose state.
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* CameraScreen re-checks on next composition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask once at launch. If the rider denies it the placeholder text
        // on the rear-camera screen will say "permission denied" rather
        // than "no camera". The Motoeye does have a rear camera; we just
        // need to ask for access on Android 6+.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Fullscreen, no system bars, screen stays on while the app is up.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = HudUiController()
        server = HudServer(applicationContext)
        // OK / Nav buttons enqueue commands back to the phone over the
        // WebSocket. Queued in HudServer so a tap during a momentary
        // disconnect isn't lost -- it ships on the next reconnect.
        controller.onCommand = { server.sendCommand(it) }
        server.start()

        // Forward connection events into the controller. The VISUAL disconnect
        // alert is debounced separately in HudApp (DISCONNECT_RECONNECT_GRACE_MS)
        // so a brief blip the link recovers from never flashes the splash --
        // the internal detection (fast heartbeats) and the phone's reconnect
        // loop stay snappy regardless of when the rider SEES the alert.
        lifecycleScope.launch {
            server.status.collect { controller.updateStatus(it) }
        }

        // Pick up the rider's customised screen carousel from each
        // accepted wire frame. distinctUntilChanged so the controller
        // doesn't churn on the 5 Hz pump when the list hasn't moved.
        lifecycleScope.launch {
            server.state
                .map { it.enabledHudScreens }
                .distinctUntilChanged()
                .collect { controller.applyEnabledScreens(it) }
        }

        // Feed every accepted wire frame into the session accumulators
        // so screens that show rolling state (PowerScreen sparkline,
        // TripStats max/avg, Safety sag baseline) keep updating even
        // when their screen isn't the current one. The renderer
        // composables stay pure: they read what sessionState exposes,
        // they don't mutate anything themselves.
        lifecycleScope.launch {
            server.state.collect { hud -> sessionState.ingest(hud) }
        }

        // Pick up the rider's map-tile style choice. distinctUntilChanged
        // so the cache only evicts when the style actually changes, not
        // on every 5 Hz frame.
        lifecycleScope.launch {
            server.state
                .map { it.hudMapStyle }
                .distinctUntilChanged()
                .collect { tileCache.applyStyle(it) }
        }

        setContent {
            HudApp(
                state = server.state,
                status = server.status,
                peer = server.peer,
                localIp = server.localIp,
                versionCompat = server.versionCompat,
                recoveryStatus = server.recoveryStatus,
                controller = controller,
                tileCache = tileCache,
                sessionState = sessionState,
                onCommand = server::sendCommand
            )
        }
    }

    override fun onDestroy() {
        dpadHandler.removeCallbacksAndMessages(null)
        server.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Map HUD remote keys to UI actions. The Motoeye remote reports these
        // standard codes; we keep the wear-OS-style "if not consumed by us,
        // fall through" pattern so a real keyboard during development still
        // works for back/menu.
        //
        // Side effect: any DPAD press while the rider is disconnected
        // collapses the disconnected modal to a corner badge so they can
        // keep using the screens (camera, map) without the IP splash
        // covering everything.
        controller.dismissDisconnectedModal()
        return when (keyCode) {
            // The four DPAD directions are gesture-driven: a single tap runs the
            // screen behaviour, a double tap fires the rider-configured HUD button
            // action, and a hold session does nothing (left for the MotoEye). We
            // can't tell which on key-down, so we track press sessions here and
            // decide on the debounced release (see handlePressEnd). repeatCount is
            // always 0 even during a hold (the remote streams down/up pairs), so
            // we don't special-case auto-repeat.
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // A down means the press continues (hold pairs) or a new tap
                // starts -- either way, cancel any debounced release for this key.
                releasePending.remove(keyCode)?.let { dpadHandler.removeCallbacks(it) }
                if (singleTapPending.containsKey(keyCode)) {
                    // We were waiting after a first tap -> this down is the 2nd
                    // tap. Fire the configured action and switch NO screen.
                    singleTapPending.remove(keyCode)?.let { dpadHandler.removeCallbacks(it) }
                    server.sendCommand(
                        com.eried.eucplanet.hud.protocol.HudCommand.Action(slotFor(keyCode))
                    )
                    // Mark this 2nd-tap session consumed so its press-end is a
                    // no-op, and stamp a fresh start so handlePressEnd finds it.
                    consumed.add(keyCode)
                    pressStart[keyCode] = android.os.SystemClock.uptimeMillis()
                } else if (!pressStart.containsKey(keyCode)) {
                    // Start a fresh session.
                    pressStart[keyCode] = android.os.SystemClock.uptimeMillis()
                    consumed.remove(keyCode)
                }
                // else: session already active (hold stream / auto-repeat) -> nothing.
                true
            }
            // L1 / R1 stay immediate -- they're the dedicated prev/next screen
            // buttons with no long-press meaning.
            KeyEvent.KEYCODE_BUTTON_L1 -> { controller.previousScreen(); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { controller.nextScreen(); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { controller.centerAction(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // The DPAD directions don't startTracking(), so this callback never
        // fires for them -- their gestures are decided in onKeyUp/handlePressEnd.
        // We keep this ONLY for ESC/BACK.
        //
        // Long-press ESC exits the app (matches the competitor's UX so muscle
        // memory transfers). A short ESC press would interfere with hardware
        // back behaviours we may want later, so we keep it as a long press.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // Debounced release for the four DPAD directions. A hold streams as
        // down/up PAIRS, so a bare UP doesn't mean the press ended -- we post
        // a delayed press-end and let a follow-up DOWN within PRESS_DEBOUNCE_MS
        // cancel it (the hold continues). Only a clean release runs the gesture
        // decision in handlePressEnd.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val r = Runnable { handlePressEnd(keyCode) }
                releasePending.remove(keyCode)?.let { dpadHandler.removeCallbacks(it) }
                releasePending[keyCode] = r
                dpadHandler.postDelayed(r, PRESS_DEBOUNCE_MS)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * A DPAD press session ended (debounced release fired without a follow-up
     * down). Decide the gesture:
     *   - consumed session (double-tap already fired) -> no-op
     *   - held >= HOLD_MS                              -> hold, do nothing (MotoEye)
     *   - tap on an UNBOUND direction                 -> screen action immediately
     *   - tap on a BOUND direction                    -> defer DOUBLE_TAP_MS for a
     *                                                     2nd tap; if none, screen
     */
    private fun handlePressEnd(keyCode: Int) {
        releasePending.remove(keyCode)
        val start = pressStart.remove(keyCode) ?: return
        // Double-tap already fired on this session: this was just the 2nd-tap
        // release. Swallow it.
        if (consumed.remove(keyCode)) return
        val dur = android.os.SystemClock.uptimeMillis() - start
        if (dur >= HOLD_MS) return  // a hold -> leave it for MotoEye
        if (!isBound(keyCode)) {
            screenAction(keyCode)
            return
        }
        // Bound direction: wait for a possible 2nd tap. If it doesn't arrive
        // within DOUBLE_TAP_MS, this was a single tap -> screen action.
        val st = Runnable {
            singleTapPending.remove(keyCode)
            screenAction(keyCode)
        }
        singleTapPending[keyCode] = st
        dpadHandler.postDelayed(st, DOUBLE_TAP_MS)
    }

    /** Wire slot name for a DPAD keyCode. */
    private fun slotFor(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        else -> "RIGHT"
    }

    /** A direction is "bound" when the phone shipped a non-blank human label
     *  for it -- only then do we defer a tap to wait for a double-tap. Unbound
     *  directions fire the screen action immediately so screen switching is
     *  instant. */
    private fun isBound(keyCode: Int): Boolean {
        val s = server.state.value
        val label = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> s.joystickUp
            KeyEvent.KEYCODE_DPAD_DOWN -> s.joystickDown
            KeyEvent.KEYCODE_DPAD_LEFT -> s.joystickLeft
            else -> s.joystickRight
        }
        return label.isNotBlank()
    }

    /** The single-tap screen behaviour for a DPAD direction. */
    private fun screenAction(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> controller.upAction()
            KeyEvent.KEYCODE_DPAD_DOWN -> controller.downAction()
            KeyEvent.KEYCODE_DPAD_LEFT -> controller.previousScreen()
            KeyEvent.KEYCODE_DPAD_RIGHT -> controller.nextScreen()
        }
    }

    private companion object {
        /** Debounce window after a key-up before we treat the press as ended.
         *  A hold streams as down/up pairs ~1 ms apart, so a follow-up down
         *  inside this window cancels the press-end and keeps the session
         *  alive. Low enough that a real release still feels instant. */
        const val PRESS_DEBOUNCE_MS: Long = 70L
        /** Held-duration threshold separating a tap from a hold. A session
         *  whose press lasted at least this long is a hold -> we do nothing and
         *  leave it for the MotoEye device. */
        const val HOLD_MS: Long = 400L
        /** Window after a first tap's release in which a second tap (a new down)
         *  upgrades the gesture to a double-tap. Covers the human "lift" gap
         *  (~100-250 ms) between the two taps. */
        const val DOUBLE_TAP_MS: Long = 280L
    }
}
