package com.eried.eucplanet.ui.charging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.service.ChargeAlertPolicy
import com.eried.eucplanet.ui.settings.NumberUpDown
import com.eried.eucplanet.ui.theme.appColors

/**
 * Asks for the level of the rider's own charge alert. Save turns it on;
 * turning it off is a second tap on the ticked menu row, not a button here.
 *
 * The field takes any number so a wrong one can be seen as wrong: Save stays
 * disabled, with a small warning, until it is 5 to 95 and not 80, which has
 * its own alert. Not
 * dismissed by a tap outside, so a stray touch does not drop a number half
 * typed.
 */
@Composable
fun ChargeLevelDialog(
    initial: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var level by remember { mutableIntStateOf(initial) }
    val valid = ChargeAlertPolicy.isValidCustom(level)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.charging_notify_at)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberUpDown(
                    value = level,
                    onValueChange = { level = it },
                    // Wider than the valid range on purpose, an empty field
                    // reads as 0 and anything longer as 100: all leave Save
                    // disabled instead of quietly keeping the last good number.
                    range = 0..100,
                    parse = { it.toIntOrNull()?.coerceAtMost(100) ?: 0 },
                    suffix = "%",
                    modifier = Modifier.fillMaxWidth(),
                )
                // Only a number it will not take says anything, small and red,
                // like the lockdown code check.
                if (!valid) {
                    Text(
                        stringResource(R.string.charging_notify_custom_range),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.statusDanger,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(level) },
                enabled = valid,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
