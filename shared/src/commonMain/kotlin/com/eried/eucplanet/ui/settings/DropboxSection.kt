package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.platform.formatLocalDate
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors

/**
 * The Dropbox part of Settings → Backup: what it is, the link or the account
 * and last sync, Sync now / Unlink, and the progress of a running or pending
 * sync. Both phones draw it from here; each passes its own state and actions.
 */
@Composable
fun DropboxSection(
    linked: Boolean,
    account: String,
    lastSyncAt: Long,
    syncRunning: Boolean,
    showProgress: Boolean,
    progress: Pair<Int, Int>?,
    cancelling: Boolean,
    syncPending: Boolean,
    pendingCount: Int,
    syncTotal: Int,
    onSyncNow: () -> Unit,
    onUnlink: () -> Unit,
    onLink: () -> Unit,
    onStop: () -> Unit,
) {
    val dbxLinked = linked
    val dbxAccount = account
    val showDbxProgress = showProgress
    val dbxProgress = progress
    val dbxCancelling = cancelling
    val dbxSyncPending = syncPending
    val dbxPendingCount = pendingCount
    val dbxSyncTotal = syncTotal
        Text(
            stringResource(Res.string.dropbox_section_caption),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.appColors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        run {
            val url = stringResource(Res.string.dropbox_section_desc_url)
            val full = stringResource(Res.string.dropbox_section_desc, url)
            val urlStart = full.indexOf(url)
            val linkColor = MaterialTheme.appColors.link
            // When linked, the folder location flows straight after this same
            // sentence (no new line), with the path emphasized. Only the URL is
            // a tappable link, so tapping the folder name does nothing.
            val folderPath = stringResource(Res.string.dropbox_folder_path)
            val folderSentence = if (dbxLinked)
                " " + stringResource(Res.string.dropbox_folder_desc, folderPath) else ""
            val annotated = buildAnnotatedString {
                if (urlStart >= 0) {
                    append(full.substring(0, urlStart))
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://$url",
                            styles = TextLinkStyles(
                                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                            ),
                        )
                    ) { append(url) }
                    append(full.substring(urlStart + url.length))
                } else append(full)
                if (folderSentence.isNotEmpty()) {
                    val fi = folderSentence.indexOf(folderPath)
                    if (fi >= 0) {
                        append(folderSentence.substring(0, fi))
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(folderPath) }
                        append(folderSentence.substring(fi + folderPath.length))
                    } else append(folderSentence)
                }
            }
            Text(
                annotated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (dbxLinked) {
            val lastSyncBase = if (lastSyncAt > 0L) {
                stringResource(Res.string.dropbox_last_sync, formatLocalDate("dd MMM yyyy HH:mm", lastSyncAt))
            } else stringResource(Res.string.dropbox_never_synced)
            val lastSyncText = if (dbxAccount.isNotBlank())
                lastSyncBase + " " + stringResource(Res.string.dropbox_account_suffix, dbxAccount)
            else lastSyncBase
            Text(
                lastSyncText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = !syncRunning,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.cloud_retry_now)) }
                Button(
                    onClick = onUnlink,
                    enabled = !syncRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.statusDanger,
                        contentColor   = MaterialTheme.appColors.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.dropbox_unlink)) }
            }
            if (showDbxProgress) {
                SyncProgressWithCancel(
                    progress = dbxProgress,
                    cancelling = dbxCancelling,
                    onCancel = onStop,
                    label = if (dbxProgress == null && !dbxCancelling)
                        stringResource(Res.string.dropbox_checking) else null,
                )
            }
            // Quiet persistent "still syncing" row: trips are pending and the
            // background worker keeps retrying. Only shown when the transient
            // foreground progress bar above is NOT up, so the two never stack.
            if (dbxSyncPending && !showDbxProgress) {
                // Reuse the same bar + Cancel component as the foreground sync so
                // leaving the app just swaps the live progress for this pending
                // state without the controls changing shape. With a known batch
                // total, show the identical determinate "X of Y" bar (done =
                // total - remaining); otherwise fall back to the plain label.
                SyncProgressWithCancel(
                    progress = if (dbxSyncTotal > 0)
                        (dbxSyncTotal - dbxPendingCount).coerceIn(0, dbxSyncTotal) to dbxSyncTotal
                    else null,
                    cancelling = false,
                    onCancel = onStop,
                    label = if (dbxSyncTotal > 0) null else stringResource(Res.string.dropbox_syncing),
                )
            }
        } else {
            // Half-width to match the Sync/Unlink row that replaces this
            // button once the rider links Dropbox.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onLink,
                    enabled = !syncRunning,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { com.eried.eucplanet.ui.common.SearchableText(stringResource(Res.string.dropbox_link)) }
                Spacer(Modifier.weight(1f))
            }
        }
}

/**
 * Progress row for a running foreground sync (folder or Dropbox) plus a Cancel
 * button. Cancel is cooperative: the current file finishes, the caption and
 * button switch to "Stopping..." and the button disables so it can't fire
 * twice. Determinate when done/total is known, else an indeterminate bar with
 * the checking/stopping caption.
 */
@Composable
fun SyncProgressWithCancel(
    progress: Pair<Int, Int>?,
    cancelling: Boolean,
    onCancel: () -> Unit,
    // When set, replaces the default status text (e.g. the background
    // "Syncing N trips…" pending state reuses this component with its own label).
    label: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (progress != null) {
                val (done, total) = progress
                val fraction = if (total > 0) done.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    // Drop the Material3 end gap + stop-indicator dot; they read
                    // as a stray glitched segment/dot at the end of the bar.
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    label ?: stringResource(Res.string.sync_progress, done, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    gapSize = 0.dp,
                )
                Text(
                    label ?: stringResource(if (cancelling) Res.string.sync_stopping else Res.string.sync_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Static half-width Cancel on the right, matching the other action
        // buttons. weight(1f) fixes its width so it no longer resizes between
        // "Cancel" and "Stopping...".
        androidx.compose.material3.OutlinedButton(
            onClick = onCancel,
            enabled = !cancelling,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                stringResource(if (cancelling) Res.string.sync_stopping else Res.string.sync_cancel),
                maxLines = 1,
            )
        }
    }
}
