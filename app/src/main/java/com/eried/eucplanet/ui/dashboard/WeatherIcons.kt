package com.eried.eucplanet.ui.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Sun-behind-cloud entry icon for the weather flyout. The stock
 * material-icons-extended set predates Google's "partly cloudy day" symbol,
 * so this embeds the official Material Symbols path (fill style, 24 px
 * grid). Material Symbols draw in a 960-unit viewport with y running
 * -960..0, hence the group translation.
 */
private var cachedPartlyCloudy: ImageVector? = null

val PartlyCloudyDayIcon: ImageVector
    get() = cachedPartlyCloudy ?: ImageVector.Builder(
        name = "PartlyCloudyDay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        addGroup(translationY = 960f)
        addPath(
            pathData = addPathNodes(
                "M240-160q-66 0-113-47T80-320q0-66 47-113t113-47q48 0 88.5 26t58.5 " +
                    "71l10 23h24q42 0 70.5 29t28.5 71q0 42-29 71t-71 29H240Zm359-112q-4" +
                    "-63-45.5-109T449-438q-31-54-83.5-85.5T250-560q26-73 89-116.5T480-72" +
                    "0q100 0 170 70t70 170q0 65-32 120.5T599-272ZM440-760v-160h80v160h-8" +
                    "0Zm266 110-56-56 112-114 57 57-113 113Zm54 210v-80h160v80H760Zm2 30" +
                    "0L650-254l56-56 114 112-58 58ZM254-650 141-763l57-57 112 114-56 56Z"
            ),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
        clearGroup()
    }.build().also { cachedPartlyCloudy = it }
