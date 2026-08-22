package com.eried.eucplanet.ui.lockdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the Unlock button stays disabled after a wrong code. */
private const val WRONG_CODE_COOLDOWN_MS = 3_000L

/**
 * The Speed limit dialog, and the only way out of Legal Mode Lockdown.
 *
 * Shows the real limits the wheel is being held to, then asks for the
 * manufacturer code. A wrong code costs a flat three seconds, which is what
 * makes walking a four digit PIN impractical at a stoplight.
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

    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var cooldownUntil by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(cooldownUntil) {
        while (System.currentTimeMillis() < cooldownUntil) {
            nowMs = System.currentTimeMillis()
            delay(200)
        }
        nowMs = System.currentTimeMillis()
    }
    val cooling = nowMs < cooldownUntil

    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap must not drop a half-typed code.
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = colors.dialog,
        title = { Text(stringResource(R.string.lockdown_speed_limit_title)) },
        text = {
            Column {
                LockdownInfoRow(
                    stringResource(R.string.lockdown_vehicle_max_speed),
                    formatSpeed(legalTiltbackKmh, speedUnit, context)
                )
                LockdownInfoRow(
                    stringResource(R.string.lockdown_vehicle_alarm),
                    formatSpeed(legalAlarmKmh, speedUnit, context)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.lockdown_unlock_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        // Digits only, and never longer than the longest code.
                        pin = it.filter { c -> c.isDigit() }.take(8)
                        wrong = false
                    },
                    singleLine = true,
                    isError = wrong,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                if (wrong) {
                    Text(
                        text = stringResource(R.string.lockdown_wrong_code),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.statusDanger
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !cooling && pin.isNotEmpty(),
                onClick = {
                    scope.launch {
                        if (!onTryUnlock(pin)) {
                            wrong = true
                            pin = ""
                            cooldownUntil = System.currentTimeMillis() + WRONG_CODE_COOLDOWN_MS
                            nowMs = System.currentTimeMillis()
                        }
                        // On success the armed flag flips and MainActivity swaps
                        // back to the nav graph on its own. Nothing to do here.
                    }
                }
            ) { Text(stringResource(R.string.lockdown_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
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
