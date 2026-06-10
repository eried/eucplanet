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
import androidx.compose.ui.graphics.takeOrElse
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

    // --- Granular add-on tokens. Default Unspecified and resolved from a base
    //     token by fillDerived(), so the 3 built-ins and any theme saved before
    //     these existed keep rendering with no per-theme values to maintain.
    // Surfaces
    val tileBackground: Color = Color.Unspecified,
    val sheetBackground: Color = Color.Unspecified,
    val topBar: Color = Color.Unspecified,
    val menuBackground: Color = Color.Unspecified,
    val divider: Color = Color.Unspecified,
    // Text & icons
    val sectionHeader: Color = Color.Unspecified,
    val link: Color = Color.Unspecified,
    val hint: Color = Color.Unspecified,
    val tileLabel: Color = Color.Unspecified,
    // Small MIN/MAX/AVG stat caption on metric tiles. Its own token so it can be
    // recolored (e.g. pure black) without dragging every other piece of
    // secondary text along (it used to read colorScheme.onSurfaceVariant).
    val cornerStatLabel: Color = Color.Unspecified,
    // Dashboard action glyphs (map/navigator + camera entry points). Their own
    // token so they aren't pinned to statusGood ("Good / safe"), which they are
    // not semantically.
    val dashIcon: Color = Color.Unspecified,
    // Inputs & controls
    val fieldBackground: Color = Color.Unspecified,
    val fieldText: Color = Color.Unspecified,
    val fieldLabel: Color = Color.Unspecified,
    val fieldBorder: Color = Color.Unspecified,
    val segmentSelectedBg: Color = Color.Unspecified,
    val segmentSelectedText: Color = Color.Unspecified,
    val segmentText: Color = Color.Unspecified,
    val switchOn: Color = Color.Unspecified,
    val switchOff: Color = Color.Unspecified,
    val sliderActive: Color = Color.Unspecified,
    val sliderTrack: Color = Color.Unspecified,
    // Buttons & chips
    val tonalButtonFill: Color = Color.Unspecified,
    val tonalButtonText: Color = Color.Unspecified,
    val textButton: Color = Color.Unspecified,
    val outlineButtonBorder: Color = Color.Unspecified,
    val chipBackground: Color = Color.Unspecified,
    val chipSelected: Color = Color.Unspecified,
    // Feedback
    val snackbarBackground: Color = Color.Unspecified,
    val snackbarText: Color = Color.Unspecified,
    val snackbarAction: Color = Color.Unspecified,
    // Navigation / map route lines (per travel mode) + the route preview line
    val routeWalk: Color = Color.Unspecified,
    val routeBike: Color = Color.Unspecified,
    val routeDrive: Color = Color.Unspecified,
    val routeStraight: Color = Color.Unspecified,
    val routePreview: Color = Color.Unspecified,
    // Charging monitor: the spark icon accent + the two liquid-fill colors
    // (current charge vs. energy added this session).
    val chargingAccent: Color = Color.Unspecified,
    val chargingFillCurrent: Color = Color.Unspecified,
    val chargingFillAdded: Color = Color.Unspecified,
)

/**
 * Resolve any granular add-on token still left [Color.Unspecified] to the base
 * token it falls back to. Applied to every theme after construction (and after
 * Pure Black overrides its surfaces) so derived values track the right base.
 */
fun AppThemeColors.fillDerived(): AppThemeColors = copy(
    tileBackground = tileBackground.takeOrElse { surfaceVariant },
    sheetBackground = sheetBackground.takeOrElse { dialog },
    topBar = topBar.takeOrElse { appBackground },
    menuBackground = menuBackground.takeOrElse { surface },
    divider = divider.takeOrElse { outline },
    sectionHeader = sectionHeader.takeOrElse { textSecondary },
    link = link.takeOrElse { primary },
    hint = hint.takeOrElse { textSecondary },
    tileLabel = tileLabel.takeOrElse { textSecondary },
    cornerStatLabel = cornerStatLabel.takeOrElse { textSecondary },
    dashIcon = dashIcon.takeOrElse { statusGood },
    fieldBackground = fieldBackground.takeOrElse { surface },
    fieldText = fieldText.takeOrElse { textPrimary },
    fieldLabel = fieldLabel.takeOrElse { textSecondary },
    fieldBorder = fieldBorder.takeOrElse { outline },
    segmentSelectedBg = segmentSelectedBg.takeOrElse { surfaceVariant },
    segmentSelectedText = segmentSelectedText.takeOrElse { primary },
    segmentText = segmentText.takeOrElse { textPrimary },
    switchOn = switchOn.takeOrElse { primary },
    switchOff = switchOff.takeOrElse { surfaceVariant },
    sliderActive = sliderActive.takeOrElse { primary },
    sliderTrack = sliderTrack.takeOrElse { surfaceVariant },
    tonalButtonFill = tonalButtonFill.takeOrElse { surfaceVariant },
    tonalButtonText = tonalButtonText.takeOrElse { primary },
    textButton = textButton.takeOrElse { primary },
    outlineButtonBorder = outlineButtonBorder.takeOrElse { outline },
    chipBackground = chipBackground.takeOrElse { surfaceVariant },
    chipSelected = chipSelected.takeOrElse { primary },
    snackbarBackground = snackbarBackground.takeOrElse { textPrimary },
    snackbarText = snackbarText.takeOrElse { surface },
    snackbarAction = snackbarAction.takeOrElse { primary },
    // Map route colors keep their historical hex unless the rider edits them.
    routeWalk = routeWalk.takeOrElse { Color(0xFF7E57C2) },
    routeBike = routeBike.takeOrElse { Color(0xFF26A69A) },
    routeDrive = routeDrive.takeOrElse { Color(0xFFFB8C00) },
    routeStraight = routeStraight.takeOrElse { Color(0xFF42A5F5) },
    routePreview = routePreview.takeOrElse { Color(0xFFFFCA28) },
    chargingAccent = chargingAccent.takeOrElse { statusGood },
    // Old charge (present at session start) = blue; new charge (added this
    // session) = pink. Both resolve from existing metric hues so they track the
    // theme and stay editable.
    chargingFillCurrent = chargingFillCurrent.takeOrElse { metricVoltage },
    chargingFillAdded = chargingFillAdded.takeOrElse { metricAccel },
)

/**
 * Builds a Material3 [ColorScheme] from the theme's tokens. The ~448 existing
 * `MaterialTheme.colorScheme.*` reads across the app reskin through this with no
 * changes. We base off the matching light/dark Material default so the roles we
 * don't explicitly map keep sensible values.
 */
fun AppThemeColors.toColorScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    // Map EVERY Material slot from a token — the unmapped *container / inverse /
    // tint* slots used to fall back to Material's default purple, which is why
    // things like the segmented selector's selected text weren't following the
    // theme. The "container" slots stay subtle (surfaceVariant fill + accent
    // content) so filled-tonal buttons / chips don't turn into solid accents.
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary,
        onPrimaryContainer = onPrimary,
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = primary,
        tertiary = tertiary,
        onTertiary = onPrimary,
        tertiaryContainer = surfaceVariant,
        onTertiaryContainer = tertiary,
        background = appBackground,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceTint = primary,
        inverseSurface = textPrimary,
        inverseOnSurface = surface,
        error = statusDanger,
        onError = onPrimary,
        errorContainer = surfaceVariant,
        onErrorContainer = statusDanger,
        outline = outline,
        outlineVariant = outline,
        scrim = scrim,
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
    const val GROUP_INPUTS = "Inputs & controls"
    const val GROUP_BUTTONS = "Buttons & chips"
    const val GROUP_FEEDBACK = "Feedback"

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
        ThemeTokenSpec("routeWalk", "Route: walk", GROUP_NAV, { it.routeWalk }, { c, v -> c.copy(routeWalk = v) }),
        ThemeTokenSpec("routeBike", "Route: bike", GROUP_NAV, { it.routeBike }, { c, v -> c.copy(routeBike = v) }),
        ThemeTokenSpec("routeDrive", "Route: drive", GROUP_NAV, { it.routeDrive }, { c, v -> c.copy(routeDrive = v) }),
        ThemeTokenSpec("routeStraight", "Route: straight", GROUP_NAV, { it.routeStraight }, { c, v -> c.copy(routeStraight = v) }),
        ThemeTokenSpec("routePreview", "Route: preview line", GROUP_NAV, { it.routePreview }, { c, v -> c.copy(routePreview = v) }),

        ThemeTokenSpec("connectionActive", "Connected / active", GROUP_INDICATOR, { it.connectionActive }, { c, v -> c.copy(connectionActive = v) }),
        ThemeTokenSpec("connectionIdle", "Disconnected / idle", GROUP_INDICATOR, { it.connectionIdle }, { c, v -> c.copy(connectionIdle = v) }),

        // --- Granular add-on tokens ---
        ThemeTokenSpec("tileBackground", "Tile background", GROUP_SURFACES, { it.tileBackground }, { c, v -> c.copy(tileBackground = v) }),
        ThemeTokenSpec("sheetBackground", "Bottom sheet", GROUP_SURFACES, { it.sheetBackground }, { c, v -> c.copy(sheetBackground = v) }),
        ThemeTokenSpec("topBar", "Top app bar", GROUP_SURFACES, { it.topBar }, { c, v -> c.copy(topBar = v) }),
        ThemeTokenSpec("menuBackground", "Dropdown menu", GROUP_SURFACES, { it.menuBackground }, { c, v -> c.copy(menuBackground = v) }),
        ThemeTokenSpec("divider", "Divider line", GROUP_SURFACES, { it.divider }, { c, v -> c.copy(divider = v) }),

        ThemeTokenSpec("sectionHeader", "Section header", GROUP_TEXT, { it.sectionHeader }, { c, v -> c.copy(sectionHeader = v) }),
        ThemeTokenSpec("link", "Link text", GROUP_TEXT, { it.link }, { c, v -> c.copy(link = v) }),
        ThemeTokenSpec("hint", "Hint / placeholder", GROUP_TEXT, { it.hint }, { c, v -> c.copy(hint = v) }),
        ThemeTokenSpec("tileLabel", "Tile label", GROUP_TEXT, { it.tileLabel }, { c, v -> c.copy(tileLabel = v) }),
        ThemeTokenSpec("cornerStatLabel", "Tile stat caption (MIN / MAX)", GROUP_TEXT, { it.cornerStatLabel }, { c, v -> c.copy(cornerStatLabel = v) }),
        ThemeTokenSpec("dashIcon", "Dashboard action icon", GROUP_TEXT, { it.dashIcon }, { c, v -> c.copy(dashIcon = v) }),

        ThemeTokenSpec("fieldBackground", "Field background", GROUP_INPUTS, { it.fieldBackground }, { c, v -> c.copy(fieldBackground = v) }),
        ThemeTokenSpec("fieldText", "Field text", GROUP_INPUTS, { it.fieldText }, { c, v -> c.copy(fieldText = v) }),
        ThemeTokenSpec("fieldLabel", "Field label", GROUP_INPUTS, { it.fieldLabel }, { c, v -> c.copy(fieldLabel = v) }),
        ThemeTokenSpec("fieldBorder", "Field border", GROUP_INPUTS, { it.fieldBorder }, { c, v -> c.copy(fieldBorder = v) }),
        ThemeTokenSpec("segmentSelectedBg", "Selector selected fill", GROUP_INPUTS, { it.segmentSelectedBg }, { c, v -> c.copy(segmentSelectedBg = v) }),
        ThemeTokenSpec("segmentSelectedText", "Selector selected text", GROUP_INPUTS, { it.segmentSelectedText }, { c, v -> c.copy(segmentSelectedText = v) }),
        ThemeTokenSpec("segmentText", "Selector text", GROUP_INPUTS, { it.segmentText }, { c, v -> c.copy(segmentText = v) }),
        ThemeTokenSpec("switchOn", "Switch on", GROUP_INPUTS, { it.switchOn }, { c, v -> c.copy(switchOn = v) }),
        ThemeTokenSpec("switchOff", "Switch off track", GROUP_INPUTS, { it.switchOff }, { c, v -> c.copy(switchOff = v) }),
        ThemeTokenSpec("sliderActive", "Slider active", GROUP_INPUTS, { it.sliderActive }, { c, v -> c.copy(sliderActive = v) }),
        ThemeTokenSpec("sliderTrack", "Slider track", GROUP_INPUTS, { it.sliderTrack }, { c, v -> c.copy(sliderTrack = v) }),

        ThemeTokenSpec("tonalButtonFill", "Tonal button fill", GROUP_BUTTONS, { it.tonalButtonFill }, { c, v -> c.copy(tonalButtonFill = v) }),
        ThemeTokenSpec("tonalButtonText", "Tonal button text", GROUP_BUTTONS, { it.tonalButtonText }, { c, v -> c.copy(tonalButtonText = v) }),
        ThemeTokenSpec("textButton", "Text button", GROUP_BUTTONS, { it.textButton }, { c, v -> c.copy(textButton = v) }),
        ThemeTokenSpec("outlineButtonBorder", "Outlined button border", GROUP_BUTTONS, { it.outlineButtonBorder }, { c, v -> c.copy(outlineButtonBorder = v) }),
        ThemeTokenSpec("chipBackground", "Chip background", GROUP_BUTTONS, { it.chipBackground }, { c, v -> c.copy(chipBackground = v) }),
        ThemeTokenSpec("chipSelected", "Chip selected", GROUP_BUTTONS, { it.chipSelected }, { c, v -> c.copy(chipSelected = v) }),

        ThemeTokenSpec("snackbarBackground", "Snackbar background", GROUP_FEEDBACK, { it.snackbarBackground }, { c, v -> c.copy(snackbarBackground = v) }),
        ThemeTokenSpec("snackbarText", "Snackbar text", GROUP_FEEDBACK, { it.snackbarText }, { c, v -> c.copy(snackbarText = v) }),
        ThemeTokenSpec("snackbarAction", "Snackbar action", GROUP_FEEDBACK, { it.snackbarAction }, { c, v -> c.copy(snackbarAction = v) }),

        ThemeTokenSpec("chargingAccent", "Charging indicator", GROUP_STATUS, { it.chargingAccent }, { c, v -> c.copy(chargingAccent = v) }),
        ThemeTokenSpec("chargingFillCurrent", "Charge fill (current)", GROUP_GAUGE, { it.chargingFillCurrent }, { c, v -> c.copy(chargingFillCurrent = v) }),
        ThemeTokenSpec("chargingFillAdded", "Charge fill (added)", GROUP_GAUGE, { it.chargingFillAdded }, { c, v -> c.copy(chargingFillAdded = v) }),
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
