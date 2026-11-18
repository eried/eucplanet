package com.eried.eucplanet.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import com.eried.eucplanet.ui.theme.appColors

/**
 * Colours for a plain menu row. A row that cannot run greys to the theme's
 * disabled text, the same strength as every other greyed control.
 */
@Composable
fun appMenuItemColors(): MenuItemColors {
    val c = MaterialTheme.appColors
    return MenuDefaults.itemColors(
        textColor = c.textPrimary,
        leadingIconColor = c.textSecondary,
        trailingIconColor = c.textSecondary,
        disabledTextColor = c.textDisabled,
        disabledLeadingIconColor = c.textDisabled,
        disabledTrailingIconColor = c.textDisabled,
    )
}
