package com.eried.eucplanet.ui.lockdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.launch

/**
 * The Speed limit dialog, and the only way out of Legal Mode Lockdown.
 *
 * Shows the limits the wheel is being held to as read-only fields, then asks
 * for the manufacturer code.
 *
 * A wrong code simply closes the dialog, with no message and nothing changed.
 * That is deliberate: there is no error to read and no field left focused, so
 * guessing means reopening the dialog and starting over every single time. It
 * makes walking a four digit code a chore without ever locking anyone out.
 */
@Composable
fun LockdownUnlockDialog(
    legalTiltbackKmh: Float,
    legalAlarmKmh: Float,
    speedUnit: String,
    onDismiss: () -> Unit,
    onTryUnlock: suspend (String) -> Boolean
) {
    val colors = MaterialTheme.appColors
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    var pin by remember { mutableStateOf("") }

    // One action for the Unlock button and for Done on the code field, so
    // finishing with the keyboard does the same thing as tapping.
    val unlock: () -> Unit = {
        scope.launch {
            // Right or wrong, the dialog closes. On success the armed flag
            // flips and MainActivity swaps back to the nav graph on its own.
            onTryUnlock(pin)
            onDismiss()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap must not drop a half-typed code.
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = colors.dialog,
        title = { Text(stringResource(R.string.lockdown_speed_limit_title)) },
        text = {
            Column {
                // Half and half, the same shape the Legal Tiltback / Legal Alarm
                // pair uses in settings, so the two limits read as a pair. Both
                // are read-only: this dialog reports them, it does not set them.
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReadOnlySpeedField(
                        label = stringResource(R.string.lockdown_action_speed_limit),
                        value = formatSpeed(legalTiltbackKmh, speedUnit, context),
                        modifier = Modifier.fillMaxWidth()
                    )
                    ReadOnlySpeedField(
                        label = stringResource(R.string.lockdown_tiltback_alarm),
                        value = formatSpeed(legalAlarmKmh, speedUnit, context),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        // Digits only, and never longer than the longest code.
                        pin = it.filter { c -> c.isDigit() }.take(8)
                    },
                    label = { Text(stringResource(R.string.lockdown_unlock_prompt)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    // Done unlocks, the way finishing any form with the
                    // keyboard submits it.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (pin.isNotEmpty()) unlock()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.isNotEmpty(),
                onClick = unlock
            ) { Text(stringResource(R.string.lockdown_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/**
 * A limit the rider can read but not touch: a lock-badged pill.
 *
 * It was an OutlinedTextField with readOnly, which still looked editable and
 * still let the text be selected - a field that refuses to change reads as
 * broken, not as information. Of three candidates drawn side by side (stat
 * tiles, plain rows, these pills) Erwin chose the pills: the lock says what
 * the value is doing here, and this section is allowed to look a little
 * different from the rest of the app.
 */
@Composable
private fun ReadOnlySpeedField(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    Row(
        modifier = modifier
            .border(1.dp, colors.divider, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.material3.Icon(
            Icons.Outlined.Lock, contentDescription = null,
            tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
    }
}

/** Read-only wheel identity, for showing what is connected without any menus. */
@Composable
fun LockdownVehicleDialog(
    connected: Boolean,
    deviceName: String?,
    brand: String?,
    model: String?,
    legalTiltbackKmh: Float,
    legalAlarmKmh: Float,
    odometerKm: Float,
    speedUnit: String,
    distanceUnit: String,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.appColors
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = colors.dialog,
        title = { Text(stringResource(R.string.lockdown_action_vehicle)) },
        text = {
            if (!connected) {
                Text(stringResource(R.string.lockdown_no_wheel))
            } else {
                Column {
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_name),
                        deviceName.orEmpty().ifBlank { "-" }
                    )
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_brand),
                        brand.orEmpty().ifBlank { "-" }
                    )
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_model),
                        model.orEmpty().ifBlank { "-" }
                    )
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_max_speed),
                        formatSpeed(legalTiltbackKmh, speedUnit, context)
                    )
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_alarm),
                        formatSpeed(legalAlarmKmh, speedUnit, context)
                    )
                    LockdownInfoRow(
                        stringResource(R.string.lockdown_vehicle_odometer),
                        "%.0f %s".format(
                            Units.distance(odometerKm, distanceUnit),
                            Units.distanceUnit(distanceUnit)
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun LockdownInfoRow(label: String, value: String) {
    val colors = MaterialTheme.appColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

private fun formatSpeed(kmh: Float, unit: String, context: android.content.Context): String =
    "%.0f %s".format(Units.speed(kmh, unit), Units.speedUnit(context, unit))
