package com.eried.eucplanet.flic

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** The phone's media volume and flashlight for the PHONE_* actions; moved from FlicManager. */
class AndroidPhoneControls(private val context: Context) : PhoneControls {
    private companion object {
        const val TAG = "FlicManager"
    }

    override fun stepVolume(direction: Int) =
        adjustPhoneVolume(if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)

    override fun toggleMute() = adjustPhoneVolume(AudioManager.ADJUST_TOGGLE_MUTE)

    /**
     * One step of the media volume, with the system slider shown. Aimed at the
     * music stream by name: "the suggested stream" would be our own volume key
     * session while it holds the buttons, and the press would come back to us.
     */
    private fun adjustPhoneVolume(direction: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }.onFailure { Log.w(TAG, "phone volume change refused", it) }
    }

    /** What the torch is doing right now, kept by the callback below once it is registered. */
    @Volatile private var torchOn = false
    private var torchWatched = false

    /**
     * The phone's flashlight, flipped. The system tells every app the torch
     * state through a callback, so a torch the rider switched on from the
     * quick settings is switched off here, not "on" a second time.
     */
    override fun toggleTorch() {
        runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            if (!torchWatched) {
                torchWatched = true
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) torchOn = enabled
                    }
                }, Handler(Looper.getMainLooper()))
            }
            cm.setTorchMode(id, !torchOn)
            torchOn = !torchOn
        }.onFailure { Log.w(TAG, "phone torch refused", it) }
    }
}
