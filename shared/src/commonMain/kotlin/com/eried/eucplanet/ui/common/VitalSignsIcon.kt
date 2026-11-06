package com.eried.eucplanet.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols "vital_signs" glyph, Apache 2.0, as app/res's
 * ic_vital_signs.xml draws it: the same path, moved down 960 because
 * Material Symbols SVGs use a viewBox of 0 -960 960 960.
 */
val VitalSignsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "VitalSigns",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).addGroup(translationY = 960f)
        .addPath(
            pathData = PathParser().parsePathString(
                "M326,-171q-15,-11 -22,-28l-92,-241H40v-80h228l92,244 184,-485q7,-17 22,-28t34,-11q19,0 34,11t22,28l92,241h172v80H692l-92,-244 -184,485q-7,17 -22,28t-34,11q-19,0 -34,-11Z"
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
        .clearGroup()
        .build()
}
