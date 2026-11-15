package com.eried.eucplanet.ui.studio.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A camera the device exposes. [deviceId] is the *logical* camera that gets
 * opened; [physicalId] (when set) pins a specific physical lens behind it
 * (Android's OutputConfiguration.setPhysicalCameraId); that is how the
 * ultrawide / telephoto are reached, since only logical cameras appear in the
 * id list.
 */
data class StudioCameraInfo(
    val key: String,
    val label: String,
    val front: Boolean,
    val deviceId: String,
    val physicalId: String? = null
)

/**
 * Multi-camera feed hub for the Overlay Studio: the cameras the phone has and
 * the latest frame of each one streaming, as Compose state the viewports and
 * floating windows draw. The platform binds the cameras and fills it (Camera2
 * on Android, in rememberStudioCameraHub).
 *
 * Where the hardware genuinely cannot co-stream a requested pair, only the first
 * camera goes live and the UI shows a placeholder for the rest.
 */
class StudioCameraHub {
    /** Every camera detected on the device. */
    var cameras by mutableStateOf<List<StudioCameraInfo>>(emptyList())

    /** How many cameras can stream at once on this device (1 or 2). */
    var maxConcurrent by mutableStateOf(1)

    /** True once camera enumeration has finished. */
    var ready by mutableStateOf(false)

    /** Latest frame per logical camera key. */
    val frames = mutableStateMapOf<String, ImageBitmap?>()

    /** Logical keys currently streaming. */
    var liveKeys by mutableStateOf<Set<String>>(emptySet())

    /** The live platform binding, kept so teardown can close it directly. */
    var binding: AutoCloseable? = null

    fun frame(key: String): ImageBitmap? = frames[key]
    fun isLive(key: String): Boolean = key in liveKeys
    fun info(key: String): StudioCameraInfo? = cameras.firstOrNull { it.key == key }
}

/**
 * Frame access to the replay faces' video clips: whether a clip opened, its
 * length, and a decoded frame for the offline export. Android reads clips
 * with MediaMetadataRetriever (AndroidStudioVideoHub).
 */
interface StudioVideoHub {
    /** True when [uri] could not be opened (moved, no permission, undecodable). */
    fun isInvalid(uri: String): Boolean

    /** The clip's length, 0 while unknown. */
    fun durationMs(uri: String): Long

    /** The frame at [timeUs], or null. */
    fun frameAt(uri: String, timeUs: Long): ImageBitmap?
}
