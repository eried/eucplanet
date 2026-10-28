package com.eried.eucplanet.hud.ui

import android.view.KeyEvent

/** Which remote keys the HUD keeps for itself. */
object HudKeys {

    /** Keys the HUD acts on, or leaves to the system (volume). */
    private val OURS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    /**
     * True for a key that frees the camera. The MotoEye remote's camera
     * button shows its rear view on a press and starts or stops the
     * recorder on a hold, and both need the camera a camera screen holds.
     * Its key code is not documented, so every key that is not one of ours
     * counts; the key itself is still passed on to the MotoEye.
     */
    fun handsOffCamera(keyCode: Int): Boolean = keyCode !in OURS
}
