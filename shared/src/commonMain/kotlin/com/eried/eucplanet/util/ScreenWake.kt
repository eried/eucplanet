package com.eried.eucplanet.util

/**
 * Keep the device screen awake during a ride (so the dashboard doesn't dim or
 * lock mid-ride). iOS toggles `UIApplication.idleTimerDisabled`; Android is a
 * no-op here — the `:app` host owns its own window `FLAG_KEEP_SCREEN_ON`, and the
 * shared `App()` Compose UI only runs on iOS.
 */
expect fun setKeepScreenOn(on: Boolean)
