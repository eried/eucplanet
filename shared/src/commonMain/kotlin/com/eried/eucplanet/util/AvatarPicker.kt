package com.eried.eucplanet.util

/**
 * Avatar photo-picker seam. The iOS app (Swift `AvatarPhotoPicker`) sets
 * [nativeImpl] at launch to a PHPicker-backed implementation; [pick] presents it
 * and calls back a 256×256 PNG as base64 (or null when cancelled). Android leaves
 * [nativeImpl] null — the Android app uses its own picker and never drives this
 * shared screen — so [pick] simply returns null there. No expect/actual needed:
 * the hook is a plain settable lambda, kept null on platforms that don't wire it.
 */
object AvatarPicker {
    var nativeImpl: ((onResult: (String?) -> Unit) -> Unit)? = null

    fun pick(onResult: (String?) -> Unit) {
        val impl = nativeImpl
        if (impl != null) impl(onResult) else onResult(null)
    }
}
