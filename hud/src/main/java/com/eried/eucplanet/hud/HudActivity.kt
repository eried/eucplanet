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
    // Manual hold-duration timing for the four DPAD directions. We record the
    // key-down uptime per keyCode and, on key-up, measure the held duration
    // ourselves rather than relying on the framework's onKeyLongPress callback.
    // The real MotoEye remote was mis-firing the short-press carousel move on a
    // genuine long hold via the startTracking()/onKeyLongPress path; a manual
    // threshold (LONG_PRESS_MS) makes the decision unambiguous: a short tap runs
    // ONLY the short action, a long hold sends ONLY the long action. Keyed by
    // keyCode so simultaneous directions can't clobber each other's timer.
    private val dpadDownAt = HashMap<Int, Long>()
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

        // Forward connection events into the UI status banner.
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
                controller = controller,
                tileCache = tileCache,
                sessionState = sessionState,
                onCommand = server::sendCommand
            )
        }
    }

    override fun onDestroy() {
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
            // The four DPAD directions are dual-action: a SHORT press runs the
            // carousel / zoom / view behaviour, a LONG hold fires the rider-
            // configured HUD button action. We can't tell which on key-down, so
            // we just record the press time (and flash the guide overlay) here
            // and decide in onKeyUp from the held duration. repeatCount > 0 is
            // the framework's auto-repeat while the rider keeps holding -- we
            // consume it but do nothing, so a hold never triggers a stream of
            // actions, only the single long action on release.
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event?.repeatCount == 0) {
                    dpadDownAt[keyCode] = android.os.SystemClock.uptimeMillis()
                }
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
        // DPAD direction long-press is handled manually in onKeyUp now (see
        // dpadDownAt), so we don't startTracking() them and this callback never
        // fires for them. We keep it ONLY for ESC/BACK.
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
        // Decide short-vs-long for the four DPAD directions from the held
        // duration we measured ourselves. >= LONG_PRESS_MS sends ONLY the
        // rider-configured long action to the phone; a shorter tap runs ONLY
        // the screen-specific short behaviour. Either way exactly one fires.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val downAt = dpadDownAt.remove(keyCode)
                // No recorded down (e.g. key-up without our key-down): ignore.
                if (downAt == null) return true
                val held = android.os.SystemClock.uptimeMillis() - downAt
                if (held >= LONG_PRESS_MS) {
                    val slot = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> "UP"
                        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                        else -> "RIGHT"
                    }
                    server.sendCommand(
                        com.eried.eucplanet.hud.protocol.HudCommand.Action(slot)
                    )
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> controller.upAction()
                        KeyEvent.KEYCODE_DPAD_DOWN -> controller.downAction()
                        KeyEvent.KEYCODE_DPAD_LEFT -> controller.previousScreen()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> controller.nextScreen()
                    }
                }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        /** Hold threshold separating a short tap (screen behaviour) from a long
         *  hold (configured button action). 450 ms is comfortably above an
         *  accidental long tap yet short enough to feel intentional on the
         *  MotoEye remote. */
        const val LONG_PRESS_MS: Long = 450L
    }
}
