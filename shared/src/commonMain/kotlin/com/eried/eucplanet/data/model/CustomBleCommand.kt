package com.eried.eucplanet.data.model

import kotlinx.serialization.Serializable

/**
 * A user-defined "CUSTOM BLE" command — an ordered list of raw frames written to
 * the connected wheel when fired. Port of Android's CustomBleCommand, but frames
 * are stored as hex strings (so it serializes cleanly inside AppSettings, no
 * ByteArray equality dance). Frames are sent verbatim (one BLE write each, in
 * order) — the user pastes complete frames (CRC already baked in), typically
 * copied from a btsnoop. Scoped to a wheel [family] (adapter familyId) so custom
 * bytes never reach a wheel they weren't authored for; blank family = any wheel.
 */
@Serializable
data class CustomBleCommand(
    val id: String,
    val label: String,
    val family: String = "",
    /** Each entry is a whitespace-tolerant hex string for one frame. */
    val framesHex: List<String> = emptyList(),
)
