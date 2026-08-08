package com.eried.eucplanet.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Single source of truth for "is audio currently going to an external output
 * device" (Bluetooth A2DP / BLE, wired headphones or headset, or USB) rather
 * than the phone's built-in speaker. Used to gate speed-driven automations so
 * they only act when the rider is actually listening on headphones / Bluetooth.
 *
 * Point-in-time poll of the current output devices - both callers already run
 * on a periodic tick, so no route-change listener is needed.
 */
object AudioOutput {

    /** Output device types that count as "external" (not the phone speaker). */
    val EXTERNAL_OUTPUT_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** Pure decision: does any device type in [types] count as external? */
    fun hasExternalType(types: List<Int>): Boolean = types.any { it in EXTERNAL_OUTPUT_TYPES }

    /** True when a current output device is external (see [EXTERNAL_OUTPUT_TYPES]). */
    fun isExternalActive(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return hasExternalType(devices.map { it.type })
    }
}
