package com.eried.eucplanet.service

/**
 * Whether the phone's audio output should be held awake between sounds, so
 * the first beep or voice line after a quiet stretch is not clipped by an
 * output (a Bluetooth headset above all) that had gone to sleep.
 *
 * A preview always holds the route; a ride holds it until [quietMs] pass with
 * no sound. Android's TonePlayer and the iPhone's IosTonePlayer both ask this.
 */
fun routeKeepAliveWanted(
    preview: Boolean, ride: Boolean, nowMs: Long, lastSoundMs: Long, quietMs: Long,
): Boolean = preview || (ride && nowMs - lastSoundMs < quietMs)
