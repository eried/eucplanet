package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.R
import com.eried.eucplanet.service.VoicePill
import com.eried.eucplanet.service.VoiceReportPlan
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import sh.calvin.reorderable.ReorderableColumn

/**
 * One announcement's pills: reordered with the standard drag handle, a
 * statistic picked per pill, the rider's own words as a Message pill, and
 * Play speaking the real announcement.
 */
@Composable
internal fun VoicePillList(
    title: String,
    pills: List<VoicePill>,
    onChange: (List<VoicePill>) -> Unit,
    onPlay: () -> Unit,
) {
    // Pills can repeat (speed, then max speed), so the list needs keys of its
    // own for the reorder animation; rebuilt whenever the saved list changes
    // under it.
    data class Keyed(val id: Int, val pill: VoicePill)
    var nextId by remember { mutableIntStateOf(0) }
    fun keyed(list: List<VoicePill>) = list.map { Keyed(nextId++, it) }
    var items by remember { mutableStateOf(keyed(pills)) }
    LaunchedEffect(pills) {
        if (items.map { it.pill } != pills) items = keyed(pills)
    }
    fun commit(list: List<Keyed>) {
        items = list
        onChange(list.map { it.pill })
    }

    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) } // index of a Message pill, -1 for a new one

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.appColors.primary,
            )
            Spacer(Modifier.width(4.dp))
            PlayButton(onClick = onPlay, enabled = items.isNotEmpty())
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { addOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.appColors.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.voice_pill_add), color = MaterialTheme.appColors.primary)
                }
                DropdownMenu(
                    expanded = addOpen,
                    onDismissRequest = { addOpen = false },
                    containerColor = MaterialTheme.appColors.menuBackground,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.voice_pill_message)) },
                        onClick = { addOpen = false; editing = -1 },
                    )
                    VoiceReportPlan.KNOWN.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(voiceReportLabel(item)) },
                            onClick = {
                                addOpen = false
                                commit(items + Keyed(nextId++, VoicePill(item)))
                            },
                        )
                    }
                }
            }
        }

        if (items.isEmpty()) {
            Text(
                stringResource(R.string.voice_pills_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
            )
        }

        val haptic = LocalHapticFeedback.current
        ReorderableColumn(
            list = items,
            onSettle = { from, to ->
                commit(items.toMutableList().apply { add(to, removeAt(from)) })
            },
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { index, entry, _ ->
            key(entry.id) {
                PillRow(
                    pill = entry.pill,
                    dragHandleModifier = Modifier.draggableHandle(),
                    onStat = { stat ->
                        commit(items.toMutableList().also { it[index] = entry.copy(pill = entry.pill.copy(stat = stat)) })
                    },
                    onEditMessage = { editing = index },
                    onRemove = { commit(items.filterIndexed { i, _ -> i != index }) },
                )
            }
        }
    }

    editing?.let { index ->
        MessageDialog(
            initial = if (index >= 0) items.getOrNull(index)?.pill?.text.orEmpty() else "",
            onDismiss = { editing = null },
            onSave = { text ->
                val pill = VoicePill(VoicePill.MESSAGE, text = text)
                commit(
                    if (index >= 0) items.toMutableList().also { it[index] = it[index].copy(pill = pill) }
                    else items + Keyed(nextId++, pill)
                )
                editing = null
            },
        )
    }
}

@Composable
private fun PillRow(
    pill: VoicePill,
    dragHandleModifier: Modifier,
    onStat: (VoicePill.Stat) -> Unit,
    onEditMessage: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = MaterialTheme.appColors
    val isMessage = pill.item == VoicePill.MESSAGE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.action_reorder),
            modifier = dragHandleModifier.size(28.dp),
            tint = c.textSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .background(c.surface, RoundedCornerShape(20.dp))
                .then(if (isMessage) Modifier.clickable(onClick = onEditMessage) else Modifier)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isMessage) "“${pill.text}”" else voiceReportLabel(pill.item),
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (isMessage) FontStyle.Italic else FontStyle.Normal,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (!isMessage && VoiceReportPlan.statKey(pill.item) != null) {
                StatPicker(pill.stat, onStat)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary)
        }
    }
}

@Composable
private fun StatPicker(current: VoicePill.Stat, onPick: (VoicePill.Stat) -> Unit) {
    val c = MaterialTheme.appColors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .background(
                    if (current == VoicePill.Stat.NOW) c.surfaceVariant else c.primary,
                    RoundedCornerShape(16.dp),
                )
                .clickable { open = true }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                statLabel(current),
                style = MaterialTheme.typography.labelLarge,
                color = if (current == VoicePill.Stat.NOW) c.textPrimary else c.onPrimary,
            )
            Icon(
                Icons.Default.ArrowDropDown, contentDescription = null,
                tint = if (current == VoicePill.Stat.NOW) c.textSecondary else c.onPrimary,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.menuBackground,
        ) {
            VoicePill.Stat.entries.forEach { stat ->
                DropdownMenuItem(
                    text = { Text(statLabel(stat)) },
                    onClick = { open = false; onPick(stat) },
                )
            }
        }
    }
}

@Composable
private fun MessageDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Rule 11: a stray tap must not drop what the rider is typing.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.voice_pill_message)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(VoicePill.MESSAGE_MAX) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.voice_pill_message_hint)) },
                colors = themedFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_save), color = MaterialTheme.appColors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.appColors.primary)
            }
        },
        containerColor = MaterialTheme.appColors.dialog,
    )
}


@Composable
private fun statLabel(stat: VoicePill.Stat): String = stringResource(
    when (stat) {
        VoicePill.Stat.NOW -> R.string.voice_stat_now
        VoicePill.Stat.MAX -> R.string.voice_stat_max
        VoicePill.Stat.MIN -> R.string.voice_stat_min
        VoicePill.Stat.AVG -> R.string.voice_stat_avg
        VoicePill.Stat.PEAK -> R.string.voice_stat_peak
    }
)

/** The name a report goes by in the editor, the same the old switch rows used. */
@Composable
internal fun voiceReportLabel(item: String): String {
    VoiceReportPlan.extra(item)?.let { spec ->
        val metric = com.eried.eucplanet.data.model.MetricCatalog.all.first { it.key == spec.metricKey }
        return stringResource(metric.labelRes)
    }
    return stringResource(
        when (item) {
            "Speed" -> R.string.report_speed
            "Battery" -> R.string.report_battery
            "PhoneBattery" -> R.string.report_phone_battery
            "Temp" -> R.string.report_temp
            "PWM" -> R.string.report_pwm
            "Current" -> R.string.report_current
            "Power" -> R.string.report_power
            "Distance" -> R.string.report_distance
            "Recording" -> R.string.report_recording
            "Time" -> R.string.report_time
            else -> R.string.report_navigation
        }
    )
}
