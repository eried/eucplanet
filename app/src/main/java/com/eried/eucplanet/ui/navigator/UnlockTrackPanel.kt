package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.ui.settings.NumberUpDown
import com.eried.eucplanet.ui.theme.appColors

/**
 * Unlock track, docked where the stops panel sits so the map stays in view:
 * the rider picks how many stops the track becomes and watches the straight
 * line through them (the gold dashed preview) against the track itself.
 */
@Composable
fun UnlockTrackPanel(
    state: RouteBuilderViewModel.UnlockState,
    imperial: Boolean,
    onCount: (Int) -> Unit,
    onCancel: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.appColors
    val context = LocalContext.current
    // The rider counts stops, the ends and named places included.
    val fixed = state.line.size - state.added
    val buttons = ButtonDefaults.textButtonColors(contentColor = c.textButton)
    Surface(modifier = modifier.fillMaxWidth(), color = c.dialog, contentColor = c.textPrimary) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.nav_unlock_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.nav_unlock_warning),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_unlock_stops), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.nav_unlock_off, NavFormat.distance(context, state.maxOffM, imperial)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                NumberUpDown(
                    value = state.line.size,
                    onValueChange = { onCount(it - fixed) },
                    range = fixed..(fixed + state.maxAdded),
                    modifier = Modifier.weight(1.4f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, shape = RoundedCornerShape(12.dp), colors = buttons) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = onUnlock, shape = RoundedCornerShape(12.dp), colors = buttons) {
                    Text(stringResource(R.string.nav_unlock_confirm))
                }
            }
        }
    }
}
