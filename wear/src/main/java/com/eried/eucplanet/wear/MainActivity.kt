package com.eried.eucplanet.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.wear.bridge.WatchControl
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.WatchApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val demoReceiver = object : BroadcastReceiver() {
        /**
         * Debug-only hook. `adb shell am broadcast -p com.eried.eucplanet \
         *   -a com.eried.eucplanet.wear.DEMO --ef speed 35 --ei battery 78 \
         *   --ei phone 64 --es accent teal --es speedUnit mph`
         * Unit extras (speedUnit / distanceUnit / tempUnit) take the same
         * codes as the Data Layer keys; omitting them defaults to metric.
         * fills the watch UI with synthetic state so the dashboard renders
         * without the phone/Data Layer pairing being live. Gated by the
         * debuggable flag so this never ships in a release build.
         */
        override fun onReceive(context: Context, intent: Intent) {
            if (!context.isDebuggable()) return
            WatchStateRepository.update(
                WatchState(
                    connected = true,
                    wheelName = intent.getStringExtra("name") ?: "Demo wheel",
                    speedKmh = intent.getFloatExtra("speed", 28f),
                    batteryPercent = intent.getIntExtra("battery", 78),
                    phoneBatteryPercent = intent.getIntExtra("phone", 64),
                    voltage = intent.getFloatExtra("voltage", 95.4f),
                    current = intent.getFloatExtra("current", 12.3f),
                    pwmPercent = intent.getFloatExtra("pwm", 35f),
                    temperatureC = intent.getFloatExtra("temp", 42f),
                    tripKm = intent.getFloatExtra("trip", 12.34f),
                    torque = intent.getFloatExtra("torque", 5.6f),
                    lightOn = intent.getBooleanExtra("light", false),
                    maxSpeedKmh = intent.getFloatExtra("maxSpeed", 70f),
                    hasHorn = intent.getBooleanExtra("horn", true),
                    hasLight = intent.getBooleanExtra("hasLight", true),
                    speedUnit = intent.getStringExtra("speedUnit") ?: "kmh",
                    distanceUnit = intent.getStringExtra("distanceUnit") ?: "km",
                    tempUnit = intent.getStringExtra("tempUnit") ?: "C",
                    accentKey = intent.getStringExtra("accent") ?: "default",
                    // Packed theme colors for testing the custom-theme mirror,
                    // same "#"-less AARRGGBB pipe order as ThemeAccent.packForWatch.
                    themePacked = intent.getStringExtra("theme") ?: "",
                    showGaugeBand = intent.getBooleanExtra("band", false),
                    pwmDisplay = intent.getStringExtra("pwmDisplay") ?: "BOTH",
                    showSpeedUnit = intent.getBooleanExtra("showSpeedUnit", true),
                    showWheelBattery = intent.getBooleanExtra("showWheelBatt", true),
                    showPhoneBattery = intent.getBooleanExtra("showPhoneBatt", true),
                    showWatchBattery = intent.getBooleanExtra("showWatchBatt", true),
                    keepScreenOn = intent.getBooleanExtra("keepOn", true)
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isDebuggable()) {
            ContextCompat.registerReceiver(
                this,
                demoReceiver,
                IntentFilter("com.eried.eucplanet.wear.DEMO"),
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        // Apply / clear FLAG_KEEP_SCREEN_ON whenever the phone-pushed setting
        // toggles. The watch's own ambient mode still kicks in if the user
        // covers the screen, this only blocks the inactivity timeout.
        lifecycleScope.launch {
            WatchStateRepository.state
                .map { it.keepScreenOn }
                .distinctUntilChanged()
                .collect { keepOn ->
                    if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }

        // One-shot announce: ship Build / BuildConfig info to the phone so
        // its Service Mode log captures both sides of the pair. Idempotent
        // (gated by a flag inside WatchStateRepository), best-effort, no-op
        // if no phone is paired right now.
        WatchStateRepository.sendWatchInfo(applicationContext)

        // Listen for a phone-side "Stop all" close signal. Phone fires this
        // only when the user has the matching "Close watch on exit" toggle
        // on; we finish the task so the dial doesn't sit on a stale frame.
        lifecycleScope.launch {
            WatchStateRepository.closeSignal.collect {
                finishAndRemoveTask()
            }
        }

        setContent { WatchApp() }
    }

    override fun onStart() {
        super.onStart()
        WatchStateRepository.setActivityVisible(true)
    }

    override fun onStop() {
        WatchStateRepository.setActivityVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDebuggable()) {
            runCatching { unregisterReceiver(demoReceiver) }
        }
    }

    /**
     * Watch hardware-button intercept. Galaxy Watch Ultra surfaces the orange
     * Action button as KEYCODE_STEM_1 and the lower side button as STEM_2
     * (after the user has bound EUC Planet to launch on press in the watch's
     * Customize-buttons menu). The bindings come from phone settings via the
     * Data Layer; we look them up at press time so live changes take effect.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val state = WatchStateRepository.state.value
        val action = when (keyCode) {
            KeyEvent.KEYCODE_STEM_1 -> state.stem1Click
            KeyEvent.KEYCODE_STEM_2 -> state.stem2Click
            else -> null
        }
        if (action == null || action == "NONE") return super.onKeyDown(keyCode, event)
        if (event?.repeatCount == 0) {
            // Mark as long-press-eligible so onKeyLongPress can intercept the
            // hold variant. KeyEvent.startTracking requires this to fire.
            event.startTracking()
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        val state = WatchStateRepository.state.value
        val action = when (keyCode) {
            KeyEvent.KEYCODE_STEM_1 -> state.stem1Hold
            KeyEvent.KEYCODE_STEM_2 -> state.stem2Hold
            else -> null
        }
        if (action == null || action == "NONE") return super.onKeyLongPress(keyCode, event)
        dispatchAction(action)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // If the press was tracking (long-press eligible) and DIDN'T trigger
        // a long-press handler, treat it as a click and dispatch the click
        // binding. Long-press already consumed the event in onKeyLongPress.
        if (event != null && (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2)) {
            if (event.isTracking && !event.isCanceled) {
                val state = WatchStateRepository.state.value
                val action = if (keyCode == KeyEvent.KEYCODE_STEM_1) state.stem1Click
                             else state.stem2Click
                if (action != "NONE") {
                    dispatchAction(action)
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a [com.eried.eucplanet.data.model.FlicAction] name. HORN and
     * LIGHT_TOGGLE use the existing dedicated control intents so older watch
     * builds without the action: prefix handler still work; everything else
     * goes through the prefixed passthrough that PhoneWearListenerService
     * forwards to FlicManager.dispatchActionByName().
     */
    private fun dispatchAction(action: String) {
        val intent = when (action) {
            "HORN" -> WatchControl.HORN
            "LIGHT_TOGGLE" -> {
                val on = WatchStateRepository.state.value.lightOn
                if (on) WatchControl.LIGHT_OFF else WatchControl.LIGHT_ON
            }
            else -> WatchControl.ACTION_PREFIX + action
        }
        WatchStateRepository.sendControl(this, intent)
        // Mirror the touch-button path's haptic so the rider's
        // watchHapticOnAction setting works for hardware buttons too —
        // previously this path was silent and only on-screen taps
        // produced the buzz. Garmin's Actions.mc:dispatch already fires
        // haptic for both press kinds, so this brings Wear OS to parity.
        if (WatchStateRepository.state.value.hapticOnAction) {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        50L, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(50L)
            }
        }
    }
}

private fun Context.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
