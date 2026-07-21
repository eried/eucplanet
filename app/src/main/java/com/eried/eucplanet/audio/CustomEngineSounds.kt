package com.eried.eucplanet.audio

import org.json.JSONObject

/** Slot keys for a custom engine. These mirror CompositionEnginePlayer section names. */
object CustomSlot {
    const val IDLE = "idle_loop"       // required main / default clip
    const val REV = "rev_loop"         // optional high-speed loop
    const val STARTUP = "startup"      // optional one-shot on engine start
    const val DECEL = "decel"          // optional one-shot pop / backfire on throttle close
    const val SHUTDOWN = "shutdown"    // optional one-shot on engine stop

    /** Editor order. IDLE first (required), then the optional one-shots/loops. */
    val ALL = listOf(IDLE, REV, STARTUP, DECEL, SHUTDOWN)
    val OPTIONAL = listOf(REV, STARTUP, DECEL, SHUTDOWN)
}

/** UI status for a single slot's file reference. */
enum class SlotStatus { OK, REQUIRED, MISSING, EMPTY_OPTIONAL }

/**
 * Pure helpers for the rider's custom engine: (de)serialize the slot->URI map,
 * compute per-slot status, and build the synthetic [EngineProfile] the audio
 * engine plays. No Android or IO dependencies, so it is fully unit-tested.
 */
object CustomEngineSounds {

    fun parseSlots(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    val v = obj.optString(key, "")
                    if (v.isNotBlank()) put(key, v)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun encodeSlots(slots: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in slots) if (v.isNotBlank()) obj.put(k, v)
        return obj.toString()
    }

    /** [canOpen] answers whether a URI still resolves (injected so this stays pure). */
    fun statusFor(slot: String, uri: String?, canOpen: (String) -> Boolean): SlotStatus {
        if (uri.isNullOrBlank()) {
            return if (slot == CustomSlot.IDLE) SlotStatus.REQUIRED else SlotStatus.EMPTY_OPTIONAL
        }
        return if (canOpen(uri)) SlotStatus.OK else SlotStatus.MISSING
    }

    /**
     * Build the synthetic custom profile. In single-file mode only the main
     * (idle) clip is used, pitch-shifted by speed. In multi mode every provided
     * slot becomes a URI-backed section. An empty map yields an empty (silent)
     * section set, which the composition player renders as no sound.
     */
    fun buildProfile(slots: Map<String, String>, modulatePitch: Boolean): EngineProfile {
        val effective = if (modulatePitch) slots.filterKeys { it == CustomSlot.IDLE } else slots
        val sections = buildMap {
            for ((slot, uri) in effective) {
                if (uri.isNotBlank()) put(slot, SampleSection(rawAsset = "", startMs = 0, endMs = 0, uri = uri))
            }
        }
        return EngineProfile(
            key = EngineProfile.CUSTOM_KEY,
            displayName = "Custom",
            kind = EngineProfile.Kind.ICE,
            gearless = true,
            idleRpm = 700,
            maxRpm = 6000,
            sampleSections = sections,
            pitchModulated = modulatePitch,
            supportsMuffler = false,
            supportsBrakeWhine = false,
            supportsPops = false,
        )
    }
}
