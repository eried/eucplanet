package com.eried.eucplanet.ui.theme

import org.json.JSONObject

/**
 * Custom-theme persistence (Service-Mode theme editor). Kept in :app because it
 * uses org.json (JVM-only); the pure color data lives in :shared. iOS doesn't
 * ship the theme editor, so it never needs this.
 *
 * JSON shape: `{ "isLight": false, "colors": { "<key>": "AARRGGBB", ... } }`.
 * Unknown keys are ignored on read; missing keys fall back to [fallback] so an
 * older saved theme that predates a new token still loads cleanly.
 */
object ThemeJson {
    fun colorsToJson(c: AppThemeColors): JSONObject = JSONObject().apply {
        put("isLight", c.isLight)
        val colors = JSONObject()
        ThemeTokens.specs.forEach { spec -> colors.put(spec.key, spec.get(c).toHex()) }
        put("colors", colors)
    }

    fun colorsFromJson(j: JSONObject, fallback: AppThemeColors): AppThemeColors {
        val isLight = j.optBoolean("isLight", fallback.isLight)
        val colors = j.optJSONObject("colors") ?: JSONObject()
        var result = fallback.copy(isLight = isLight)
        ThemeTokens.specs.forEach { spec ->
            val hex = colors.optString(spec.key, "")
            val parsed = if (hex.isNotEmpty()) hexToColor(hex) else null
            if (parsed != null) result = spec.set(result, parsed)
        }
        return result
    }

    fun colorsToString(c: AppThemeColors): String = colorsToJson(c).toString()

    fun colorsFromString(s: String, fallback: AppThemeColors): AppThemeColors? =
        runCatching { colorsFromJson(JSONObject(s), fallback) }.getOrNull()
}
