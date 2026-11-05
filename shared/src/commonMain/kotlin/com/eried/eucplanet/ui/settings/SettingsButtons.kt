package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Section-action button styled to match the "Synchronize all" button in the
 * cloud trips section: a half-width Button sitting on the left half of a
 * full-width Row, with an empty Spacer on the right. Shared across the
 * Integration tab (Flic / External GPS Start scan + Stop scan), the Alarms
 * tab (New alarm) and any other "kick off a thing" action in the
 * settings, so they all read as the same kind of action at a glance.
 *
 * The half-width Row pattern is preferred over a fixed-dp Button because
 * it scales with the screen, looks proportional on small phones AND
 * tablets without needing a separate width per breakpoint.
 */
@Composable
fun LeftAlignedScanButton(
    label: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = if (containerColor != null)
        ButtonDefaults.buttonColors(containerColor = containerColor)
    else ButtonDefaults.buttonColors()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = colors,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (leadingIcon != null) {
                androidx.compose.material3.Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            com.eried.eucplanet.ui.common.SearchableText(label)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
