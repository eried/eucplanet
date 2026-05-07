package com.eried.eucplanet.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.WatchApp

class MainActivity : ComponentActivity() {

    private val demoReceiver = object : BroadcastReceiver() {
        /**
         * Debug-only hook. `adb shell am broadcast -p com.eried.eucplanet \
         *   -a com.eried.eucplanet.wear.DEMO --ef speed 35 --ei battery 78 \
         *   --ei phone 64 --es accent teal`
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
                    imperialUnits = intent.getBooleanExtra("imperial", false),
                    accentKey = intent.getStringExtra("accent") ?: "default"
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
        setContent { WatchApp() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDebuggable()) {
            runCatching { unregisterReceiver(demoReceiver) }
        }
    }
}

private fun Context.isDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
