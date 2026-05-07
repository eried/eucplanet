package com.eried.eucplanet.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.bridge.WatchStateRepository
import com.eried.eucplanet.wear.ui.WatchApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dev hook for previewing the connected UI without a paired phone:
        //   adb shell am start -n com.eried.eucplanet/.wear.MainActivity --ez preview true
        // The repo cache is plain memory so this is gone once the process dies.
        if (intent?.getBooleanExtra("preview", false) == true) {
            WatchStateRepository.update(
                WatchState(
                    connected = true,
                    wheelName = "InMotion V14 50GB",
                    speedKmh = 24.7f,
                    batteryPercent = 78,
                    voltage = 96.2f,
                    current = 4.3f,
                    pwmPercent = 32f,
                    temperatureC = 38f,
                    tripKm = 12.4f,
                    torque = 18.6f,
                    lightOn = true,
                    maxSpeedKmh = 45f,
                    hasHorn = true,
                    hasLight = true
                )
            )
        }

        setContent { WatchApp() }
    }
}
