package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.data.repository.LegalLockdownCode
import com.eried.eucplanet.ui.common.BulletPoint
import com.eried.eucplanet.ui.common.LocalSnackbar
import com.eried.eucplanet.ui.common.dialogContentMaxHeight
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import kotlinx.coroutines.launch

/**
 * The Legal Mode Lockdown row, in the Wheel parameters tab under Legal mode
 * speed. Lives in its own file rather than inside SettingsScreen.kt, which is
 * already past nine thousand lines.
 *
 * The switch is resident: turning it on arms the mode and then waits, and the
 * locked screen only appears once legal mode comes on. Until it engages the
 * switch is an ordinary one and can be turned back off. After that the settings
 * screen is unreachable anyway, so only the manufacturer code gets out.
 */
@Composable
internal fun LegalLockdownSetting(viewModel: SettingsViewModel) {
    val colors = MaterialTheme.appColors
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val armed by viewModel.legalLockdown.armed.collectAsState()
    val legalModeOn by viewModel.legalModeActive.collectAsState()

    var showDialog by remember { mutableStateOf(false) }

    // No SectionHeader: it would repeat the row label word for word directly
    // above it. The row sits under the Legal mode speed section it belongs to.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.lockdown_setting_label),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary
            )
            Text(
                text = stringResource(R.string.lockdown_setting_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Switch(
            checked = armed,
            onCheckedChange = { on ->
                if (on) showDialog = true else viewModel.disarmLockdown()
            },
            colors = themedSwitchColors()
        )
    }

    if (showDialog) {
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        val validPin = LegalLockdownCode.isValidPin(pin)
        val matches = pin == confirm
        val error = when {
            pin.isEmpty() && confirm.isEmpty() -> null
            !validPin -> stringResource(R.string.lockdown_code_invalid)
            !matches -> stringResource(R.string.lockdown_code_mismatch)
            else -> null
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            // A stray tap must not drop a half-typed code.
            properties = DialogProperties(dismissOnClickOutside = false),
            containerColor = colors.dialog,
            title = { Text(stringResource(R.string.lockdown_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The limitation list scrolls inside its own capped box so
                    // the two code fields below it are always on screen. Behind
                    // a wall of text the rider saw only a disabled Turn on
                    // button and no way forward.
                    Column(
                        modifier = Modifier
                            // Shared cap, so the Turn on button is never
                            // pushed off a short screen such as a flip cover.
                            .heightIn(max = dialogContentMaxHeight(260))
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Arming normally just waits. With legal mode already on
                        // there is nothing to wait for, so say so plainly before
                        // the rider taps a button that locks the app on the spot.
                        Text(
                            text = stringResource(
                                if (legalModeOn) R.string.lockdown_warning_now
                                else R.string.lockdown_warning_waits
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (legalModeOn) colors.statusWarn else colors.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.lockdown_warning_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                        for (limit in lockdownLimitStrings()) {
                            BulletPoint(
                                text = limit,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.lockdown_warning_settings_safe),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                        Text(
                            text = stringResource(R.string.lockdown_warning_recovery),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.statusDanger
                        )
                    }
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(stringResource(R.string.lockdown_set_code)) },
                        placeholder = { Text(stringResource(R.string.lockdown_code_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(stringResource(R.string.lockdown_confirm_code)) },
                        singleLine = true,
                        isError = error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.statusDanger
                        )
                    }
                }
            },
            confirmButton = {
                val savedMsg = stringResource(R.string.lockdown_trip_saved)
                TextButton(
                    enabled = validPin && matches,
                    onClick = {
                        scope.launch {
                            val wasRecording = viewModel.isRecordingNow()
                            if (viewModel.armLockdown(pin)) {
                                showDialog = false
                                // MainActivity swaps to the locked screen when
                                // the armed flag flips, so there is nothing to
                                // navigate to from here.
                                if (wasRecording) snackbar?.showSnackbar(savedMsg)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.lockdown_arm)) }
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
 * Every limitation the rider is agreeing to, in display order.
 *
 * One string per line rather than one long string with escapes: the bullets
 * then render as real bullets, and each line is translated on its own instead
 * of as a wall of text where a single missed newline ruins the layout.
 */
@Composable
private fun lockdownLimitStrings(): List<String> = listOf(
    stringResource(R.string.lockdown_limit_1),
    stringResource(R.string.lockdown_limit_2),
    stringResource(R.string.lockdown_limit_3),
    stringResource(R.string.lockdown_limit_4),
    stringResource(R.string.lockdown_limit_5),
    stringResource(R.string.lockdown_limit_6),
    stringResource(R.string.lockdown_limit_7),
    stringResource(R.string.lockdown_limit_8),
    stringResource(R.string.lockdown_limit_9),
    stringResource(R.string.lockdown_limit_10),
    stringResource(R.string.lockdown_limit_11),
    stringResource(R.string.lockdown_limit_12),
    stringResource(R.string.lockdown_limit_13),
    stringResource(R.string.lockdown_limit_14),
    stringResource(R.string.lockdown_limit_15),
    stringResource(R.string.lockdown_limit_16),
    stringResource(R.string.lockdown_limit_17),
    stringResource(R.string.lockdown_limit_18),
)
