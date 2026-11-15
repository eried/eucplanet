package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.nav.offline.OfflineTileStore
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.offline_data_open
import com.eried.eucplanet.ui.settings.offline.OfflineDataHost
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * The Navigation section's "Offline areas" row with the space the downloads
 * use, and the areas screen it opens ([dialog], which each phone builds
 * with its view model and [host]). [openAtStart] opens it straight away
 * (a launch hook for scripted runs).
 */
@Composable
fun OfflineAreasRow(
    store: OfflineTileStore,
    host: OfflineDataHost,
    openAtStart: Boolean = false,
    dialog: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    var offlineAreasOpen by rememberSaveable { mutableStateOf(openAtStart) }
    // Counted again each time the areas screen closes.
    val offlineUsed by produceState(0L, offlineAreasOpen) {
        if (!offlineAreasOpen) {
            value = withContext(Dispatchers.IO) { store.usedBytes() }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { offlineAreasOpen = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = stringResource(Res.string.offline_data_open)
        Text(
            if (offlineUsed > 0) "$title (${host.formatSize(offlineUsed)})" else title,
            color = MaterialTheme.appColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.appColors.textSecondary,
        )
    }
    if (offlineAreasOpen) {
        dialog { offlineAreasOpen = false }
    }
}
