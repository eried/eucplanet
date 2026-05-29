package com.eried.eucplanet.wear.ui

import android.content.Context
import androidx.annotation.StringRes
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
 * Watch-side icon + label registry, keyed by phone action key (the string
 * the Data Layer carries from the phone).
 *
 * KEEP IN SYNC with `com.eried.eucplanet.data.model.ActionCatalog` in the
 * `app` module. The catalog is the single source of truth for which keys
 * are eyes-free-safe (i.e. WATCH-bindable). The watch lives in its own
 * Gradle module without an `app/` dependency, so this file maintains its
 * own mapping — but the mapping is now consolidated into one table so the
 * icon and label entries can never drift out of sync key-wise (adding an
 * entry covers both). Labels stay watch-specific (`watch_action_*`) because
 * the watch screen needs shorter strings than the phone's `action_chip_*`.
 */
private data class WatchActionEntry(
    val icon: ImageVector,
    @StringRes val labelRes: Int
)

private val WATCH_ACTION_TABLE: Map<String, WatchActionEntry> = mapOf(
    "HORN" to WatchActionEntry(Icons.Filled.Campaign, R.string.watch_action_horn),
    "LIGHT_TOGGLE" to WatchActionEntry(Icons.Filled.FlashlightOn, R.string.watch_action_light),
    "LOCK_TOGGLE" to WatchActionEntry(Icons.Filled.Lock, R.string.watch_action_lock),
    "SAFETY_TOGGLE" to WatchActionEntry(Icons.Filled.Shield, R.string.watch_action_safety),
    "SAFETY_ON" to WatchActionEntry(Icons.Filled.Shield, R.string.watch_action_safety_on),
    "SAFETY_OFF" to WatchActionEntry(Icons.Filled.Shield, R.string.watch_action_safety_off),
    "VOICE_ANNOUNCE" to WatchActionEntry(Icons.Filled.RecordVoiceOver, R.string.watch_action_voice),
    "RECORD_TOGGLE" to WatchActionEntry(Icons.Filled.FiberManualRecord, R.string.watch_action_record),
    "RECORD_START" to WatchActionEntry(Icons.Filled.FiberManualRecord, R.string.watch_action_record_start),
    "RECORD_STOP" to WatchActionEntry(Icons.Filled.FiberManualRecord, R.string.watch_action_record_stop),
    "MEDIA_PLAY_PAUSE" to WatchActionEntry(Icons.Filled.PlayArrow, R.string.watch_action_media_play),
    "MEDIA_NEXT" to WatchActionEntry(Icons.Filled.SkipNext, R.string.watch_action_media_next),
    "MEDIA_PREVIOUS" to WatchActionEntry(Icons.Filled.SkipPrevious, R.string.watch_action_media_prev)
)

internal fun iconForAction(action: String): ImageVector? =
    WATCH_ACTION_TABLE[action]?.icon

/** Short user-facing label for the action, in the watch's locale. */
internal fun labelForAction(context: Context, action: String): String? =
    WATCH_ACTION_TABLE[action]?.labelRes?.let { context.getString(it) }
