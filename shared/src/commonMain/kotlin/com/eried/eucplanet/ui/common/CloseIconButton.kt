package com.eried.eucplanet.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors

/**
 * The X in the corner of a window that has no button row of its own.
 *
 * Every dialog, sheet and full-screen overlay needs a way out the rider can see.
 * Android has the system back, iPhone does not, so a window that only closes on
 * back is a dead end there. A window with a button row takes Cancel or Close in
 * it; a window without one (a scanner card, a side panel) puts this at the end
 * of its header.
 *
 * The tint defaults to the primary text token, which reads on the dialog and
 * header surfaces in the dark, light and custom themes.
 */
@Composable
fun CloseIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.appColors.textPrimary,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(Res.string.action_close),
            tint = tint,
        )
    }
}
