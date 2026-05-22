package com.eried.eucplanet.data.model

import java.util.UUID

/**
 * Data model for the Overlay Studio — the video / photo recorder with fully
 * customisable data overlays.
 *
 * A whole studio configuration is one [OverlayPreset]: the viewport division
 * layout, the per-pane background sources, and the floating overlay elements.
 * Presets serialise to a single self-contained `.json` file (see
 * [com.eried.eucplanet.data.store.OverlayPresetJson]) — user images are
 * embedded as base64 so a preset can be copied between phones intact.
 *
 * Everything positional is stored as a 0..1 fraction of the screen so a preset
 * built on one device renders proportionally on another.
 */

/** How the studio screen is divided into background viewport panes. */
enum class ViewportLayout(val paneCount: Int, val dividerCount: Int) {
    /** One full-screen pane. */
    SINGLE(1, 0),
    /** Two panes stacked top / bottom, one draggable horizontal divider. */
    ROWS_2(2, 1),
    /** Two panes side by side, one draggable vertical divider. */
    COLUMNS_2(2, 1),
    /** Three stacked rows, two horizontal dividers. */
    ROWS_3(3, 2),
    /** Three side-by-side columns, two vertical dividers. */
    COLUMNS_3(3, 2),
    /** 2x2 grid, one shared vertical + one shared horizontal divider. */
    GRID_4(4, 2);

    /** Even split positions used when the layout is first selected. */
    fun defaultDividers(): List<Float> = when (this) {
        SINGLE -> emptyList()
        ROWS_2, COLUMNS_2 -> listOf(0.5f)
        ROWS_3, COLUMNS_3 -> listOf(1f / 3f, 2f / 3f)
        GRID_4 -> listOf(0.5f, 0.5f)
    }
}

/** Background source for a single viewport pane. */
enum class ViewportSourceType { CAMERA, SOLID, IMAGE, GRADIENT }

/**
 * Background configuration for one viewport pane. [cameraKey] is a portable
 * logical camera id ("BACK", "FRONT", "BACK2", …) resolved to a real device
 * camera at runtime, so each pane picks its own camera independently and a
 * preset moves between phones intact.
 */
data class ViewportConfig(
    val source: ViewportSourceType = ViewportSourceType.CAMERA,
    val cameraKey: String = "BACK",
    /** Horizontally flip the camera frame (GPU graphicsLayer transform). */
    val cameraMirror: Boolean = false,
    /** Camera frame rotation in degrees: one of 0 / 90 / 180 / 270. */
    val cameraOrientation: Int = 0,
    /**
     * How the camera frame / source image fills its viewport — one of
     * "CROP" (fill, crop overflow), "FIT" (letterboxed) or "CENTER"
     * (original size, centred). Maps to a Compose [ContentScale]; GPU-only.
     */
    val fitMode: String = "CROP",
    /** Colour-grade brightness, -1..1 (0 = neutral). GPU ColorMatrix only. */
    val brightness: Float = 0f,
    /** Colour-grade contrast, 0..2 (1 = neutral). GPU ColorMatrix only. */
    val contrast: Float = 1f,
    /** Colour-grade saturation, 0..2 (1 = neutral). GPU ColorMatrix only. */
    val saturation: Float = 1f,
    /** Filter preset — one of NONE / BW / SEPIA / WARM / COOL. GPU ColorMatrix only. */
    val colorFilter: String = "NONE",
    /** Digital zoom factor, 1..3 — a GPU graphicsLayer scale, no pixel work. */
    val zoom: Float = 1f,
    /** ARGB colour used when [source] is [ViewportSourceType.SOLID]. */
    val solidColor: Long = 0xFF101014L,
    /** Base64 PNG embedded in the preset when [source] is [ViewportSourceType.IMAGE]. */
    val imageData: String? = null,
    // --- Gradient ([ViewportSourceType.GRADIENT]) ---
    /** Stop colours (ARGB longs), parallel to [gradientStops]. */
    val gradientColors: List<Long> = listOf(0xFF1E1E2EL, 0xFF4FC3F7L),
    /** Stop positions 0..1, parallel to [gradientColors]. */
    val gradientStops: List<Float> = listOf(0f, 1f),
    /** Linear-gradient direction in degrees (ignored when radial). */
    val gradientAngle: Float = 90f,
    /** True for a radial gradient, false for linear. */
    val gradientRadial: Boolean = false
)

/** Kind of floating overlay element the rider can drop onto the layout. */
enum class OverlayElementType {
    /** The connected wheel's model / brand name. */
    WHEEL_NAME,
    /** The EUC Planet name + launcher icon badge. */
    APP_BADGE,
    /** Free text with {speed}-style live variables. */
    TEXT,
    /** A single live telemetry value (speed, temp, battery, …). */
    DATA_VALUE,
    /** A rolling line graph of a telemetry value over a time window. */
    DATA_GRAPH,
    /** A circular gauge (dial) for a telemetry value. */
    DATA_DIAL,
    /** A horizontal bar gauge for a telemetry value. */
    DATA_BAR,
    /** A small floating camera window (picture-in-picture). */
    FLOATING_CAMERA,
    /** A user-supplied image / clipart, embedded in the preset. */
    IMAGE,
    /** A clock / watch — digital, analog, plain text, or a stopwatch. */
    CLOCK,
    /** A circular crosshair plotting live lateral × forward G-force with a comet trail. */
    G_FORCE,
    /** A live mini-map of the rider's GPS position with a route trace. */
    MAP
}

/**
 * One floating overlay element. Unused fields for a given [type] keep their
 * defaults and are simply ignored by that element's renderer.
 */
data class OverlayElement(
    val id: String = UUID.randomUUID().toString(),
    val type: OverlayElementType,

    // Placement — top-left corner and width as a fraction of the screen.
    // Height follows from the element's content / image aspect ratio.
    val x: Float = 0.08f,
    val y: Float = 0.08f,
    val width: Float = 0.4f,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    /** Draw a soft drop shadow behind the element so it reads on bright video. */
    val shadow: Boolean = false,

    // DATA_VALUE / DATA_GRAPH — which metric, see StudioMetric.
    val metric: String = "SPEED",
    val showLabel: Boolean = true,

    // TEXT — free text template; {speed}-style tokens resolve to live values.
    val text: String = "This is a text",
    /** TEXT alignment: START / CENTER / END. */
    val textAlign: String = "START",

    // APP_BADGE — layout options for the EUC Planet badge.
    /** Stack the icon above the text instead of placing them side by side. */
    val badgeStacked: Boolean = false,
    /** Append the app version number under / after the name. */
    val badgeShowVersion: Boolean = false,

    // DATA_GRAPH — rolling window length in seconds.
    val graphWindowSec: Int = 60,

    // DATA_DIAL / DATA_BAR — the value the gauge treats as full (min is 0).
    val gaugeMax: Float = 100f,

    // Colours, ARGB longs. A fully-transparent background = "invisible".
    val foreground: Long = 0xFFFFFFFFL,
    val background: Long = 0x66000000L,

    // FLOATING_CAMERA — which camera this PiP window shows (logical key).
    val cameraKey: String = "FRONT",

    // IMAGE — base64 PNG embedded directly in the preset so it travels with it.
    val imageData: String? = null,
    /** When true, pixels close to [chromaKeyColor] are made transparent. */
    val chromaKeyEnabled: Boolean = false,
    val chromaKeyColor: Long = 0xFF00FF00L,
    /** 0..1 — how far a pixel may stray from the key colour and still drop out. */
    val chromaKeyTolerance: Float = 0.14f,

    // CLOCK — DIGITAL / ANALOG / TEXT / STOPWATCH; a date line for the TEXT style.
    val clockStyle: String = "DIGITAL",
    val clockShowDate: Boolean = false,

    // MAP — live mini-map options.
    /** Tile style: STREET / DARK / SATELLITE. */
    val mapStyle: String = "STREET",
    /** Slippy-map tile zoom level, 10..19. */
    val mapZoom: Int = 16,
    /** True rotates the map so the direction of travel points up; false = north up. */
    val mapRotateWithHeading: Boolean = false,
    /** Draw the GPS trace polyline over the map. */
    val mapTrace: Boolean = true
)

/**
 * A complete, saveable studio configuration. [name] is blank for the working
 * draft and set once the rider saves it as a named preset file.
 */
data class OverlayPreset(
    val name: String = "",
    val layout: ViewportLayout = ViewportLayout.SINGLE,
    val dividers: List<Float> = ViewportLayout.SINGLE.defaultDividers(),
    val viewports: List<ViewportConfig> = listOf(ViewportConfig()),
    val elements: List<OverlayElement> = emptyList(),
    /** ARGB colour of the lines between viewport panes. */
    val dividerColor: Long = 0xCCFFFFFFL,
    /** Thickness of the divider lines, in dp. */
    val dividerThickness: Float = 3f
) {
    /**
     * Returns a copy with [viewports] and [dividers] resized to fit [newLayout]
     * — extra panes are dropped, missing panes get a default camera source.
     * Divider positions reset to the even defaults for the new layout.
     */
    fun withLayout(newLayout: ViewportLayout): OverlayPreset {
        val resizedViewports = (0 until newLayout.paneCount).map { i ->
            viewports.getOrNull(i) ?: ViewportConfig()
        }
        return copy(
            layout = newLayout,
            dividers = newLayout.defaultDividers(),
            viewports = resizedViewports
        )
    }

    /**
     * Pads / trims [viewports] and [dividers] so their counts match [layout],
     * **keeping** existing values (unlike [withLayout], which resets dividers).
     * Use after loading a preset so a hand-edited or stale file can't render a
     * structurally inconsistent layout.
     */
    fun normalized(): OverlayPreset {
        val defaults = layout.defaultDividers()
        val fixedViewports = (0 until layout.paneCount).map { i ->
            viewports.getOrNull(i) ?: ViewportConfig()
        }
        val fixedDividers = (0 until layout.dividerCount).map { i ->
            dividers.getOrNull(i) ?: defaults.getOrElse(i) { 0.5f }
        }
        return copy(viewports = fixedViewports, dividers = fixedDividers)
    }
}
