package com.eried.eucplanet.ui.settings.eucstats

import androidx.compose.ui.graphics.ImageBitmap

/** A cropped leaderboard picture: what the dialogs preview and what they upload. */
class PickedAvatar(
    val image: ImageBitmap,
    /** The same picture as a base64 PNG, no data-URL prefix, as the server takes it. */
    val pngBase64: String,
)

/**
 * The platform's photo picker with its crop step, as the settings host gives
 * it (SettingsHost.rememberAvatarPicker). [launch] opens the picker. While
 * [cropping] the host draws its own full-screen crop step, and the dialog that
 * asked draws nothing else.
 */
class AvatarPicker(val launch: () -> Unit, val cropping: Boolean)
