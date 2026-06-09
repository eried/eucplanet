package com.eried.eucplanet.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-defined "CUSTOM BLE" action: an ordered list of raw frames written to
 * the connected wheel when the rider taps the button. Write-only.
 *
 * Scoped to a wheel [family] (an adapter `familyId` such as "veteran"); it is
 * only dispatched when the connected wheel matches, so custom bytes never reach
 * a wheel they were not authored for. Persisted in
 * [AppSettings.dashboardCustomBle] as a JSON object keyed by [id] ("B:<uuid>"),
 * mirroring the `dashboardCustomTiles` ("C:<uuid>") pattern. The id also appears
 * in `dashboardActionOrder` like a built-in action key.
 *
 * Frames are sent verbatim (one BLE write each, in order) — the user pastes
 * complete frames, typically copied from a btsnoop, so any required CRC is
 * already baked in. See docs/superpowers/specs/2026-06-01-custom-ble-action-design.md.
 */
data class CustomBleCommand(
    val id: String,
    val label: String,
    val iconKey: String,
    val family: String,
    val frames: List<ByteArray>
) {
    /** At least one non-empty frame to send. A command with none is a no-op. */
    val isSendable: Boolean get() = frames.any { it.isNotEmpty() }

    // Explicit equals/hashCode because [frames] is a List<ByteArray] (reference
    // equality by default). Mirrors VeteranParser.Frame.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomBleCommand) return false
        return id == other.id && label == other.label && iconKey == other.iconKey &&
            family == other.family && frames.size == other.frames.size &&
            frames.indices.all { frames[it].contentEquals(other.frames[it]) }
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + label.hashCode()
        h = 31 * h + iconKey.hashCode()
        h = 31 * h + family.hashCode()
        frames.forEach { h = 31 * h + it.contentHashCode() }
        return h
    }

    companion object {
        const val ID_PREFIX = "B:"

        fun isCustomBleId(key: String): Boolean = key.startsWith(ID_PREFIX)

        /** A fresh synthetic id; pass a UUID string the caller already holds. */
        fun newId(uuid: String): String = ID_PREFIX + uuid

        /**
         * Pure dispatch gate: resolve [key] to a command that is safe to send on
         * the currently connected wheel. Returns null (caller no-ops) when the key
         * is unknown, the command has no sendable frames, the wheel is
         * disconnected, or the command targets a different [family] than
         * [connectedFamily]. Keeps raw bytes from reaching a wheel they were not
         * authored for.
         */
        fun resolveForDispatch(
            commands: Map<String, CustomBleCommand>,
            key: String,
            connectedFamily: String?
        ): CustomBleCommand? {
            val cmd = commands[key] ?: return null
            if (!cmd.isSendable) return null
            if (connectedFamily == null || cmd.family != connectedFamily) return null
            return cmd
        }

        /**
         * Parse one hex frame. Accepts spaces, commas, colons, semicolons,
         * underscores, dashes and "0x" prefixes between bytes; case-insensitive.
         * Returns null if any non-hex character remains or the nibble count is
         * odd, so the editor can reject bad input.
         */
        fun parseHexFrame(text: String): ByteArray? {
            val cleaned = text
                .replace("0x", "", ignoreCase = true)
                .replace(Regex("[\\s,:;_-]"), "")
            if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
            if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            return ByteArray(cleaned.length / 2) {
                cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Parse a multiline frames blob: one frame per non-blank line. Returns
         * null if ANY non-blank line fails to parse (editor rejects the whole
         * input); all-blank input yields an empty list.
         */
        fun parseFrames(text: String): List<ByteArray>? {
            val out = ArrayList<ByteArray>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                out.add(parseHexFrame(line) ?: return null)
            }
            return out
        }

        fun bytesToHex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

        fun framesToText(frames: List<ByteArray>): String =
            frames.joinToString("\n") { bytesToHex(it) }

        /**
         * Parse every command from the `dashboardCustomBle` JSON string. Malformed
         * JSON or entries yield an empty map / skipped entry rather than throwing,
         * matching the lenient read pattern of the sibling dashboard tile fields.
         */
        fun parseAll(json: String): Map<String, CustomBleCommand> {
            if (json.isBlank()) return emptyMap()
            val obj = try { JSONObject(json) } catch (e: Exception) { return emptyMap() }
            val out = LinkedHashMap<String, CustomBleCommand>()
            for (key in obj.keys()) {
                val v = obj.optJSONObject(key) ?: continue
                val arr = v.optJSONArray("frames")
                val frames = ArrayList<ByteArray>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        parseHexFrame(arr.optString(i))?.let { frames.add(it) }
                    }
                }
                out[key] = CustomBleCommand(
                    id = key,
                    label = v.optString("label", ""),
                    iconKey = v.optString("icon", "BOLT"),
                    family = v.optString("family", ""),
                    frames = frames
                )
            }
            return out
        }

        /** Serialize commands back to the `dashboardCustomBle` JSON string. */
        fun serialize(commands: Collection<CustomBleCommand>): String {
            val obj = JSONObject()
            for (c in commands) {
                val arr = JSONArray()
                c.frames.forEach { arr.put(bytesToHex(it)) }
                obj.put(
                    c.id,
                    JSONObject()
                        .put("label", c.label)
                        .put("icon", c.iconKey)
                        .put("family", c.family)
                        .put("frames", arr)
                )
            }
            return obj.toString()
        }
    }
}
