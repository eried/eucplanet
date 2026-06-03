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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.eucstats.RiderProfile
import com.eried.eucplanet.ui.navigator.UserMarkerCropDialog
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.theme.appColors
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
        val bmp = runCatching {
            val src = context.contentResolver.openInputStream(uri)
                ?: return@rememberLauncherForActivityResult
            android.graphics.BitmapFactory.decodeStream(src)
        }.getOrNull() ?: return@rememberLauncherForActivityResult
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
                }
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
                        )
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
                    )
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
        title = {
            Text(
                text = stringResource(R.string.online_profile_title),
                color = MaterialTheme.appColors.textPrimary,
            )
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
                } else {
                    // ---- Display name ----------------------------------------
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { if (nameEditable) displayName = it },
                        label = { Text(stringResource(R.string.online_upload_profile_name_label)) },
                        singleLine = true,
                        enabled = nameEditable && !saving,
                        supportingText = if (!nameEditable) {
                            {
                                Text(
                                    text = stringResource(
                                        R.string.online_profile_changeable_on,
                                        profile?.canChangeNameAfter.orEmpty()
                                    ),
                                    color = MaterialTheme.appColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedFieldColors(),
                    )

                    // ---- Flag ------------------------------------------------
                    OutlinedTextField(
                        value = flagCode,
                        onValueChange = { raw ->
                            if (flagEditable) {
                                val filtered = raw.filter { it.isLetter() }.uppercase()
                                if (filtered.length <= 2) flagCode = filtered
                            }
                        },
                        label = { Text(stringResource(R.string.online_upload_profile_flag_label)) },
                        placeholder = { Text(stringResource(R.string.online_upload_profile_flag_placeholder)) },
                        supportingText = when {
                            !flagEditable -> {
                                {
                                    Text(
                                        text = stringResource(
                                            R.string.online_profile_changeable_on,
                                            profile?.canChangeFlagAfter.orEmpty()
                                        ),
                                        color = MaterialTheme.appColors.textSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            flagCode.isNotEmpty() && !flagValid -> {
                                {
                                    Text(
                                        text = stringResource(R.string.online_upload_profile_flag_error),
                                        color = MaterialTheme.appColors.statusDanger,
                                    )
                                }
                            }
                            else -> null
                        },
                        isError = flagCode.isNotEmpty() && !flagValid,
                        singleLine = true,
                        enabled = flagEditable && !saving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedFieldColors(),
                    )

                    // ---- Avatar ----------------------------------------------
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = if (croppedBitmap != null)
                                        MaterialTheme.appColors.primary
                                    else
                                        MaterialTheme.appColors.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val cropped = croppedBitmap
                            if (cropped != null) {
                                val imageBitmap = remember(cropped) { cropped.asImageBitmap() }
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                val hasExistingAvatar = profile?.hasAvatar == true
                                Text(
                                    text = if (hasExistingAvatar)
                                        stringResource(R.string.online_profile_avatar_has)
                                    else
                                        stringResource(R.string.online_upload_profile_avatar_placeholder),
                                    color = MaterialTheme.appColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = avatarEditable && !saving,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.appColors.tonalButtonFill,
                                    contentColor   = MaterialTheme.appColors.tonalButtonText,
                                )
                            ) {
                                Text(
                                    text = stringResource(
                                        if (croppedBitmap == null)
                                            R.string.online_upload_profile_avatar_pick
                                        else
                                            R.string.online_upload_profile_avatar_change
                                    )
                                )
                            }
                            if (!avatarEditable) {
                                Text(
                                    text = stringResource(
                                        R.string.online_profile_changeable_on,
                                        profile?.canChangeAvatarAfter.orEmpty()
                                    ),
                                    color = MaterialTheme.appColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    // ---- Save error ------------------------------------------
                    saveError?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.appColors.statusDanger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // ---- Export data -----------------------------------------
                    FilledTonalButton(
                        onClick = {
                            viewModel.exportOnlineData { json ->
                                if (json != null) {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        putExtra(Intent.EXTRA_SUBJECT, "eucstats profile export")
                                    }
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, null)
                                        )
                                    }
                                }
                            }
                        },
                        enabled = !saving && !deleting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.appColors.tonalButtonFill,
                            contentColor   = MaterialTheme.appColors.tonalButtonText,
                        )
                    ) {
                        Text(stringResource(R.string.online_profile_export_data))
                    }

                    // ---- Delete account --------------------------------------
                    Button(
                        onClick = { showDeleteConfirm = true },
                        enabled = !saving && !deleting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.appColors.statusDanger,
                            contentColor   = MaterialTheme.appColors.onPrimary,
                        )
                    ) {
                        Text(stringResource(R.string.online_profile_delete_account))
                    }
                }
            }
        },
        confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.appColors.primary,
                    )
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
                                429 -> saveError = "Rate limited — please try again later."
                                else -> saveError = "Save failed (HTTP $code). Please try again."
                            }
                        }
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = MaterialTheme.appColors.primary,
                        contentColor           = MaterialTheme.appColors.onPrimary,
                        disabledContainerColor = MaterialTheme.appColors.surfaceVariant,
                        disabledContentColor   = MaterialTheme.appColors.textDisabled,
                    )
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (!saving && !deleting) onDismiss() },
                enabled = !saving && !deleting,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.appColors.textButton,
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
