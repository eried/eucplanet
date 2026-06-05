package com.eried.eucplanet.ui.settings.eucstats

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.navigator.UserMarkerCropDialog
import com.eried.eucplanet.ui.navigator.decodeDownsampledBitmap
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import java.io.ByteArrayOutputStream
import java.util.Locale

// ---- Step constants ---------------------------------------------------------
private const val STEP_CONSENT = 0
private const val STEP_PROFILE = 1

/**
 * Multi-step onboarding dialog for the eucstats online upload feature.
 *
 * Step 1 — Consent: explains what data is shared publicly.
 * Step 2 — Profile: a tappable avatar, display name, and a flag/country picker.
 *
 * The avatar is required (Register stays disabled until one is cropped) but the
 * UI never nags about it — the empty avatar ring with a camera glyph is the cue.
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

    var pickedBitmap     by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap    by remember { mutableStateOf<Bitmap?>(null) }
    var showCropDialog   by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }

    // ---- Registration state --------------------------------------------------
    var registering   by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf(false) }

    // ---- Image picker --------------------------------------------------------
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Downsample on decode: a full-res gallery photo would OOM / blow the
        // canvas bitmap limit in the crop dialog and crash the app.
        val bmp = runCatching { decodeDownsampledBitmap(context, uri) }
            .getOrNull() ?: return@rememberLauncherForActivityResult
        pickedBitmap  = bmp
        croppedBitmap = null
        showCropDialog = true
    }

    // ---- Avatar crop sub-dialog (full-screen) --------------------------------
    pickedBitmap?.let { src ->
        if (showCropDialog) {
            UserMarkerCropDialog(
                source = src,
                onCancel = { showCropDialog = false },
                onApply  = { cropped ->
                    croppedBitmap  = cropped
                    showCropDialog = false
                },
                // 256px so the 96dp preview is crisp (server still re-encodes to 64).
                outputSize = 256,
            )
            return // Render only the crop dialog while it's open.
        }
    }

    // ---- Validation ----------------------------------------------------------
    val flagValid   = flagCode.length == 2 && flagCode.all { it.isLetter() }
    val canRegister = displayName.isNotBlank() && flagValid && croppedBitmap != null

    // =========================================================================
    // Consent step
    // =========================================================================
    if (step == STEP_CONSENT) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.online_upload_consent_title),
                    color = MaterialTheme.appColors.textPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.online_upload_consent_intro),
                        color = MaterialTheme.appColors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    ConsentBullet(stringResource(R.string.online_upload_consent_bullet_name))
                    ConsentBullet(stringResource(R.string.online_upload_consent_bullet_flag))
                    ConsentBullet(stringResource(R.string.online_upload_consent_bullet_avatar))
                    ConsentBullet(stringResource(R.string.online_upload_consent_bullet_stats))
                    ConsentBullet(stringResource(R.string.online_upload_consent_bullet_location))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.online_upload_consent_no_raw_gps),
                        color = MaterialTheme.appColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { step = STEP_PROFILE },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.primary,
                        contentColor   = MaterialTheme.appColors.onPrimary,
                    )
                ) {
                    Text(stringResource(R.string.online_upload_consent_agree))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.appColors.textButton,
                    )
                ) {
                    Text(stringResource(R.string.action_cancel))
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
        title = {
            Text(
                text = stringResource(R.string.online_upload_profile_title),
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
                        .clip(CircleShape)
                        .background(MaterialTheme.appColors.surfaceVariant)
                        .border(
                            width = 2.dp,
                            color = if (croppedBitmap != null) MaterialTheme.appColors.primary
                                    else MaterialTheme.appColors.outline,
                            shape = CircleShape
                        )
                        .clickable(enabled = !registering) { imagePicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    val cropped = croppedBitmap
                    if (cropped != null) {
                        Image(
                            bitmap = remember(cropped) { cropped.asImageBitmap() },
                            contentDescription = null,
                            modifier = Modifier.size(96.dp).clip(CircleShape)
                        )
                        // Small edit badge so it's obviously tappable to change.
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
                    } else {
                        Icon(
                            Icons.Default.AddAPhoto, contentDescription = null,
                            tint = MaterialTheme.appColors.textSecondary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                // Gentle affordance (not a "required" nag) only before a photo is set.
                if (croppedBitmap == null) {
                    Text(
                        text = stringResource(R.string.online_upload_profile_avatar_hint),
                        color = MaterialTheme.appColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // ---- Display name -------------------------------------------
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.online_upload_profile_name_label)) },
                    singleLine = true,
                    enabled = !registering,
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedFieldColors(),
                )

                // ---- Country picker (flag list) ------------------------------
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.online_upload_profile_country_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    Surface(
                        onClick = { if (!registering) showCountryPicker = true },
                        enabled = !registering,
                        shape = RoundedCornerShape(8.dp),
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
                                       else stringResource(R.string.online_upload_profile_country_select),
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
                    Text(
                        text = stringResource(R.string.online_upload_register_error),
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
                        val bitmap = croppedBitmap ?: return@Button
                        registering   = true
                        registerError = false
                        val base64 = encodeBitmapToBase64(bitmap)
                        viewModel.registerOnlineUpload(
                            displayName = displayName.trim(),
                            flag        = flagCode.uppercase(),
                            avatarPngBase64 = base64,
                        ) { ok ->
                            registering = false
                            if (ok) onRegistered() else registerError = true
                        }
                    },
                    enabled = canRegister && !registering,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor         = MaterialTheme.appColors.primary,
                        contentColor           = MaterialTheme.appColors.onPrimary,
                        disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                        disabledContentColor   = MaterialTheme.appColors.textDisabled,
                    )
                ) {
                    Text(stringResource(R.string.online_upload_register_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!registering) onDismiss() },
                enabled = !registering,
                colors  = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.appColors.textButton,
                )
            ) {
                Text(stringResource(R.string.action_cancel))
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

/** Searchable list of all ISO countries with flag emoji, built from the JVM
 *  locale data so there's no hardcoded country table to maintain.
 *  Shared with the profile-edit form (same package). */
@Composable
internal fun CountryPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        Locale.getISOCountries()
            .map { it to Locale("", it).displayCountry }
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
        title = {
            Text(
                stringResource(R.string.online_upload_profile_country_label),
                color = MaterialTheme.appColors.textPrimary,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.online_upload_country_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
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
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.appColors.textButton),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** ISO 3166-1 alpha-2 code → flag emoji (regional indicator letters). */
internal fun flagEmoji(code: String): String {
    val c = code.uppercase()
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    val first  = 0x1F1E6 + (c[0] - 'A')
    val second = 0x1F1E6 + (c[1] - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

/** Localised country name for a 2-letter code. */
internal fun countryName(code: String): String =
    Locale("", code.uppercase()).displayCountry.ifBlank { code.uppercase() }

// ---- Private helpers --------------------------------------------------------

/** A single "•" bullet point for the consent list. */
@Composable
private fun ConsentBullet(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "•",
            color = MaterialTheme.appColors.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            color = MaterialTheme.appColors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Encode the cropped 64×64 avatar to a base64 PNG (no data-URL prefix). */
private fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
}
