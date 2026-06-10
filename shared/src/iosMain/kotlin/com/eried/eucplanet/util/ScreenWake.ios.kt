package com.eried.eucplanet.util

import platform.UIKit.UIApplication

/** Disable the idle timer so the screen stays on while riding. */
actual fun setKeepScreenOn(on: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = on
}
