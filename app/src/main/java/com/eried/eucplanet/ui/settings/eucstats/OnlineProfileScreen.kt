package com.eried.eucplanet.ui.settings.eucstats

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.ui.navigator.UserMarkerCropDialog
import com.eried.eucplanet.ui.navigator.decodeDownsampledBitmap
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.FieldNotchLabel
import com.eried.eucplanet.ui.theme.themedFieldColors
import java.io.ByteArrayOutputStream
import java.time.LocalDate

// ---------------------------------------------------------------------------
// Gating helper
// ---------------------------------------------------------------------------

/**
 * Returns true if the field is currently editable.
 *
 * A field is editable when [canChangeAfter] is null (no cooldown set), or
 * when the cooldown date is today or in the past. On parse failure the field
 * is treated as editable so the UI never permanently locks the rider out of
 * a field due to a server-format change.
 *
 * @param canChangeAfter ISO date string (yyyy-MM-dd) or null.
 * @param today          The reference date (default: today).
 */
fun isEditableOn(canChangeAfter: String?, today: LocalDate = LocalDate.now()): Boolean {
    if (canChangeAfter == null) return true
    return runCatching {
        val gate = LocalDate.parse(canChangeAfter)
        !today.isBefore(gate)   // today >= gate → editable
    }.getOrDefault(true)        // parse failure → editable
}

// ---------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------

/**
 * Full-screen AlertDialog for viewing and editing the rider's eucstats profile
 * (name, flag, avatar) with cooldown gating, plus GDPR delete and export.
 */
@Composable
fun OnlineProfileDialog(
    onDismiss: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current

    // ---- Loading state -------------------------------------------------------
    var loading by remember { mutableStateOf(true) }
    var profile by remember { mutableStateOf<RiderProfile?>(null) }

    // ---- Form fields ---------------------------------------------------------
    var displayName by rememberSaveable { mutableStateOf("") }
    var flagCode    by rememberSaveable { mutableStateOf("") }

    // Avatar
    var pickedBitmap   by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap  by remember { mutableStateOf<Bitmap?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }

    // ---- Operation state -----------------------------------------------------
    var saving       by remember { mutableStateOf(false) }
    var saveError    by remember { mutableStateOf<String?>(null) }

    // ---- Delete confirm ------------------------------------------------------
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting         by remember { mutableStateOf(false) }

    // ---- Load profile on first composition -----------------------------------
    LaunchedEffect(Unit) {
        viewModel.loadOnlineProfile { p ->
            profile = p
            displayName = p?.displayName.orEmpty()
            flagCode    = p?.flag.orEmpty()
            loading = false
        }
    }

    // ---- Image picker --------------------------------------------------------
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

    // ---- Avatar crop sub-dialog (renders full-screen, blocks the main dialog) -
    pickedBitmap?.let { src ->
        if (showCropDialog) {
            UserMarkerCropDialog(
                source = src,
                onCancel = { showCropDialog = false },
                onApply  = { cropped ->
                    croppedBitmap  = cropped
                    showCropDialog = false
                },
                // 256px so the preview is crisp (server still re-encodes to 64).
                outputSize = 256,
            )
            return
        }
    }

    // ---- Delete-confirm sub-dialog -------------------------------------------
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            title = {
                Text(
                    text = stringResource(R.string.online_profile_delete_confirm_title),
                    color = MaterialTheme.appColors.statusDanger,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.online_profile_delete_confirm_body),
                    color = MaterialTheme.appColors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.appColors.statusDanger,
                        )
                    }
                    Button(
                        onClick = {
                            deleting = true
                            viewModel.deleteOnlineAccount { ok ->
                                deleting = false
                                if (ok) {
                                    showDeleteConfirm = false
                                    onDismiss()
                                }
                            }
                        },
                        enabled = !deleting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.appColors.statusDanger,
                            contentColor   = MaterialTheme.appColors.onPrimary,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.online_profile_delete_confirm_action))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.appColors.textButton,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ---- Gating helpers (computed after profile is loaded) -------------------
    val today = remember { LocalDate.now() }
    val nameEditable   = isEditableOn(profile?.canChangeNameAfter,   today)
    val flagEditable   = isEditableOn(profile?.canChangeFlagAfter,   today)
    val avatarEditable = isEditableOn(profile?.canChangeAvatarAfter, today)

    val flagValid    = flagCode.length == 2 && flagCode.all { it.isLetter() }
    val hasChanges   = !loading && (
        displayName.trim() != (profile?.displayName.orEmpty()) ||
        flagCode.uppercase() != (profile?.flag.orEmpty()) ||
        croppedBitmap != null
    )
    val canSave = hasChanges && !saving && (flagCode.isEmpty() || flagValid)

    // ---- Main dialog ---------------------------------------------------------
    AlertDialog(
        onDismissRequest = { if (!saving && !deleting) onDismiss() },
        modifier = Modifier.fillMaxWidth(0.92f),
        shape = RoundedCornerShape(12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.online_profile_title),
                    color = MaterialTheme.appColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // Export my data: only when the profile loaded (export hits
                // the same server the profile fetch did, so a broken card
                // would also fail export).
                if (profile != null) {
                    IconButton(
                        onClick = {
                            viewModel.exportOnlineData { json ->
                                if (json != null) {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        putExtra(Intent.EXTRA_SUBJECT, "EUC Stats profile export")
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(shareIntent, null))
                                    }
                                }
                            }
                        },
                        enabled = !saving && !deleting,
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.online_profile_export_data),
                            tint = MaterialTheme.appColors.textSecondary,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.appColors.primary,
                        )
                        Text(
                            text = stringResource(R.string.online_upload_card_loading),
                            color = MaterialTheme.appColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (profile == null) {
                    Text(
                        text = stringResource(R.string.online_upload_card_unavailable),
                        color = MaterialTheme.appColors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            loading = true
                            viewModel.loadOnlineProfile { p ->
                                profile = p
                                displayName = p?.displayName.orEmpty()
                                flagCode    = p?.flag.orEmpty()
                                loading = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(R.string.online_profile_retry)) }
                } else {
                    // ---- Avatar (centered, tappable) -------------------------
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val cropped = croppedBitmap
                        val avatarBorder = if (cropped != null || profile?.hasAvatar == true)
                            MaterialTheme.appColors.primary else MaterialTheme.appColors.outline
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clickable(enabled = avatarEditable && !saving) { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Circular avatar (clipped). The edit badge is a sibling
                            // outside this clip so CircleShape doesn't slice it.
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.appColors.surfaceVariant)
                                    .border(width = 2.dp, color = avatarBorder, shape = CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    cropped != null -> Image(
                                        bitmap = remember(cropped) { cropped.asImageBitmap() },
                                        contentDescription = null,
                                        modifier = Modifier.size(96.dp).clip(CircleShape),
                                    )
                                    profile?.avatarUrl != null -> RemoteAvatar(
                                        url = profile?.avatarUrl,
                                        modifier = Modifier.size(96.dp).clip(CircleShape),
                                    ) {
                                        Icon(
                                            Icons.Default.Edit, contentDescription = null,
                                            tint = MaterialTheme.appColors.textSecondary,
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                    profile?.hasAvatar == true -> Icon(
                                        Icons.Default.Edit, contentDescription = null,
                                        tint = MaterialTheme.appColors.textSecondary,
                                        modifier = Modifier.size(30.dp),
                                    )
                                    else -> Icon(
                                        Icons.Default.AddAPhoto, contentDescription = null,
                                        tint = MaterialTheme.appColors.textSecondary,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                            }
                            // Edit badge on the circle's bottom-right edge (not clipped).
                            if (avatarEditable && (cropped != null || profile?.avatarUrl != null)) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.appColors.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Edit, contentDescription = null,
                                        tint = MaterialTheme.appColors.onPrimary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        if (avatarEditable && cropped == null && profile?.hasAvatar != true) {
                            Text(
                                text = stringResource(R.string.online_upload_profile_avatar_hint),
                                color = MaterialTheme.appColors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // ---- Display name ----------------------------------------
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { if (nameEditable) displayName = it },
                        label = { Text(stringResource(R.string.online_upload_profile_name_label)) },
                        singleLine = true,
                        enabled = nameEditable && !saving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                    )

                    // ---- Country picker (flag list) --------------------------
                    // A read-only OutlinedTextField so it matches the display-name
                    // field above exactly (label notch, font, fill). A transparent
                    // overlay opens the picker dialog instead of focusing the field.
                    val countryEnabled = flagEditable && !saving
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = if (flagValid) "${flagEmoji(flagCode)}  ${countryName(flagCode)}"
                                    else stringResource(R.string.online_upload_profile_country_select),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text(stringResource(R.string.online_upload_profile_country_label)) },
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            },
                            colors = themedFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (countryEnabled) 1f else 0.5f),
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (countryEnabled)
                                        Modifier.clickable { showCountryPicker = true }
                                    else Modifier
                                )
                        )
                    }

                    // One consolidated "locked until" line — the server locks the
                    // fields together, so a separate caption per field just looked
                    // messy. Shown once when anything is on cooldown.
                    val anyLocked = !nameEditable || !flagEditable || !avatarEditable
                    val lockedUntil = listOfNotNull(
                        profile?.canChangeNameAfter,
                        profile?.canChangeFlagAfter,
                        profile?.canChangeAvatarAfter,
                    ).maxOrNull()
                    if (anyLocked && lockedUntil != null) {
                        Text(
                            text = stringResource(R.string.online_profile_changeable_on, lockedUntil),
                            color = MaterialTheme.appColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ---- Save error ------------------------------------------
                    saveError?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.appColors.statusDanger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                }
            }
        },
        confirmButton = {
            // Custom full-width bar: Delete on the far left (destructive),
            // Cancel + Save grouped on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    // Also gated on having a loaded profile: deleteAccount
                    // needs the server round-trip to actually wipe the
                    // record, and without a successful card load we don't
                    // even know if the rider still exists on that side.
                    enabled = !saving && !deleting && profile != null,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.appColors.statusDanger,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.online_profile_delete_account))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.appColors.primary,
                        )
                    }
                    TextButton(
                        onClick = { if (!saving && !deleting) onDismiss() },
                        enabled = !saving && !deleting,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.appColors.textButton,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            saving    = true
                            saveError = null
                            val newName   = displayName.trim().takeIf { nameEditable && it != profile?.displayName.orEmpty() }
                            val newFlag   = flagCode.uppercase().takeIf { flagEditable && it != profile?.flag.orEmpty() }
                            val newAvatar = croppedBitmap?.takeIf { avatarEditable }
                                ?.let { bmp ->
                                    val baos = ByteArrayOutputStream()
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                }
                            viewModel.editOnlineProfile(
                                displayName    = newName,
                                flag           = newFlag,
                                avatarPngBase64 = newAvatar,
                            ) { code ->
                                saving = false
                                when (code) {
                                    200, 201, 204 -> onDismiss()
                                    429 -> saveError = context.getString(R.string.online_profile_save_rate_limited)
                                    else -> saveError = context.getString(R.string.online_profile_save_failed, code)
                                }
                            }
                        },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor         = MaterialTheme.appColors.primary,
                            contentColor           = MaterialTheme.appColors.onPrimary,
                            disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                            disabledContentColor   = MaterialTheme.appColors.textDisabled,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
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
