package com.eried.eucplanet.ui.theme

import com.eried.eucplanet.data.model.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Theme import/export — port of Android's theme JSON share. Serializes the chosen
 * built-in theme + the per-token custom overrides to a small JSON document the rider
 * can back up / share (written to Documents via FileStore), and applies an imported
 * one back onto AppSettings. Token keys are the same ThemeTokenSpec.key vocabulary
 * the editor uses, so a theme exported here re-imports faithfully.
 */
object ThemeIO {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun export(s: AppSettings): String =
        json.encodeToString(ThemeExport(s.theme, s.customThemeEnabled, s.customThemeColors))

    /** Returns the imported settings, or null if [text] isn't a valid theme document. */
    fun apply(text: String, s: AppSettings): AppSettings? = try {
        val t = json.decodeFromString<ThemeExport>(text)
        s.copy(
            theme = t.theme.coerceIn(0, 2),
            customThemeEnabled = t.customThemeEnabled,
            customThemeColors = t.colors,
        )
    } catch (e: Exception) {
        null
    }
}

@Serializable
private data class ThemeExport(
    val theme: Int = 1,
    val customThemeEnabled: Boolean = false,
    val colors: Map<String, Int> = emptyMap(),
)
