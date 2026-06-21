package com.eried.eucplanet.hudlink

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encodes / decodes an [OverlayPreset] to the `customOverlayJson` wire string the
 * HUD decodes. Same Json config as the Android side (`encodeDefaults` so every
 * field ships, `ignoreUnknownKeys` so a newer HUD field doesn't break loading,
 * `allowSpecialFloatingPointValues` for the rare NaN).
 */
object OverlayPresetCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
        prettyPrint = false
    }

    fun encode(preset: OverlayPreset): String = json.encodeToString(preset)

    fun decode(text: String): OverlayPreset? =
        if (text.isBlank()) null
        else try { json.decodeFromString<OverlayPreset>(text) } catch (_: Throwable) { null }
}
