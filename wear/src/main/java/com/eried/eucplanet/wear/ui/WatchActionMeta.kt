package com.eried.eucplanet.wear.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.graphics.vector.ImageVector
import com.eried.eucplanet.wear.R

/**
 * Maps a [com.eried.eucplanet.data.model.FlicAction]-style action name (carried
 * across the Data Layer as a String) to an icon and a localized label the watch
 * can display.
 *
 * Kept in the wear module rather than imported from app/ because the watch
 * doesn't have a Hilt-injected dependency on AppSettings — it just needs the
 * String name. The action vocabulary mirrors `FlicAction` enum on the phone.
 */
internal fun iconForAction(action: String): ImageVector? = when (action) {
    "HORN" -> Icons.Filled.Campaign
    "LIGHT_TOGGLE" -> Icons.Filled.FlashlightOn
    "LOCK_TOGGLE" -> Icons.Filled.Lock
    "SAFETY_TOGGLE", "SAFETY_ON", "SAFETY_OFF" -> Icons.Filled.Shield
    "VOICE_ANNOUNCE" -> Icons.Filled.RecordVoiceOver
    "RECORD_TOGGLE", "RECORD_START", "RECORD_STOP" -> Icons.Filled.FiberManualRecord
    "MEDIA_PLAY_PAUSE" -> Icons.Filled.PlayArrow
    "MEDIA_NEXT" -> Icons.Filled.SkipNext
    "MEDIA_PREVIOUS" -> Icons.Filled.SkipPrevious
    else -> null
}

/** Short user-facing label for the action, in the watch's locale. */
internal fun labelForAction(context: Context, action: String): String? = when (action) {
    "HORN" -> context.getString(R.string.watch_action_horn)
    "LIGHT_TOGGLE" -> context.getString(R.string.watch_action_light)
    "LOCK_TOGGLE" -> context.getString(R.string.watch_action_lock)
    "SAFETY_TOGGLE" -> context.getString(R.string.watch_action_safety)
    "SAFETY_ON" -> context.getString(R.string.watch_action_safety_on)
    "SAFETY_OFF" -> context.getString(R.string.watch_action_safety_off)
    "VOICE_ANNOUNCE" -> context.getString(R.string.watch_action_voice)
    "RECORD_TOGGLE" -> context.getString(R.string.watch_action_record)
    "RECORD_START" -> context.getString(R.string.watch_action_record_start)
    "RECORD_STOP" -> context.getString(R.string.watch_action_record_stop)
    "MEDIA_PLAY_PAUSE" -> context.getString(R.string.watch_action_media_play)
    "MEDIA_NEXT" -> context.getString(R.string.watch_action_media_next)
    "MEDIA_PREVIOUS" -> context.getString(R.string.watch_action_media_prev)
    else -> null
}
