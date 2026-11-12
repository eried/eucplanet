package com.eried.eucplanet.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.platform.PlatformFeature
import com.eried.eucplanet.platform.available
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors

/*
 * The one look and the one message for a feature this phone does not have
 * yet (PlatformFeature): muted text from the theme and a short snackbar.
 * The text is held in a val so the settings search, which reads the strings
 * a section draws, does not offer "not yet" as something to search for.
 */
private val NOT_YET = Res.string.platform_feature_not_yet

/** "Not on iPhone yet", for a caller that builds its own message. */
@Composable
fun notYetText(): String = stringResource(NOT_YET)

/** A tap on a missing feature: the short snackbar, through [LocalSnackbar]. */
@Composable
fun rememberNotYetTap(): () -> Unit {
    val host = LocalSnackbar.current
    val scope = LocalSnackbarScope.current
    val message = stringResource(NOT_YET)
    return remember(host, scope, message) { { showSnackbar(host, scope, message) } }
}

/** A small muted pill, for the welcome tour's steps about a missing feature. */
@Composable
fun NotYetNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.appColors.surfaceVariant,
        contentColor = MaterialTheme.appColors.textSecondary,
    ) {
        Text(
            stringResource(NOT_YET),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.appColors.textSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Where a settings block of a missing [feature] would be: its name and
 * "Not on iPhone yet", muted, and a tap shows the snackbar. Draws nothing
 * where the feature is available, so Android is unchanged.
 */
@Composable
fun NotYetSettingsRow(feature: PlatformFeature) {
    if (feature.available) return
    val tap = rememberNotYetTap()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = tap)
            .padding(vertical = 10.dp),
    ) {
        Text(
            stringResource(feature.title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.appColors.textDisabled,
        )
        Text(
            stringResource(NOT_YET),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.appColors.textDisabled,
        )
    }
}
