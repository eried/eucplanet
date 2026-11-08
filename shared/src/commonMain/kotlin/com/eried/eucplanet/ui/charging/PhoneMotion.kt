package com.eried.eucplanet.ui.charging

import androidx.compose.runtime.Composable

/**
 * The phone's motion while the composition shows it, for the battery's
 * liquid. [onGravityX] gets gravity along the screen's horizontal in m/s²
 * (Android's accelerometer sign, remapped for the display's rotation);
 * [onGyroY] the turn rate about the device's Y axis in rad/s.
 */
@Composable
expect fun PhoneMotionEffect(onGravityX: (Float) -> Unit, onGyroY: (Float) -> Unit)
