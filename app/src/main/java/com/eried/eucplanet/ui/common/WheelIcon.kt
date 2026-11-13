package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * An electric unicycle, side on: the shell, rounded on top and following the
 * tyre below, with the tyre showing through, the pedals across it with the hub
 * half under them, and a cushioned seat sunk into the top. Material has no
 * unicycle, and its bicycle and motorcycle icons call the wheel something it
 * is not.
 *
 * Drawn on Material's 24 grid in black like the stock icons, so `Icon` tints it
 * from the theme the same way. Parts that touch overlap rather than share an
 * edge, so no hairline shows between them.
 */
val WheelIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Wheel",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Shell with the tyre opening.
        addPath(
            pathData = addPathNodes("M4.5 14.5V10a3 3 0 0 1 3-3h9a3 3 0 0 1 3 3v4.5a7.5 7.5 0 0 1-15 0zM12 9.5a5 5 0 1 0 0 10a5 5 0 1 0 0-10z"),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        // Hub, pedals and seat.
        listOf(
            "M8.1 14.9a3.9 3.9 0 0 1 7.8 0z",
            "M6.5 14.5h11a1 1 0 0 1 0 2h-11a1 1 0 0 1 0-2z",
            "M8 7.4q4-2.4 8 0v.4a.6 .6 0 0 1-.6 .6h-6.8a.6 .6 0 0 1-.6-.6z",
        ).forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()
}
