package com.eried.eucplanet.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.ui.theme.appColors

/**
 * One row of a tools dialog: a leading icon, the action in one line, and the
 * detail under it.
 *
 * This is the app's shape for "a dialog that is a short list of things a
 * button can mean". Trip tools drew it first; the navigator's Share button
 * uses the same rows so the two read as one pattern rather than as two
 * separately invented menus.
 *
 * Disabled rows stay in place rather than disappearing: a list that changes
 * shape between two openings a few seconds apart is harder to aim at, and the
 * subtitle is where the row says what is missing. Only the icon and the title
 * grey out, the subtitle is already the muted colour.
 */
@Composable
internal fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val appColors = MaterialTheme.appColors
    // appColors.primary is what the theme maps into colorScheme.primary, so
    // this is the same tint the row has always had, read from the app palette.
    val iconTint = if (enabled) appColors.primary else appColors.textSecondary
    val titleColor = if (enabled) appColors.textPrimary else appColors.textSecondary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = titleColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary)
        }
    }
}
