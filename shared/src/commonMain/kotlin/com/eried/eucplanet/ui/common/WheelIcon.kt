package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * An electric unicycle, side on: the tyre with its hub, the seat on a short
 * post and the pedals across the lower half. Material has no unicycle, and its
 * bicycle and motorcycle icons call the wheel something it is not.
 *
 * Drawn on Material's 24 grid in black like the stock icons, so `Icon` tints it
 * from the theme the same way.
 */
val WheelIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Wheel",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Tyre, a ring.
        addPath(
            pathData = addPathNodes("M12 7.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13zm0 2.2a4.3 4.3 0 1 0 0 8.6a4.3 4.3 0 1 0 0-8.6z"),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        // Hub, seat post, seat and pedals.
        listOf(
            "M12 12.6a1.4 1.4 0 1 1 0 2.8a1.4 1.4 0 1 1 0-2.8z",
            "M12 4.6a1 1 0 0 1 1 1v2a1 1 0 0 1-2 0v-2a1 1 0 0 1 1-1z",
            "M10 3.6h4a1 1 0 0 1 0 2h-4a1 1 0 0 1 0-2z",
            "M9.5 16.6h5a.9.9 0 0 1 0 1.8h-5a.9.9 0 0 1 0-1.8z",
        ).forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()
}
