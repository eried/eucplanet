package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.TripSplitDetector
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors

/**
 * Trip tools, opened from the wrench in the trip list.
 *
 * Replaces the eye that used to sit there: the whole row already opens the trip,
 * so that icon was a second way to do the same thing and the slot was free.
 *
 * Everything here PRODUCES trips rather than altering the ride: a split leaves
 * the original alone, a join leaves its sources alone. The only tool that edits
 * in place is Change wheel, which corrects a label rather than the data.
 *
 * Nothing produced here can reach the eucstats leaderboard. See
 * [com.eried.eucplanet.data.repository.TripDerive].
 */
@Composable
fun TripToolsDialog(
    trip: TripRecord,
    onDismiss: () -> Unit,
    onChangeWheel: () -> Unit,
    onSplit: () -> Unit,
    onCombine: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_title)) },
        text = {
            Column {
                ToolRow(
                    // Not a vehicle glyph. This tool changes a LABEL, and the
                    // repo's terminology rule is that the device is a wheel,
                    // never a motorbike. A swap arrow says what it does.
                    Icons.Default.SwapHoriz,
                    stringResource(R.string.trip_tools_change_wheel),
                    stringResource(R.string.trip_tools_change_wheel_desc),
                ) { onDismiss(); onChangeWheel() }
                ToolRow(
                    Icons.Default.ContentCut,
                    stringResource(R.string.trip_tools_split),
                    stringResource(R.string.trip_tools_split_desc),
                ) { onDismiss(); onSplit() }
                ToolRow(
                    Icons.Default.CallMerge,
                    stringResource(R.string.trip_tools_combine),
                    stringResource(R.string.trip_tools_combine_desc),
                ) { onDismiss(); onCombine() }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Pick which wheel a trip was ridden on.
 *
 * [knownWheels] comes from two places: the rider's saved wheel profiles, and the
 * wheel names any existing trip already carries. The second matters because a
 * wheel that only ever arrived as an imported CSV has no profile, and that is
 * exactly when a rider is most likely to be fixing a wrong label. A free-text
 * field still covers a wheel neither source has heard of.
 *
 * [alreadyUploaded] shows the warning the rider agreed to: the leaderboard entry
 * keeps the old wheel and cannot be corrected from the app.
 */
@Composable
fun ChangeWheelDialog(
    knownWheels: List<String>,
    currentWheel: String?,
    alreadyUploaded: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(currentWheel ?: knownWheels.firstOrNull() ?: "") }
    var custom by remember { mutableStateOf("") }
    var usingCustom by remember { mutableStateOf(knownWheels.isEmpty()) }
    val chosen = if (usingCustom) custom.trim() else picked

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_change_wheel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (alreadyUploaded) {
                    Text(
                        stringResource(R.string.trip_tools_wheel_uploaded_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.statusWarn,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                knownWheels.forEach { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { usingCustom = false; picked = name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = !usingCustom && picked == name,
                            onClick = { usingCustom = false; picked = name },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name, color = MaterialTheme.appColors.textPrimary)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { usingCustom = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = usingCustom, onClick = { usingCustom = true })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.trip_tools_wheel_other),
                        color = MaterialTheme.appColors.textPrimary,
                    )
                }
                if (usingCustom) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = themedFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (chosen.isNotBlank()) onConfirm(chosen) },
                enabled = chosen.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * The cuts the detector proposed, for the rider to accept or reject.
 *
 * Every one is opt-in. Only the rider knows whether a twenty minute stop was a
 * coffee break or a long traffic light, so the app proposes and never decides.
 */
@Composable
fun SplitTripDialog(
    cuts: List<TripSplitDetector.Cut>,
    formatElapsed: (Long) -> String,
    onConfirm: (List<TripSplitDetector.Cut>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(cuts.map { it.index }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_split)) },
        text = {
            if (cuts.isEmpty()) {
                Text(
                    stringResource(R.string.trip_tools_split_none),
                    color = MaterialTheme.appColors.textSecondary,
                )
            } else {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.trip_tools_split_hint, cuts.size + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.textSecondary,
                    )
                    cuts.forEach { cut ->
                        val on = cut.index in selected.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value = if (on) selected.value - cut.index
                                                     else selected.value + cut.index
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = {
                                    selected.value = if (on) selected.value - cut.index
                                                     else selected.value + cut.index
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    stringResource(
                                        when (cut.reason) {
                                            TripSplitDetector.Reason.WHEEL_CHANGE -> R.string.trip_split_reason_wheel
                                            TripSplitDetector.Reason.TIME_GAP -> R.string.trip_split_reason_gap
                                            TripSplitDetector.Reason.STOPPED -> R.string.trip_split_reason_stopped
                                        }
                                    ),
                                    color = MaterialTheme.appColors.textPrimary,
                                )
                                Text(
                                    formatElapsed(cut.atElapsedMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.appColors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Nothing to split means nothing to apply, so the dialog offers a
            // single Close rather than a greyed Apply the rider might poke at.
            if (cuts.isNotEmpty()) {
                val chosen = cuts.filter { it.index in selected.value }
                TextButton(
                    onClick = { onConfirm(chosen) },
                    enabled = chosen.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.action_apply)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(
                    stringResource(
                        if (cuts.isEmpty()) R.string.action_close else R.string.action_cancel
                    )
                )
            }
        }
    )
}

/**
 * Pick other trips to merge into this one.
 *
 * Done here rather than as multi-select in the list because the tool is already
 * opened from a specific trip, so "which others join it" is the only question
 * left. That also keeps the trip list a plain list.
 */
@Composable
fun CombineTripsDialog(
    candidates: List<TripRecord>,
    label: (TripRecord) -> String,
    mixedWheelWarning: Boolean,
    onConfirm: (List<TripRecord>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        title = { Text(stringResource(R.string.trip_tools_combine)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (mixedWheelWarning) {
                    Text(
                        stringResource(R.string.trip_tools_combine_mixed_wheels),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.statusWarn,
                    )
                }
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.trip_tools_combine_none),
                        color = MaterialTheme.appColors.textSecondary,
                    )
                }
                candidates.forEach { t ->
                    val on = t.id in selected.value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected.value = if (on) selected.value - t.id else selected.value + t.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = {
                                selected.value = if (on) selected.value - t.id else selected.value + t.id
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label(t), color = MaterialTheme.appColors.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            val chosen = candidates.filter { it.id in selected.value }
            TextButton(
                onClick = { onConfirm(chosen) },
                enabled = chosen.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.appColors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary)
        }
    }
}
