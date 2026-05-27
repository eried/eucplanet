package com.eried.eucplanet.hud

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.hud.net.HudClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            fixedPeer = debugPeer
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
        // Map HUD remote keys to UI actions. The Motoeye remote reports these
        // standard codes; we keep the wear-OS-style "if not consumed by us,
        // fall through" pattern so a real keyboard during development still
        // works for back/menu.
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_BUTTON_L1 -> { controller.previousScreen(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_R1 -> { controller.nextScreen(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { controller.upAction(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { controller.downAction(); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> { controller.centerAction(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        // Long-press ESC exits the app (matches the competitor's UX so muscle
        // memory transfers). A short ESC press would interfere with hardware
        // back behaviours we may want later, so we keep it as a long press.
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            finish(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
