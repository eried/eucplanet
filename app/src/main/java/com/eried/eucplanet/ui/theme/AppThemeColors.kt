package com.eried.eucplanet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject

/**
 * The full set of semantic color tokens that make up one theme. A theme is
 * single-flavor: this object IS the theme's palette. Light, Dark and Pure Black
 * are sibling [AppThemeColors] instances (see [BuiltInThemes]).
 *
 * The object is [Immutable] and held in [LocalAppColors] so that, while the
 * theme editor is closed, reading a token costs the same as reading
 * `MaterialTheme.colorScheme.*` and never triggers an extra recomposition.
 *
 * To add a token: add the field here, add a [ThemeTokens] spec entry (so it
 * serializes + shows in the editor), and set it in every built-in.
 */
@Immutable
data class AppThemeColors(
    /** True for light themes; drives the Material base scheme + system bar icons. */
    val isLight: Boolean,

    // Surfaces
    val appBackground: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val dialog: Color,
    val outline: Color,
    val scrim: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,

    // Accent
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val tertiary: Color,
    val selection: Color,

    // Status
    val statusGood: Color,
    val statusWarn: Color,
    val statusDanger: Color,

    // Metric palette
    val metricVoltage: Color,
    val metricBattery: Color,
    val metricTemp: Color,
    val metricPosition: Color,
    val metricAccel: Color,

    // Gauge
    val gaugeTrack: Color,
    val gaugeFill: Color,
    val gaugeWarn: Color,
    val gaugeDanger: Color,

    // Navigation / overlay
    val navRouteLine: Color,
    val navPopupPanel: Color,
    val navPopupInk: Color,

    // Indicators
    val connectionActive: Color,
    val connectionIdle: Color,
)

/**
 * Builds a Material3 [ColorScheme] from the theme's tokens. The ~448 existing
 * `MaterialTheme.colorScheme.*` reads across the app reskin through this with no
 * changes. We base off the matching light/dark Material default so the roles we
 * don't explicitly map keep sensible values.
 */
fun AppThemeColors.toColorScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onPrimary,
        tertiary = tertiary,
        onTertiary = onPrimary,
        background = appBackground,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        outline = outline,
        scrim = scrim,
        error = statusDanger,
    )
}

/**
 * Editor + serialization registry. One entry per token: stable JSON [key], a
 * human [label], a [group] for the editor list, and get/set lambdas so we never
 * need reflection. Order here is the order tokens appear in the editor.
 */
data class ThemeTokenSpec(
    val key: String,
    val label: String,
    val group: String,
    val get: (AppThemeColors) -> Color,
    val set: (AppThemeColors, Color) -> AppThemeColors,
)

object ThemeTokens {
    const val GROUP_SURFACES = "Surfaces"
    const val GROUP_TEXT = "Text & icons"
    const val GROUP_ACCENT = "Accent"
    const val GROUP_STATUS = "Status"
    const val GROUP_METRIC = "Metric palette"
    const val GROUP_GAUGE = "Gauge"
    const val GROUP_NAV = "Navigation"
    const val GROUP_INDICATOR = "Indicators"

    val specs: List<ThemeTokenSpec> = listOf(
        ThemeTokenSpec("appBackground", "App background", GROUP_SURFACES, { it.appBackground }, { c, v -> c.copy(appBackground = v) }),
        ThemeTokenSpec("surface", "Card / surface", GROUP_SURFACES, { it.surface }, { c, v -> c.copy(surface = v) }),
        ThemeTokenSpec("surfaceVariant", "Variant surface", GROUP_SURFACES, { it.surfaceVariant }, { c, v -> c.copy(surfaceVariant = v) }),
        ThemeTokenSpec("dialog", "Dialog / sheet", GROUP_SURFACES, { it.dialog }, { c, v -> c.copy(dialog = v) }),
        ThemeTokenSpec("outline", "Divider / outline", GROUP_SURFACES, { it.outline }, { c, v -> c.copy(outline = v) }),
        ThemeTokenSpec("scrim", "Scrim / modal dim", GROUP_SURFACES, { it.scrim }, { c, v -> c.copy(scrim = v) }),

        ThemeTokenSpec("textPrimary", "Primary text", GROUP_TEXT, { it.textPrimary }, { c, v -> c.copy(textPrimary = v) }),
        ThemeTokenSpec("textSecondary", "Secondary text", GROUP_TEXT, { it.textSecondary }, { c, v -> c.copy(textSecondary = v) }),
        ThemeTokenSpec("textDisabled", "Disabled text", GROUP_TEXT, { it.textDisabled }, { c, v -> c.copy(textDisabled = v) }),

        ThemeTokenSpec("primary", "Primary / accent", GROUP_ACCENT, { it.primary }, { c, v -> c.copy(primary = v) }),
        ThemeTokenSpec("onPrimary", "On-accent", GROUP_ACCENT, { it.onPrimary }, { c, v -> c.copy(onPrimary = v) }),
        ThemeTokenSpec("secondary", "Secondary", GROUP_ACCENT, { it.secondary }, { c, v -> c.copy(secondary = v) }),
        ThemeTokenSpec("tertiary", "Tertiary", GROUP_ACCENT, { it.tertiary }, { c, v -> c.copy(tertiary = v) }),
        ThemeTokenSpec("selection", "Selection / highlight", GROUP_ACCENT, { it.selection }, { c, v -> c.copy(selection = v) }),

        ThemeTokenSpec("statusGood", "Good / safe", GROUP_STATUS, { it.statusGood }, { c, v -> c.copy(statusGood = v) }),
        ThemeTokenSpec("statusWarn", "Warning", GROUP_STATUS, { it.statusWarn }, { c, v -> c.copy(statusWarn = v) }),
        ThemeTokenSpec("statusDanger", "Danger / error", GROUP_STATUS, { it.statusDanger }, { c, v -> c.copy(statusDanger = v) }),

        ThemeTokenSpec("metricVoltage", "Voltage", GROUP_METRIC, { it.metricVoltage }, { c, v -> c.copy(metricVoltage = v) }),
        ThemeTokenSpec("metricBattery", "Battery / speed", GROUP_METRIC, { it.metricBattery }, { c, v -> c.copy(metricBattery = v) }),
        ThemeTokenSpec("metricTemp", "Temp / power", GROUP_METRIC, { it.metricTemp }, { c, v -> c.copy(metricTemp = v) }),
        ThemeTokenSpec("metricPosition", "Position", GROUP_METRIC, { it.metricPosition }, { c, v -> c.copy(metricPosition = v) }),
        ThemeTokenSpec("metricAccel", "Acceleration", GROUP_METRIC, { it.metricAccel }, { c, v -> c.copy(metricAccel = v) }),

        ThemeTokenSpec("gaugeTrack", "Gauge track", GROUP_GAUGE, { it.gaugeTrack }, { c, v -> c.copy(gaugeTrack = v) }),
        ThemeTokenSpec("gaugeFill", "Gauge fill", GROUP_GAUGE, { it.gaugeFill }, { c, v -> c.copy(gaugeFill = v) }),
        ThemeTokenSpec("gaugeWarn", "Gauge warning band", GROUP_GAUGE, { it.gaugeWarn }, { c, v -> c.copy(gaugeWarn = v) }),
        ThemeTokenSpec("gaugeDanger", "Gauge danger band", GROUP_GAUGE, { it.gaugeDanger }, { c, v -> c.copy(gaugeDanger = v) }),

        ThemeTokenSpec("navRouteLine", "Map route line", GROUP_NAV, { it.navRouteLine }, { c, v -> c.copy(navRouteLine = v) }),
        ThemeTokenSpec("navPopupPanel", "Nav popup panel", GROUP_NAV, { it.navPopupPanel }, { c, v -> c.copy(navPopupPanel = v) }),
        ThemeTokenSpec("navPopupInk", "Nav popup ink", GROUP_NAV, { it.navPopupInk }, { c, v -> c.copy(navPopupInk = v) }),

        ThemeTokenSpec("connectionActive", "Connected / active", GROUP_INDICATOR, { it.connectionActive }, { c, v -> c.copy(connectionActive = v) }),
        ThemeTokenSpec("connectionIdle", "Disconnected / idle", GROUP_INDICATOR, { it.connectionIdle }, { c, v -> c.copy(connectionIdle = v) }),
    )

    /** Specs grouped in declaration order, for the editor's sectioned list. */
    val grouped: List<Pair<String, List<ThemeTokenSpec>>> =
        specs.groupBy { it.group }.toList()
}

/**
 * Maps a legacy palette constant (still baked into data like [com.eried.eucplanet.data.model.MetricCatalog])
 * to the matching active-theme token, so catalog/threshold-driven colors follow
 * the theme without rewriting every data definition. Use at render sites that
 * receive a baked palette Color: `MaterialTheme.appColors.remap(metric.accent)`.
 */
fun AppThemeColors.remap(legacy: Color): Color = when (legacy) {
    AccentBlue -> metricVoltage
    AccentGreen -> metricBattery
    AccentOrange -> metricTemp
    AccentPurple -> metricPosition
    AccentPink -> metricAccel
    AccentRed -> statusDanger
    AccentYellow -> gaugeWarn
    AccentTeal -> primary
    else -> legacy
}

/** ARGB hex like "FF1A1A1A" (no leading #). */
fun Color.toHex(): String =
    (toArgb().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

fun hexToColor(hex: String): Color? = runCatching {
    val clean = hex.removePrefix("#")
    val argb = when (clean.length) {
        6 -> 0xFF000000L or clean.toLong(16)   // RRGGBB → opaque
        8 -> clean.toLong(16)                    // AARRGGBB
        else -> return null
    }
    Color(argb.toInt())
}.getOrNull()

/**
 * JSON shape: `{ "name": "...", "isLight": false, "colors": { "<key>": "AARRGGBB", ... } }`.
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

/**
 * The active theme's tokens, provided once at the app root. Static because the
 * value is a stable immutable object while the editor is closed — readers don't
 * recompose unless the whole theme actually changes.
 */
val LocalAppColors = staticCompositionLocalOf { BuiltInThemes.pureBlack.colors }

/**
 * Ergonomic accessor so app-specific tokens read just like the Material roles:
 * `MaterialTheme.appColors.statusGood` sits right next to
 * `MaterialTheme.colorScheme.primary` and autocompletes the same way. New code
 * never hardcodes a color — it reaches for a colorScheme role or an appColors
 * token, and is themed (and editable by the target tool) for free.
 */
val MaterialTheme.appColors: AppThemeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current
