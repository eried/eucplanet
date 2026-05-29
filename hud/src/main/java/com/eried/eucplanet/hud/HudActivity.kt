package com.eried.eucplanet.hud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.eried.eucplanet.hud.net.HudClient
import com.eried.eucplanet.hud.net.HudPeerStore
import com.eried.eucplanet.hud.ui.HudApp
import com.eried.eucplanet.hud.ui.HudUiController
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

    private lateinit var client: HudClient
    private lateinit var controller: HudUiController
    private lateinit var peerStore: HudPeerStore

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
        client = HudClient(applicationContext)
        peerStore = HudPeerStore(applicationContext)
        controller.onCommand = { client.send(it) }
        // Allow a fixed peer override for emulator testing where mDNS can't
        // traverse the host-only network between two AVDs. Read once at boot;
        // change with:
        //   adb shell setprop debug.eucplanet.hud.peer 10.0.2.2:28080
        // and relaunch. Empty in production riders' world.
        val debugPeer = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java)
                .invoke(null, "debug.eucplanet.hud.peer") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
        client.start(
            hudId = Build.MODEL.ifBlank { "hud" } + "/" + Build.SERIAL.ifBlank { "?" },
            hudVersion = BuildConfig.VERSION_NAME,
            fixedPeer = debugPeer,
            // Persisted rider-entered IP from a prior session. mDNS still
            // runs as a fallback when this is null/blank.
            manualPeer = peerStore.load()
        )

        // Forward connection events into the UI status banner.
        lifecycleScope.launch {
            client.status.collect { controller.updateStatus(it) }
        }

        setContent {
            HudApp(
                state = client.state,
                status = client.status,
                peer = client.peer,
                controller = controller,
                onCommand = client::send
            )
        }
    }

    override fun onDestroy() {
        client.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // When the manual-peer editor is open, all DPAD input goes to it; the
        // screen carousel and short-press CENTER action are bypassed. This
        // makes the editor truly modal -- the rider can't accidentally swipe
        // to the dashboard while typing an IP.
        if (controller.editor != null) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> { controller.editorLeft(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { controller.editorRight(); true }
                KeyEvent.KEYCODE_DPAD_UP    -> { controller.editorUp(); true }
                KeyEvent.KEYCODE_DPAD_DOWN  -> { controller.editorDown(); true }
                // CENTER short-press handled in onKeyUp so we can distinguish
                // it from a long press. Start tracking here so the framework
                // dispatches onKeyLongPress.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    event?.startTracking(); true
                }
                // ESC short-press cancels the editor without saving.
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                    controller.closeManualPeerEditor(); true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // Normal carousel routing. We special-case CENTER to startTracking so
        // a long-press is delivered to onKeyLongPress (opens the editor); the
        // short-press action fires in onKeyUp instead.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_BUTTON_L1 -> { controller.previousScreen(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_R1 -> { controller.nextScreen(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { controller.upAction(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { controller.downAction(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event?.repeatCount == 0) event.startTracking()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // CENTER short-press behaviour. If the long-press flag fired we don't
        // want the short action to also run, so isCanceled / isLongPress are
        // both checked.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            event?.isCanceled == false && event.flags and KeyEvent.FLAG_LONG_PRESS == 0
        ) {
            if (controller.editor != null) {
                // SAVE on short CENTER while editor is open.
                val newPeer = controller.editorPeerString()
                peerStore.save(newPeer)
                client.setManualPeer(newPeer)
                controller.closeManualPeerEditor()
            } else {
                controller.centerAction()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // Long-press ESC exits the app (matches the competitor's UX so muscle
        // memory transfers). A short ESC press would interfere with hardware
        // back behaviours we may want later, so we keep it as a long press.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            // While the editor is open, ESC short-press already cancels it,
            // so long-press should not also exit -- riders use long-press ESC
            // to leave the app, not the editor.
            if (controller.editor != null) return true
            finish(); return true
        }
        // Long-press CENTER opens the manual-peer editor. Seeded with the
        // peer the client is currently using (manual override or last mDNS
        // hit) so the rider can edit one octet instead of typing from
        // scratch.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val seed = client.manualPeer.value ?: client.peer.value
            controller.openManualPeerEditor(seed)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
