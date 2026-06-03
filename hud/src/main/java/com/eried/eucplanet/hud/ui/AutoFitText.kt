package com.eried.eucplanet.hud.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * Drop-in [Text] replacement that auto-shrinks its font size to fit the
 * available width (or width + height) on a single line. Designed for the
 * HUD where every screen has a known footprint and the rider's data can
 * grow long enough to wrap (e.g. "1234.5 km/h" instead of "0 km/h").
 *
 * Implementation: one [rememberTextMeasurer] pass at the requested
 * [targetSize], compute the scale that brings the rendered width down
 * to the available constraint, render the [Text] at the resulting size.
 * No recomposition loop; the iterative "render then shrink then render
 * again" pattern is avoided because Compose flickers and double-charges
 * layout work on each retry.
 *
 * Notes:
 *  - `softWrap = false` + `maxLines = 1` so the text is GUARANTEED to
 *    be one row; the auto-fit only fights horizontal overflow.
 *  - `minSize` caps the shrink so a pathologically long string can
 *    still ellipsize instead of being unreadable. Defaults to 8.sp,
 *    which is the smallest legible text on a 480p HUD panel.
 *  - The measured width includes letter spacing and font metrics from
 *    [style], so a Monospace digit font shrinks differently from a
 *    proportional one. Pass the actual style you'll render with.
 */
@Composable
fun AutoFitText(
    text: String,
    targetSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    minSize: TextUnit = 8.sp,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val style = remember(targetSize, fontWeight, fontFamily) {
            TextStyle(
                fontSize = targetSize,
                fontWeight = fontWeight,
                fontFamily = fontFamily
            )
        }
        // Measure at the requested target size, NO width constraint, single
        // line. Compare the rendered width to what's actually available.
        val measured = remember(text, style, constraints.maxWidth) {
            measurer.measure(
                text = text,
                style = style,
                softWrap = false,
                maxLines = 1,
                constraints = androidx.compose.ui.unit.Constraints()
            )
        }
        // TextUnit doesn't implement Comparable directly so the coerce
        // happens on the underlying .value (sp scalar) instead.
        val fittedSize: TextUnit = remember(measured, constraints.maxWidth, targetSize, minSize) {
            val avail = constraints.maxWidth
            if (avail <= 0 || measured.size.width <= avail) targetSize
            else {
                val scale = avail.toFloat() / measured.size.width.toFloat()
                val sized = targetSize.value * scale
                kotlin.math.max(sized, minSize.value).sp
            }
        }
        Text(
            text = text,
            fontSize = fittedSize,
            color = color,
            fontWeight = fontWeight ?: FontWeight.Normal,
            fontFamily = fontFamily,
            textAlign = textAlign,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
