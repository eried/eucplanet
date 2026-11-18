package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.ui.settings.NumberFieldWithDefault
import com.eried.eucplanet.ui.theme.appColors

/**
 * Convert to stops, a dialog like Optimize stop order: the rider picks how
 * many stops the track becomes and sees how far their straight line strays.
 * A dialog rides above the keyboard, where a docked panel was covered by it.
 */
@Composable
fun UnlockTrackDialog(
    state: RouteBuilderViewModel.UnlockState,
    imperial: Boolean,
    onCount: (Int) -> Unit,
    onCancel: () -> Unit,
    onUnlock: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val context = LocalContext.current
    // The rider counts stops, the ends and named places included.
    val fixed = state.line.size - state.added
    val buttons = ButtonDefaults.textButtonColors(contentColor = c.textButton)
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = c.dialog,
        titleContentColor = c.textPrimary,
        textContentColor = c.textPrimary,
        title = { Text(stringResource(R.string.nav_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.nav_unlock_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                // The settings shape: label inside the field, the recommended
                // count under it as the reset, the description on the right.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberFieldWithDefault(
                        value = state.line.size,
                        onValueChange = { onCount(it - fixed) },
                        range = fixed..(fixed + state.maxAdded),
                        default = fixed + state.recommendedAdded,
                        label = stringResource(R.string.nav_unlock_stops),
                        modifier = Modifier.weight(1.4f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.nav_unlock_off, NavFormat.distance(context, state.maxOffM, imperial)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUnlock, shape = RoundedCornerShape(12.dp), colors = buttons) {
                Text(stringResource(R.string.nav_unlock_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, shape = RoundedCornerShape(12.dp), colors = buttons) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
