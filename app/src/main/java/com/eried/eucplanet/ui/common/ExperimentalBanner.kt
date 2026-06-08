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
import androidx.compose.material.icons.filled.Warning
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
import com.eried.eucplanet.ui.theme.appColors
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Thin orange strip on the dashboard inviting users to report issues for
 * preliminary-supported wheels. Hidden when no wheel is connected and when the
 * connected wheel is the verified V14 family. Click flow:
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
    // Only show for wheels other than the verified V14 family. A null name
    // (disconnected) hides the banner, there's nothing to report yet.
    if (!isPreliminaryWheel(detectedWheelName)) return

    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    val bannerAccent = MaterialTheme.appColors.statusWarn

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bannerAccent.copy(alpha = 0.18f))
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
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = bannerAccent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.experimental_banner),
            style = MaterialTheme.typography.bodySmall,
            color = bannerAccent
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
 * exact option label in `wheel_report.yml`. Returns null if no match, the
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
        "p6"       in n -> "InMotion P6"
        else -> null
    }
}

private fun urlEncode(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8.name())

/**
 * Model-name fragments for wheels confirmed on real hardware - the author's
 * V14 / P6, plus the rider-tested wheels (see README + in-app About → Credits).
 * Matching is a case-insensitive substring of the resolved model name, so it
 * keys off distinctive tokens ("Oryx", "Lynx S", "16X") rather than the whole
 * name. It can only fail safe: an unmatched tested wheel still shows the banner
 * (conservative), and the tokens are distinct enough not to exempt an untested
 * sibling (e.g. "Lynx S" doesn't match a plain "Lynx", "16X" doesn't match "KS-16").
 */
private val VERIFIED_WHEEL_TOKENS = listOf(
    "V14", "P6",                 // InMotion (author-verified)
    "Oryx", "Lynx S",            // Veteran (rider-tested)
    "Mten3", "EX30", "E20",      // Begode (rider-tested)
    "16X",                       // KingSong KS-16X (rider-tested)
)

/**
 * Returns true for wheels we treat as preliminary: a connected wheel whose
 * resolved model isn't in the verified/rider-tested set above. Anything else
 * trips the banner so the user knows to file a wheel report if values look off.
 * Disconnected (null name) → hidden too.
 */
private fun isPreliminaryWheel(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return VERIFIED_WHEEL_TOKENS.none { name.contains(it, ignoreCase = true) }
}
