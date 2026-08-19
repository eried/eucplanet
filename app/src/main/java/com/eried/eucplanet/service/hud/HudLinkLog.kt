package com.eried.eucplanet.service.hud

import android.util.Log
import com.eried.eucplanet.diagnostics.DiagnosticsLogger

/**
 * One stream for the whole discovery story.
 *
 * The dial layer already wrote its trace here; the probe and the broadcast
 * listener only wrote to logcat, which a rider cannot send us. That left every
 * remote report of "it won't connect" showing the verdict (no beacon, no mDNS,
 * nothing answered) with none of the context that says why: which networks the
 * phone could actually see, whether the broadcast listener was even bound,
 * whether WiFi came and went underneath it.
 *
 * All three now write here, so a Service Mode capture reads as one timeline.
 * [DiagnosticsLogger.note] is a no-op unless Service Mode is on, so this stays
 * free for riders who never open it.
 */
internal fun hudLinkNote(tag: String, msg: String) {
    Log.i(tag, "[disc] $msg")
    DiagnosticsLogger.note("hud_link: $msg")
}
