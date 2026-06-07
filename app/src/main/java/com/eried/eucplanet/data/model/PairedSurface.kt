package com.eried.eucplanet.data.model

/**
 * Snapshot of a paired companion device — Wear OS watch, Garmin watch, Garmin
 * Edge, or Pebble. Used by the Settings → Watch screen to render the collapsible
 * "Paired devices" card so the rider sees what surfaces will actually receive
 * the dial.
 *
 * Every surface can be active at the same time: the phone publishes the same
 * telemetry frame to every paired Wear OS node, every paired Garmin device, AND
 * an open Pebble watchapp. The bridges don't coordinate — each treats the phone
 * as the source of truth — and the wheel only processes one horn command per
 * debounce window, so double-fire from "horn pressed on both wrists at once"
 * is harmless.
 *
 * Detection differs by platform. Wear/Garmin report *paired* devices (known even
 * when our app isn't running on the watch), with friendly names. PebbleKit
 * Android 2 exposes no passive paired-watch query — only watchapp open/close —
 * so the Pebble surface appears while its watchapp is OPEN and carries no model
 * name (the [Kind.PEBBLE] label stands in).
 */
data class PairedSurface(
    val kind: Kind,
    /** Human-readable device name (Wear OS friendlyName / Garmin friendlyName).
     *  Empty when the bridge hasn't resolved one yet. */
    val name: String,
    /** True when the bridge is actively pushing snapshots to this device
     *  right now (vs known-paired but currently offline). */
    val active: Boolean,
    /**
     * Approximate frames-per-second the rider should expect on this
     * surface. Wear OS uses the bridge's nominal publish interval; Garmin
     * uses a rolling 1-second average of successful CIQ sendMessage calls
     * (the Connect IQ Mobile SDK rate-caps near 1 Hz, so the measured
     * value tracks closely to that on the wire).
     */
    val updateRateHz: Double = 0.0
) {
    enum class Kind { WEAR_OS, GARMIN, PEBBLE }
}
