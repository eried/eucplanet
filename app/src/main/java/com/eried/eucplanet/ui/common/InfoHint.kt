package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// Global hint typography:
//  - Always [MaterialTheme.typography.bodySmall]
//  - [MaterialTheme.colorScheme.onSurfaceVariant] color
//  - No italics, italics read as a separate dialect of text and made the UI
//    feel decorative rather than informative. Plain bodySmall is the rule.
//  - Strings end with a period (or locale equivalent, '.' / '。'). Enforced in
//    the strings.xml files; this composable doesn't add or strip punctuation.

@Composable
fun InfoHint(
    text: String,
    modifier: Modifier = Modifier,
    // Overrides for the rare hint that is not neutral information. The defaults
    // are the global rule above; pass these only when the icon genuinely
    // carries meaning (a warning the rider must not miss, a state the app
    // cannot read), not for decoration.
    icon: ImageVector = Icons.Outlined.Info,
    tint: Color = Color.Unspecified,
) {
    val ink = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp)
        )
        Text(
            highlightMatches(text, LocalSettingsSearchQuery.current),
            style = MaterialTheme.typography.bodySmall,
            color = ink
        )
    }
}

@Composable
fun HintText(
    text: String,
    modifier: Modifier = Modifier,
    // Kept for source-compat, previously toggled between bodyMedium and bodySmall.
    // Per the global hint rule we always render bodySmall now, so this flag is a no-op.
    @Suppress("UNUSED_PARAMETER") small: Boolean = false,
    textAlign: TextAlign? = null
) {
    Text(
        highlightMatches(text, LocalSettingsSearchQuery.current),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign
    )
}
