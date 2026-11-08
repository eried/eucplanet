package com.eried.eucplanet.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.app.IosAppGraph
import com.eried.eucplanet.app.IosDropboxSync
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.settings.DropboxSection
import com.eried.eucplanet.ui.settings.SyncConflictDialog
import com.eried.eucplanet.ui.theme.appColors

/**
 * Stand-in for Settings → Backup until the Settings screen moves: the shared
 * Dropbox block, plus a short read-out of what the synced settings.json holds
 * so a sync can be checked by eye. The read-out goes when Settings arrives.
 */
@Composable
fun BackupPane() {
    val settings by IosAppGraph.settings.settings.collectAsState(AppSettings())
    val running by IosDropboxSync.running.collectAsState()
    val conflict by IosDropboxSync.conflict.collectAsState()
    val firstSettings by IosDropboxSync.firstSettings.collectAsState()
    val progress by IosDropboxSync.progress.collectAsState()
    val cancelling by IosDropboxSync.cancelling.collectAsState()
    val conflictCount by IosDropboxSync.conflictCount.collectAsState()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DropboxSection(
            linked = settings.dropboxAccessToken.isNotBlank(),
            account = settings.dropboxAccountLabel,
            lastSyncAt = settings.dropboxLastSyncAt,
            syncRunning = running,
            showProgress = running,
            progress = progress,
            cancelling = cancelling,
            syncPending = settings.dropboxSyncPending,
            pendingCount = settings.dropboxPendingCount,
            syncTotal = settings.dropboxSyncTotal,
            onSyncNow = { IosDropboxSync.syncNow() },
            onUnlink = { IosDropboxSync.unlink() },
            onLink = { IosDropboxSync.link() },
            onStop = { IosDropboxSync.stop() },
        )
        Text(
            stringResource(Res.string.section_cloud_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.appColors.textPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        val c = MaterialTheme.appColors.textSecondary
        Text("units: ${settings.unitSpeed} / ${settings.unitDistance} / ${settings.unitTemp}", color = c)
        Text("theme: ${settings.activeThemeName}", color = c)
        Text("voice: ${settings.voiceEnabled}, every ${settings.voiceIntervalSeconds} s", color = c)
        val alarmCount by produceState(0, settings) { value = IosAppGraph.alarms.getAll().size }
        Text("alarm rules: $alarmCount", color = c)
    }
    if (conflict) {
        SyncConflictDialog(
            count = conflictCount ?: 1,
            isDropbox = true,
            firstSettings = firstSettings,
            onChoice = { IosDropboxSync.resolve(it) },
            onCancel = { IosDropboxSync.stop() },
        )
    }
}
