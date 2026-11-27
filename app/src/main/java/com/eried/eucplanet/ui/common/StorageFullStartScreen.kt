package com.eried.eucplanet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors

/**
 * What the app shows when it was opened with no storage left and could not
 * open its database.
 *
 * It used to close on every start then. Shown instead of the dashboard, not
 * over it: with the database shut every list is empty, and "0 trips" on a
 * full phone reads as everything lost when nothing is. The wording is the
 * storage warning's own, which says the rides are safe.
 *
 * [onFreeSpace] opens the phone's storage settings. [onRetry] looks again;
 * the caller also looks again by itself when the rider comes back.
 * [stillFull] is the answer to a retry that found no room: the app's notices
 * are drawn by the main screen, which is not there yet.
 */
@Composable
fun StorageFullStartScreen(stillFull: Boolean, onFreeSpace: () -> Unit, onRetry: () -> Unit) {
    val c = MaterialTheme.appColors
    Surface(modifier = Modifier.fillMaxSize(), color = c.appBackground) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.SdStorage,
                contentDescription = null,
                tint = c.statusWarn,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.warnings_storage_full_title),
                color = c.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.warnings_storage_full_body),
                color = c.textSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            FixButton(onClick = onFreeSpace)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.crews_tryagain), color = c.link)
            }
            if (stillFull) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.warnings_storage_full_title),
                    color = c.statusDanger,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
