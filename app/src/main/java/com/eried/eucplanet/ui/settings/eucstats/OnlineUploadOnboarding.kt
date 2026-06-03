package com.eried.eucplanet.ui.settings.eucstats

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
import com.eried.eucplanet.ui.settings.SettingsViewModel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import java.io.ByteArrayOutputStream

// ---- Step constants ---------------------------------------------------------
private const val STEP_CONSENT = 0
private const val STEP_PROFILE = 1

/**
 * Multi-step onboarding dialog for the eucstats online upload feature.
 *
 * Step 1 — Consent: explains what data is shared publicly.
 * Step 2 — Profile: display name, flag (ISO 2-letter), required avatar.
 *
 * The Register button is disabled until all three fields are valid
 * (name non-blank, flag exactly 2 uppercase letters, avatar cropped).
 *
 * Call sites (Task E3) wire this from the Cloud tab when the rider taps
 * "Enable online upload" and has not yet registered.
 *
 * @param onDismiss     Called when the rider taps Cancel at any step.
 * @param onRegistered  Called on the main thread after a successful registration.
 * @param viewModel     Provides [SettingsViewModel.registerOnlineUpload].
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
    var flagCode    by rememberSaveable { mutableStateOf("") }

    // Avatar: the cropped Bitmap lives only in memory (no need to survive
    // process-death during onboarding). We keep the raw-picked URI so that
    // if the rider re-picks we decode afresh.
    var pickedBitmap   by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap  by remember { mutableStateOf<Bitmap?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }

    // ---- Registration state --------------------------------------------------
    var registering  by remember { mutableStateOf(false) }
    var registerError by remember { mutableStateOf(false) }

    // ---- Image picker --------------------------------------------------------
    val context = LocalContext.current
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

    // ---- Avatar crop sub-dialog (full-screen) --------------------------------
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
            return // Render only the crop dialog while it's open.
        }
    }

    // ---- Form validation -----------------------------------------------------
    val flagValid     = flagCode.length == 2 && flagCode.all { it.isLetter() }
    val canRegister   = displayName.isNotBlank() && flagValid && croppedBitmap != null

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

                // ---- Flag / country -----------------------------------------
                OutlinedTextField(
                    value = flagCode,
                    onValueChange = { raw ->
                        // Accept only letters, force uppercase, cap at 2 chars
                        val filtered = raw.filter { it.isLetter() }.uppercase()
                        if (filtered.length <= 2) flagCode = filtered
                    },
                    label = { Text(stringResource(R.string.online_upload_profile_flag_label)) },
                    placeholder = { Text(stringResource(R.string.online_upload_profile_flag_placeholder)) },
                    supportingText = {
                        if (flagCode.isNotEmpty() && !flagValid) {
                            Text(
                                text = stringResource(R.string.online_upload_profile_flag_error),
                                color = MaterialTheme.appColors.statusDanger,
                            )
                        }
                    },
                    isError = flagCode.isNotEmpty() && !flagValid,
                    singleLine = true,
                    enabled = !registering,
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedFieldColors(),
                )

                // ---- Avatar pick + preview -----------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Circular avatar preview (or placeholder ring)
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
                            Text(
                                text = stringResource(R.string.online_upload_profile_avatar_placeholder),
                                color = MaterialTheme.appColors.textSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !registering,
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

                        // Required hint — only visible before any avatar is picked
                        if (croppedBitmap == null) {
                            Text(
                                text = stringResource(R.string.online_upload_profile_avatar_required),
                                color = MaterialTheme.appColors.statusWarn,
                                style = MaterialTheme.typography.labelSmall,
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
}

// ---- Private helpers --------------------------------------------------------

/**
 * A single "•" bullet point for the consent list, styled with secondary text color.
 */
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

/**
 * Encode a cropped avatar [Bitmap] to a base64 PNG string with NO_WRAP
 * padding, exactly as [com.eried.eucplanet.ui.navigator.UserMarkerCropDialog]
 * does via [com.eried.eucplanet.ui.navigator.toBase64DataUrl] (minus the
 * `data:image/png;base64,` prefix, which the eucstats API doesn't want).
 *
 * The bitmap from [UserMarkerCropDialog] is already 64×64 with a circular
 * alpha mask, so no further scaling is needed.
 */
private fun encodeBitmapToBase64(bitmap: Bitmap): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
}
