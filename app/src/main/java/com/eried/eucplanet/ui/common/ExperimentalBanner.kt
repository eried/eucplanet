package com.eried.eucplanet.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.AccentOrange
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Thin orange strip at the top of the dashboard inviting users to report issues
 * on the multi-wheel build. Click flow:
 *
 *   first click  → explainer dialog → [Report an issue] opens GitHub Issues form
 *                                   → [Cancel] keeps the dialog reappearing on next click
 *   second+ click (after Report) → opens GitHub form directly, no dialog
 *
 * The GitHub URL targets `wheel_report.yml` (the issue template in this repo)
 * with prefilled `app_version`, plus optional `wheel` and `firmware` from the
 * connected wheel.
 */
@Composable
fun ExperimentalBanner(
    state: ExperimentalBannerState,
    detectedWheelName: String? = null,
    detectedFirmware: String? = null
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentOrange.copy(alpha = 0.18f))
            .clickable {
                if (state.explainerSeen) {
                    openIssueForm(context, detectedWheelName, detectedFirmware)
                } else {
                    showDialog = true
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Science,
            contentDescription = null,
            tint = AccentOrange,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.experimental_banner),
            style = MaterialTheme.typography.bodySmall,
            color = AccentOrange
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.experimental_dialog_title)) },
            text = { Text(stringResource(R.string.experimental_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    state.explainerSeen = true
                    showDialog = false
                    openIssueForm(context, detectedWheelName, detectedFirmware)
                }) {
                    Text(stringResource(R.string.experimental_dialog_report))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Build the GitHub Issues URL with the wheel-report template and any prefill
 * values we have. GitHub Issue Forms accept URL-encoded `?param=value` for each
 * field id declared in the template's `body:` list. Unknown params are ignored
 * by GitHub, so we always include `app_version` and add `wheel` + `firmware`
 * only when the values are valid template options or non-empty strings.
 */
private fun openIssueForm(
    context: android.content.Context,
    wheelName: String?,
    firmware: String?
) {
    val params = StringBuilder("template=wheel_report.yml")
    params.append("&app_version=").append(urlEncode(BuildConfig.VERSION_NAME))

    val wheelOption = matchWheelTemplateOption(wheelName)
    if (wheelOption != null) {
        params.append("&wheel=").append(urlEncode(wheelOption))
    }
    if (!firmware.isNullOrBlank()) {
        params.append("&firmware=").append(urlEncode(firmware))
    }

    val url = "https://github.com/eried/eucplanet/issues/new?$params"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}

/**
 * Map the wheel name our parser surfaces (e.g., "InMotion V14 50GB") to the
 * exact option label in `wheel_report.yml`. Returns null if no match — the
 * field will be left blank and the user picks manually.
 */
private fun matchWheelTemplateOption(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val n = name.lowercase()
    return when {
        "v14 50gb" in n -> "InMotion V14 (50GB)"
        "v14 50s"  in n -> "InMotion V14 (50S)"
        "v13 pro"  in n -> "InMotion V13 Pro"
        "v13"      in n -> "InMotion V13"
        "v12 hs"   in n -> "InMotion V12 HS"
        "v12 ht"   in n -> "InMotion V12 HT"
        "v12 pro"  in n -> "InMotion V12 Pro"
        "v12s"     in n -> "InMotion V12S"
        "v11y"     in n -> "InMotion V11Y"
        "v11"      in n -> "InMotion V11"
        "v10f"     in n -> "InMotion V10F"
        "v10"      in n -> "InMotion V10"
        "v9"       in n -> "InMotion V9"
        else -> null
    }
}

private fun urlEncode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8.name())
