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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors

/**
 * The Navigation section's "Offline areas" row with the space the downloads
 * use, and the areas screen it opens.
 */
@Composable
fun OfflineAreasRow() {
    var offlineAreasOpen by rememberSaveable { mutableStateOf(false) }
    val offlineStore = org.koin.compose.koinInject<com.eried.eucplanet.nav.offline.OfflineTileStore>()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Counted again each time the areas screen closes.
    val offlineUsed by androidx.compose.runtime.produceState(0L, offlineAreasOpen) {
        if (!offlineAreasOpen) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { offlineStore.usedBytes() }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { offlineAreasOpen = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = stringResource(R.string.offline_data_open)
        Text(
            if (offlineUsed > 0) "$title (${android.text.format.Formatter.formatShortFileSize(ctx, offlineUsed)})" else title,
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
        com.eried.eucplanet.ui.settings.offline.OfflineDataDialog(onDismiss = { offlineAreasOpen = false })
    }
}
