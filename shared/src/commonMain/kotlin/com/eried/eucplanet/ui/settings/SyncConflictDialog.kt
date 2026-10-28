package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*

/**
 * [count] things changed on both sides of a sync: take the backup's copy, this
 * phone's, skip them, or stop. [firstSettings] is the first sync of settings
 * with this Dropbox, which asks the question in its own words.
 */
@Composable
fun SyncConflictDialog(
    count: Int,
    isDropbox: Boolean,
    firstSettings: Boolean,
    onChoice: (SyncChoice) -> Unit,
    onCancel: () -> Unit,
) {
    val syncConflict = count
    val bodyRes = if (isDropbox) Res.string.sync_conflict_body_dropbox else Res.string.sync_conflict_body
    val pullRes = if (isDropbox) Res.string.sync_conflict_dropbox else Res.string.sync_conflict_folder
    val pushRes = if (isDropbox) Res.string.sync_conflict_app_dropbox else Res.string.sync_conflict_app
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(Res.string.sync_conflict_title)) },
        text = {
            val countText = stringResource(bodyRes, syncConflict)
            Text(
                if (isDropbox && firstSettings) {
                    val ask = stringResource(Res.string.sync_first_settings)
                    // Settings alone: the question says it all. With trips or
                    // themes in the prompt too, the count still follows.
                    if (syncConflict == 1) ask else "$ask\n\n$countText"
                } else countText
            )
        },
        confirmButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { onChoice(SyncChoice.FOLDER) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(pullRes)) }
                Button(
                    onClick = { onChoice(SyncChoice.APP) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(pushRes)) }
                Button(
                    onClick = { onChoice(SyncChoice.IGNORE) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.sync_conflict_ignore)) }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(Res.string.action_cancel)) }
            }
        }
    )
}
