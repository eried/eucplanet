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
import androidx.compose.material.icons.filled.ContentCopy
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
 * Chooser shown when the rider taps Share on a trip. Four "always there"
 * items: Share file, Share via Dropbox, Open in eucviewer, Copy eucviewer
 * link, Replay. The three Dropbox-dependent items stay visible even when
 * Dropbox is not linked — they're greyed and their subtitle becomes
 * "Link Dropbox in settings to enable this" so the rider sees the
 * feature exists and how to turn it on.
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
    onCopyOnlineLink: () -> Unit = {},
) {
    val disabledHint = stringResource(R.string.trip_action_dropbox_disabled)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                TripActionRow(
                    Icons.Default.Share,
                    stringResource(R.string.trip_action_share_file),
                    stringResource(R.string.trip_action_share_file_desc)
                ) { onDismiss(); onShareFile() }
                TripActionRow(
                    Icons.Default.CloudUpload,
                    stringResource(R.string.trip_action_share_dropbox),
                    if (dropboxLinked) stringResource(R.string.trip_action_share_dropbox_desc) else disabledHint,
                    enabled = dropboxLinked,
                ) { onDismiss(); onShareViaDropbox() }
                TripActionRow(
                    Icons.Default.Public,
                    stringResource(R.string.trip_action_inspect_online),
                    if (dropboxLinked) stringResource(R.string.trip_action_inspect_online_desc) else disabledHint,
                    enabled = dropboxLinked,
                ) { onDismiss(); onInspectOnline() }
                TripActionRow(
                    Icons.Default.ContentCopy,
                    stringResource(R.string.trip_action_copy_link),
                    if (dropboxLinked) stringResource(R.string.trip_action_copy_link_desc) else disabledHint,
                    enabled = dropboxLinked,
                ) { onDismiss(); onCopyOnlineLink() }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (enabled) it.clickable(onClick = onClick) else it }
        .padding(vertical = 12.dp)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}
