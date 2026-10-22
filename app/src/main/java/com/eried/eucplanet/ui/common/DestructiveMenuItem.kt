package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.ui.theme.appColors

/**
 * A menu row that deletes or forgets something: red text with a bin at the
 * end, like every other meaningful menu icon. Plain rows carry no icon.
 * Greyed like the other items when it cannot run.
 */
@Composable
fun DestructiveMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.appColors
    val off = c.textSecondary.copy(alpha = 0.6f)
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = c.statusDanger,
            trailingIconColor = c.statusDanger,
            disabledTextColor = off,
            disabledTrailingIconColor = off,
        ),
        onClick = onClick,
    )
}
