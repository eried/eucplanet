package com.eried.eucplanet.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputTest {

    @Test
    fun bluetoothA2dpCountsAsExternal() {
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun bleAndWiredAndUsbCountAsExternal() {
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
    }

    @Test
    fun phoneSpeakerOnlyIsNotExternal() {
        assertFalse(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test
    fun emptyDeviceListIsNotExternal() {
        assertFalse(AudioOutput.hasExternalType(emptyList()))
    }

    @Test
    fun mixedListWithOneExternalCounts() {
        assertTrue(
            AudioOutput.hasExternalType(
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            )
        )
    }
}
