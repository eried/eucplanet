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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            highlightMatches(text, LocalSettingsSearchQuery.current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
