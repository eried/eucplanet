package com.eried.eucplanet.ui.settings.eucstats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.ui.common.BulletPoint
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.settings.LocalSettingsHost
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors

// ---- Step constants ---------------------------------------------------------
private const val STEP_CONSENT = 0
private const val STEP_PROFILE = 1

/**
 * Multi-step onboarding dialog for the eucstats online upload feature.
 *
 * Step 1, Consent: explains what data is shared publicly.
 * Step 2, Profile: a tappable avatar, display name, and a flag/country picker.
 *
 * The avatar is required (Register stays disabled until one is cropped) but the
 * UI never nags about it, the empty avatar ring with a camera glyph is the cue.
 */
@Composable
fun OnlineUploadOnboardingDialog(
    onDismiss: () -> Unit,
    onRegistered: () -> Unit,
    viewModel: SettingsViewModel,
) {
    var step by rememberSaveable { mutableIntStateOf(STEP_CONSENT) }

    // ---- Profile state -------------------------------------------------------
    var displayName by rememberSaveable { mutableStateOf("") }
    var flagCode    by rememberSaveable { mutableStateOf("") }  // ISO 3166-1 alpha-2

    var croppedAvatar    by remember { mutableStateOf<PickedAvatar?>(null) }
    var showCountryPicker by remember { mutableStateOf(false) }

    // ---- Registration state --------------------------------------------------
    var registering   by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf(false) }
    // The server's failure (HTTP code + detail) for a register error, or null for
    // a network failure -> falls back to the generic connection message.
    var registerFailure by remember {
        mutableStateOf<com.eried.eucplanet.data.eucstats.RegisterResult.Failed?>(null)
    }

    // ---- Image picker and its crop step (the platform's) ----------------------
    // A new pick clears the previous crop (null) until the crop is applied.
    val imagePicker = LocalSettingsHost.current.rememberAvatarPicker { croppedAvatar = it }
    if (imagePicker.cropping) return // Render only the crop step while it's open.

    // ---- Validation ----------------------------------------------------------
    val flagValid   = flagCode.length == 2 && flagCode.all { it.isLetter() }
    val canRegister = displayName.isNotBlank() && flagValid && croppedAvatar != null

    // =========================================================================
    // Consent step
    // =========================================================================
    if (step == STEP_CONSENT) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text = stringResource(Res.string.online_upload_consent_title),
                    color = MaterialTheme.appColors.textPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.online_upload_consent_intro),
                        color = MaterialTheme.appColors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    BulletPoint(stringResource(Res.string.online_upload_consent_bullet_name))
                    BulletPoint(stringResource(Res.string.online_upload_consent_bullet_flag))
                    BulletPoint(stringResource(Res.string.online_upload_consent_bullet_avatar))
                    BulletPoint(stringResource(Res.string.online_upload_consent_bullet_stats))
                    BulletPoint(stringResource(Res.string.online_upload_consent_bullet_location))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.online_upload_consent_no_raw_gps),
                        color = MaterialTheme.appColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { step = STEP_PROFILE },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.primary,
                        contentColor   = MaterialTheme.appColors.onPrimary,
                    )
                ) {
                    Text(stringResource(Res.string.online_upload_consent_agree))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.appColors.textButton,
                    )
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
        return
    }

    // =========================================================================
    // Profile step
    // =========================================================================
    AlertDialog(
        onDismissRequest = { if (!registering) onDismiss() },
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(Res.string.online_upload_profile_title),
                color = MaterialTheme.appColors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---- Tappable avatar (centered) ------------------------------
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clickable(enabled = !registering) { imagePicker.launch() },
                    contentAlignment = Alignment.Center
                ) {
                    // The circular avatar is clipped to a circle; the edit badge
                    // below is a SIBLING outside this clip so the CircleShape
                    // doesn't slice it into a corner sliver.
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.appColors.surfaceVariant)
                            .border(
                                width = 2.dp,
                                color = if (croppedAvatar != null) MaterialTheme.appColors.primary
                                        else MaterialTheme.appColors.outline,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val cropped = croppedAvatar
                        if (cropped != null) {
                            Image(
                                bitmap = cropped.image,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp).clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Default.AddAPhoto, contentDescription = null,
                                tint = MaterialTheme.appColors.textSecondary,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    // Small edit badge on the circle's bottom-right edge (not clipped).
                    if (croppedAvatar != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.appColors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Edit, contentDescription = null,
                                tint = MaterialTheme.appColors.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                // Gentle affordance (not a "required" nag) only before a photo is set.
                if (croppedAvatar == null) {
                    Text(
                        text = stringResource(Res.string.online_upload_profile_avatar_hint),
                        color = MaterialTheme.appColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // ---- Display name -------------------------------------------
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(Res.string.online_upload_profile_name_label)) },
                    singleLine = true,
                    enabled = !registering,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = themedFieldColors(),
                )

                // ---- Country picker (flag list) ------------------------------
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.online_upload_profile_country_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    Surface(
                        onClick = { if (!registering) showCountryPicker = true },
                        enabled = !registering,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.appColors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (flagValid) "${flagEmoji(flagCode)}  ${countryName(flagCode)}"
                                       else stringResource(Res.string.online_upload_profile_country_select),
                                color = if (flagValid) MaterialTheme.appColors.textPrimary
                                        else MaterialTheme.appColors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ArrowDropDown, contentDescription = null,
                                tint = MaterialTheme.appColors.textSecondary,
                            )
                        }
                    }
                }

                // ---- Inline registration error -------------------------------
                if (registerError) {
                    val failure = registerFailure
                    Text(
                        // The server's own reason + HTTP code when it responded
                        // (e.g. "display_name_taken (409)"); the generic
                        // connection message only for a real network failure.
                        text = if (failure != null)
                            stringResource(
                                Res.string.online_upload_register_server_error,
                                failure.detail ?: "?", failure.code,
                            )
                        else stringResource(Res.string.online_upload_register_error),
                        color = MaterialTheme.appColors.statusDanger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (registering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.appColors.primary,
                    )
                }
                Button(
                    onClick = {
                        val avatar = croppedAvatar ?: return@Button
                        registering   = true
                        registerError = false
                        registerFailure = null
                        val base64 = avatar.pngBase64
                        viewModel.registerOnlineUpload(
                            displayName = displayName.trim(),
                            flag        = flagCode.uppercase(),
                            avatarPngBase64 = base64,
                        ) { result ->
                            registering = false
                            if (result == com.eried.eucplanet.data.eucstats.RegisterResult.Ok) {
                                onRegistered()
                            } else {
                                registerError = true
                                // Keep the server failure (code + detail) only when
                                // the server actually responded (code != 0); a
                                // network failure falls back to the generic message.
                                registerFailure = (result as?
                                    com.eried.eucplanet.data.eucstats.RegisterResult.Failed)
                                    ?.takeIf { it.code != 0 }
                            }
                        }
                    },
                    enabled = canRegister && !registering,
                    shape = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.buttonColors(
                        containerColor         = MaterialTheme.appColors.primary,
                        contentColor           = MaterialTheme.appColors.onPrimary,
                        disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                        disabledContentColor   = MaterialTheme.appColors.textDisabled,
                    )
                ) {
                    Text(stringResource(Res.string.online_upload_register_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!registering) onDismiss() },
                enabled = !registering,
                shape = RoundedCornerShape(12.dp),
                colors  = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.appColors.textButton,
                )
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )

    // Country picker overlays the profile dialog.
    if (showCountryPicker) {
        CountryPickerDialog(
            onPick = { code -> flagCode = code.uppercase(); showCountryPicker = false },
            onDismiss = { showCountryPicker = false },
        )
    }
}

// ---- Country picker ---------------------------------------------------------

/** Searchable list of all ISO countries with flag emoji, built from the
 *  platform's locale data so there's no hardcoded country table to maintain.
 *  Shared with the profile-edit form (same package). */
@Composable
internal fun CountryPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        isoCountryCodes()
            .map { it to platformCountryName(it) }
            .filter { it.second.isNotBlank() && it.second != it.first }
            .sortedBy { it.second }
    }
    val filtered = remember(query) {
        if (query.isBlank()) all
        else all.filter {
            it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.online_upload_country_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = themedFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = com.eried.eucplanet.ui.common.dialogContentMaxHeight(340))
                ) {
                    items(filtered) { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(code) }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(flagEmoji(code), style = MaterialTheme.typography.titleMedium)
                            Text(name, color = MaterialTheme.appColors.textPrimary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.appColors.textButton),
            ) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

/** ISO 3166-1 alpha-2 code → flag emoji (regional indicator letters). */
fun flagEmoji(code: String): String {
    val c = code.uppercase()
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    return regionalIndicator(c[0]) + regionalIndicator(c[1])
}

/** The regional indicator for [letter] (A to Z), as the UTF-16 pair `Character.toChars` gave. */
private fun regionalIndicator(letter: Char): String {
    val cp = 0x1F1E6 + (letter - 'A') - 0x10000
    return charArrayOf((0xD800 + (cp shr 10)).toChar(), (0xDC00 + (cp and 0x3FF)).toChar()).concatToString()
}

/** Localised country name for a 2-letter code. */
fun countryName(code: String): String =
    platformCountryName(code.uppercase()).ifBlank { code.uppercase() }
