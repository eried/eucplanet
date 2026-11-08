package com.eried.eucplanet.ui.charging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue

/**
 * CoreMotion at Android's SENSOR_DELAY_GAME pace (50 Hz). Core Motion reports
 * acceleration in g with the opposite sign to Android's accelerometer, so it
 * is scaled and flipped to match; rotation rates share Android's sign. The
 * app is portrait only, so the screen's horizontal is the device's X.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PhoneMotionEffect(onGravityX: (Float) -> Unit, onGyroY: (Float) -> Unit) {
    val gravity = rememberUpdatedState(onGravityX)
    val gyro = rememberUpdatedState(onGyroY)
    val manager = remember { CMMotionManager() }
    DisposableEffect(manager) {
        val queue = NSOperationQueue.mainQueue
        if (manager.accelerometerAvailable) {
            manager.accelerometerUpdateInterval = 0.02
            manager.startAccelerometerUpdatesToQueue(queue) { data, _ ->
                data?.acceleration?.useContents { gravity.value((-x * 9.81).toFloat()) }
            }
        }
        if (manager.gyroAvailable) {
            manager.gyroUpdateInterval = 0.02
            manager.startGyroUpdatesToQueue(queue) { data, _ ->
                data?.rotationRate?.useContents { gyro.value(y.toFloat()) }
            }
        }
        onDispose {
            manager.stopAccelerometerUpdates()
            manager.stopGyroUpdates()
        }
    }
}
