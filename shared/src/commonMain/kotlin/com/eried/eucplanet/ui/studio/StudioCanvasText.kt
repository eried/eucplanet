package com.eried.eucplanet.ui.studio

import androidx.compose.ui.graphics.drawscope.DrawScope

// Text the overlay elements stamp straight onto their canvas, where a Text
// composable cannot go. Android draws with the platform Paint as it always
// has; iOS draws the same text with Skia.

/** A radar target's distance, bold white, centred on ([x], [y]). */
expect fun DrawScope.drawRadarDistanceLabel(text: String, x: Float, y: Float, textSize: Float)

/**
 * The map provider's credit, bottom right with a soft shadow: [full] when it
 * fits the width, [short] when not, shrinking the type only as a last resort.
 */
expect fun DrawScope.drawMapCredit(full: String, short: String)

/** `android.graphics.Color.colorToHSV`: [argb] as hue (0..360), saturation and value (0..1). */
expect fun colorToHsv(argb: Int): FloatArray
