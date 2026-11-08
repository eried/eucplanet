package com.eried.eucplanet.ui.charging

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
actual fun PhoneMotionEffect(onGravityX: (Float) -> Unit, onGyroY: (Float) -> Unit) {
    val ctx = LocalContext.current
    // Accelerometer values are in the device's fixed frame, so we remap them to
    // the current display rotation, otherwise the liquid tilts the wrong way in
    // landscape (device X no longer points along the screen's horizontal).
    val view = LocalView.current
    val gravity = rememberUpdatedState(onGravityX)
    val gyro = rememberUpdatedState(onGyroY)
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                when (e.sensor.type) {
                    android.hardware.Sensor.TYPE_ACCELEROMETER -> {
                        // Gravity component along the SCREEN's horizontal, remapped
                        // from the device frame by the display rotation so the
                        // liquid sloshes the right way in every orientation.
                        val horiz = when (view.display?.rotation) {
                            Surface.ROTATION_90 -> -e.values[1]
                            Surface.ROTATION_180 -> -e.values[0]
                            Surface.ROTATION_270 -> e.values[1]
                            else -> e.values[0]
                        }
                        gravity.value(horiz)
                    }
                    android.hardware.Sensor.TYPE_GYROSCOPE -> gyro.value(e.values[1])
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
}
