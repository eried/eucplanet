package com.eried.eucplanet.hudlink

import kotlinx.serialization.Serializable

/**
 * iOS port of Android's Overlay Studio data model (`hud-protocol/OverlayLayout.kt`).
 * A preset serializes to the `customOverlayJson` string the phone streams in
 * [HudState]; the HUD (the Android `:hud` app running on the rider's HUD device)
 * decodes + renders it. So the iOS app is the *editor*, not the renderer.
 *
 * In package `hudlink` (NOT `hud.protocol`) to avoid a duplicate-class clash on
 * Android — see [HudState]. The JSON is keyed by property name and enums encode
 * by constant name (no `@SerialName` on the Android side), so the package rename
 * is wire-safe AS LONG AS field names + enum constant names stay identical here.
 * The round-trip test `OverlayPresetTest` guards that contract.
 */

/** How the studio screen is divided into background viewport panes. */
enum class ViewportLayout(val paneCount: Int, val dividerCount: Int) {
    SINGLE(1, 0),
    ROWS_2(2, 1),
    COLUMNS_2(2, 1),
    ROWS_3(3, 2),
    COLUMNS_3(3, 2),
    GRID_4(4, 2);

    fun defaultDividers(): List<Float> = when (this) {
        SINGLE -> emptyList()
        ROWS_2, COLUMNS_2 -> listOf(0.5f)
        ROWS_3, COLUMNS_3 -> listOf(1f / 3f, 2f / 3f)
        GRID_4 -> listOf(0.5f, 0.5f)
    }
}

/** Background source for a single viewport pane. */
enum class ViewportSourceType { CAMERA, SOLID, IMAGE, GRADIENT }

/** Background configuration for one viewport pane. */
@Serializable
data class ViewportConfig(
    val source: ViewportSourceType = ViewportSourceType.CAMERA,
    val cameraKey: String = "BACK",
    val cameraMirror: Boolean = false,
    val cameraOrientation: Int = 0,
    val fitMode: String = "CROP",
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorFilter: String = "NONE",
    val zoom: Float = 1f,
    val solidColor: Long = 0xFF101014L,
    val imageData: String? = null,
    val gradientColors: List<Long> = listOf(0xFF1E1E2EL, 0xFF4FC3F7L),
    val gradientStops: List<Float> = listOf(0f, 1f),
    val gradientAngle: Float = 90f,
    val gradientRadial: Boolean = false,
)

/** Kind of floating overlay element the rider can drop onto the layout. */
enum class OverlayElementType {
    WHEEL_NAME,
    APP_BADGE,
    TEXT,
    DATA_VALUE,
    DATA_GRAPH,
    DATA_DIAL,
    DATA_BAR,
    FLOATING_CAMERA,
    IMAGE,
    CLOCK,
    G_FORCE,
    MAP,
}

/**
 * One floating overlay element. Unused fields for a given [type] keep their
 * defaults and are simply ignored by that element's renderer. Field set + order
 * is faithful to the Android model so the JSON round-trips intact.
 *
 * NOTE: [id] defaults to empty (Android uses java.util.UUID, unavailable on
 * Kotlin/Native) — the editor assigns a stable id on creation.
 */
@Serializable
data class OverlayElement(
    val id: String = "",
    val type: OverlayElementType,

    val x: Float = 0.08f,
    val y: Float = 0.08f,
    val width: Float = 0.4f,
    val height: Float = 0f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val shadow: Boolean = false,
    val shadowColor: Long = 0xFF000000L,
    val shadowStrength: Float = 0.55f,
    val shadowDistance: Float = 3f,
    val shadowAngle: Float = 45f,

    val metric: String = "SPEED",
    val showLabel: Boolean = true,

    val text: String = "This is a text",
    val textAlign: String = "START",

    val badgeStacked: Boolean = false,
    val badgeShowVersion: Boolean = false,

    val graphWindowSec: Int = 10,

    val gaugeMax: Float = 100f,

    val foreground: Long = 0xFFFFFFFFL,
    val background: Long = 0x66000000L,

    val cameraKey: String = "FRONT",

    val imageData: String? = null,
    val chromaKeyEnabled: Boolean = false,
    val chromaKeyColor: Long = 0xFF00FF00L,
    val chromaKeyTolerance: Float = 0.14f,

    val clockStyle: String = "DIGITAL",
    val clockShowDate: Boolean = false,
    val clock24Hour: Boolean = true,

    val mapStyle: String = "STREET",
    val mapZoom: Int = 16,
    val mapRotateWithHeading: Boolean = false,
    val mapTrace: Boolean = true,
    val mapBorderWidth: Float = 2f,
    val mapUseCustomMarker: Boolean = true,
    val gForceScale: Float = 1f,
    val gForceSmoothing: Float = 0.2f,
    val barShowValue: Boolean = true,
    val dialStyle: String = "FULL",
    val unitPosition: String = "RIGHT",
    val dialShowColorBand: Boolean = false,
    val dialOrangeThresholdPct: Int = 80,
    val dialRedThresholdPct: Int = 90,
)

/** A complete, saveable studio configuration. */
@Serializable
data class OverlayPreset(
    val name: String = "",
    val layout: ViewportLayout = ViewportLayout.SINGLE,
    val dividers: List<Float> = emptyList(),
    val viewports: List<ViewportConfig> = listOf(ViewportConfig()),
    val elements: List<OverlayElement> = emptyList(),
    val dividerColor: Long = 0xCCFFFFFFL,
    val dividerThickness: Float = 3f,
)
