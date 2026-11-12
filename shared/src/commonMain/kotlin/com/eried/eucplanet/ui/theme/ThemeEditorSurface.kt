package com.eried.eucplanet.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * What the floating theme editor needs from its platform: the size of the
 * window it floats over, so it never leaves the screen, and one pixel of that
 * window for the color identifier. Android reads its view and PixelCopy
 * (`AndroidThemeEditorSurface`), iOS the app's recorded root layer
 * (`app/IosThemeEditor.kt`).
 */
interface ThemeEditorSurface {
    /** The window's width in pixels, read each time so a rotation is followed. */
    val width: Int

    /** The window's height in pixels. */
    val height: Int

    /**
     * Reads the window pixel at ([x], [y]), window coordinates already inside
     * the bounds, and hands its color to [onColor] on the main thread. Nothing
     * is called when the pixel cannot be read.
     */
    fun samplePixel(x: Int, y: Int, onColor: (Color) -> Unit)
}
