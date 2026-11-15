package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The image side of the user-image element and the map's rider photo. Picked
 * images are embedded base64 inside the preset `.json` so a preset is fully
 * portable; an optional colour key turns a chosen colour transparent, handy for
 * clipart on a flat background. Android decodes with BitmapFactory; iOS with
 * Skia.
 */
expect object StudioImages {
    /** A preset's base64 image ([com.eried.eucplanet.hud.protocol.OverlayElement.imageData]), or null. */
    fun decode(data: String): ImageBitmap?

    /**
     * A copy of [src] with every pixel within [tolerance] of [key] turned fully
     * transparent. [tolerance] is 0..1 over the RGB cube diagonal.
     */
    fun applyChromaKey(src: ImageBitmap, key: Color, tolerance: Float): ImageBitmap

    /** A `data:image/...;base64,` url (the rider's marker photo), or null. */
    fun decodeDataUrl(url: String): ImageBitmap?
}
