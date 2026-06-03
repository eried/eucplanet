package com.eried.eucplanet.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Not supported on this platform" markers — informational badges that flag a
 * feature/control the active companion can't do (Garmin, HUD). The diagonal
 * slash is the universal "no". Colors come from the theme tokens (ink =
 * secondary text, slash = danger), so they track the active theme.
 */

/**
 * Text variant: a tiny boxed uppercase platform label (e.g. "GARMIN", "HUD")
 * struck through with a diagonal slash. Used where there's no good platform
 * icon — the label itself names the platform, so it's unambiguous.
 */
@Composable
fun PlatformUnsupportedTextBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    // One muted "disabled" color for the label, border, and slash — no red.
    val ink = MaterialTheme.appColors.textDisabled
    Box(
        modifier = modifier
            .border(1.dp, ink.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            color = ink,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        // Diagonal strike across the whole chip (bottom-left → top-right).
        Canvas(modifier = Modifier.matchParentSize()) {
            drawLine(
                color = ink,
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
                strokeWidth = 0.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Icon variant: a platform [icon] with a diagonal slash drawn across it. */
@Composable
fun PlatformUnsupportedBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val ink = MaterialTheme.appColors.textSecondary
    val slash = MaterialTheme.appColors.statusDanger
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = contentDescription, tint = ink, modifier = Modifier.size(size))
        Canvas(Modifier.size(size)) {
            val pad = this.size.minDimension * 0.06f
            drawLine(
                color = slash,
                start = Offset(pad, this.size.height - pad),
                end = Offset(this.size.width - pad, pad),
                strokeWidth = this.size.minDimension * 0.12f,
                cap = StrokeCap.Round,
            )
        }
    }
}
