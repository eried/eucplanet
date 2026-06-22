package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R

/**
 * Chooser shown when the rider taps Share on a trip, three things to do with
 * the recorded ride, plus Cancel.
 */
@Composable
fun TripActionDialog(
    onShareFile: () -> Unit,
    onViewOnline: () -> Unit,
    onReplay: () -> Unit,
    onDismiss: () -> Unit,
    dropboxLinked: Boolean = false,
    onShareViaDropbox: () -> Unit = {},
    onInspectOnline: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                TripActionRow(
                    Icons.Default.Share,
                    stringResource(R.string.trip_action_share_file),
                    stringResource(R.string.trip_action_share_file_desc)
                ) { onDismiss(); onShareFile() }
                if (dropboxLinked) {
                    // Two extras when Dropbox is linked: upload the trip
                    // there and share the public link, or hand the same
                    // link to the eucviewer for in-browser inspection.
                    // Both reuse the upload step, so the cost is one
                    // round-trip to Dropbox.
                    TripActionRow(
                        Icons.Default.CloudUpload,
                        stringResource(R.string.trip_action_share_dropbox),
                        stringResource(R.string.trip_action_share_dropbox_desc)
                    ) { onDismiss(); onShareViaDropbox() }
                    TripActionRow(
                        Icons.Default.Public,
                        stringResource(R.string.trip_action_inspect_online),
                        stringResource(R.string.trip_action_inspect_online_desc)
                    ) { onDismiss(); onInspectOnline() }
                }
                TripActionRow(
                    Icons.Default.History,
                    stringResource(R.string.trip_action_replay),
                    stringResource(R.string.trip_action_replay_desc)
                ) { onDismiss(); onReplay() }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun TripActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
