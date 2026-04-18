package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.util.Units

private fun displayThreshold(metric: AlarmMetric, valueInternal: Float, imperial: Boolean): Float =
    when (metric) {
        AlarmMetric.SPEED -> Units.speed(valueInternal, imperial)
        AlarmMetric.TEMPERATURE -> Units.temperature(valueInternal, imperial)
        else -> valueInternal
    }

private fun internalThreshold(metric: AlarmMetric, valueDisplayed: Float, imperial: Boolean): Float =
    when {
        !imperial -> valueDisplayed
        metric == AlarmMetric.SPEED -> valueDisplayed / 0.621371f
        metric == AlarmMetric.TEMPERATURE -> (valueDisplayed - 32f) * 5f / 9f
        else -> valueDisplayed
    }

private fun displayUnit(metric: AlarmMetric, imperial: Boolean): String =
    when (metric) {
        AlarmMetric.SPEED -> Units.speedUnit(imperial)
        AlarmMetric.TEMPERATURE -> Units.tempUnit(imperial)
        else -> metric.unit
    }

@Composable
fun AlarmSettingsContent(
    viewModel: AlarmViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val imperial by viewModel.imperialUnits.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AlarmRule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.alarm_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.alarm_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        rules.forEachIndexed { index, rule ->
            AlarmRuleCard(
                rule = rule,
                imperial = imperial,
                isFirst = index == 0,
                isLast = index == rules.lastIndex,
                onToggle = { viewModel.updateRule(rule.copy(enabled = it)) },
                onEdit = { editingRule = rule; showEditor = true },
                onDelete = { viewModel.deleteRule(rule) },
                onMoveUp = { viewModel.moveUp(rule) },
                onMoveDown = { viewModel.moveDown(rule) }
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { editingRule = null; showEditor = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.alarm_add))
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showEditor) {
        AlarmRuleEditorDialog(
            rule = editingRule,
            imperial = imperial,
            onSave = { rule ->
                if (editingRule != null) viewModel.updateRule(rule)
                else viewModel.addRule(rule)
                showEditor = false
            },
            onDismiss = { showEditor = false },
            onPreviewBeep = { freq, dur, cnt -> viewModel.previewBeep(freq, dur, cnt) },
            onPreviewVoice = { text, metric -> viewModel.previewVoice(text, metric) },
            onPreviewVibrate = { dur -> viewModel.previewVibrate(dur) }
        )
    }
}

@Composable
private fun AlarmRuleCard(
    rule: AlarmRule,
    imperial: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val metric = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val comp = try { AlarmComparator.valueOf(rule.comparator) } catch (_: Exception) { AlarmComparator.GREATER_THAN }

    val color = when {
        !rule.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> when (metric) {
            AlarmMetric.SPEED -> AccentOrange
            AlarmMetric.BATTERY -> AccentGreen
            AlarmMetric.TEMPERATURE -> AccentRed
            AlarmMetric.PWM -> AccentOrange
            else -> AccentBlue
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val shownThresh = displayThreshold(metric, rule.threshold, imperial).toInt()
                    val shownUnit = displayUnit(metric, imperial)
                    val metricLabel = stringResource(metric.labelRes)
                    Text(
                        rule.name.ifBlank { "$metricLabel ${comp.symbol} ${shownThresh}${shownUnit}" },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = color
                    )
                    // Action summary
                    val beepSummary = stringResource(R.string.alarm_summary_beep_fmt, rule.beepFrequency, rule.beepCount)
                    val voiceSummary = stringResource(R.string.alarm_summary_voice)
                    val vibrateSummary = stringResource(R.string.alarm_summary_vibrate)
                    val actions = buildList {
                        if (rule.beepEnabled) add(beepSummary)
                        if (rule.voiceEnabled) add(voiceSummary)
                        if (rule.vibrateEnabled) add(vibrateSummary)
                    }
                    if (actions.isNotEmpty()) {
                        Text(
                            actions.joinToString(" + "),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!isFirst) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowUpward, stringResource(R.string.action_move_up), modifier = Modifier.size(16.dp))
                    }
                }
                if (!isLast) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowDownward, stringResource(R.string.action_move_down), modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), modifier = Modifier.size(16.dp),
                        tint = AccentRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmRuleEditorDialog(
    rule: AlarmRule?,
    imperial: Boolean,
    onSave: (AlarmRule) -> Unit,
    onDismiss: () -> Unit,
    onPreviewBeep: (Int, Int, Int) -> Unit,
    onPreviewVoice: (String, AlarmMetric) -> Unit,
    onPreviewVibrate: (Int) -> Unit
) {
    val initial = rule ?: AlarmRule()

    var name by remember { mutableStateOf(initial.name) }
    var metric by remember { mutableStateOf(initial.metric) }
    var comparator by remember { mutableStateOf(initial.comparator) }
    var threshold by remember { mutableFloatStateOf(initial.threshold) }

    var beepEnabled by remember { mutableStateOf(initial.beepEnabled) }
    var beepFrequency by remember { mutableIntStateOf(initial.beepFrequency) }
    var beepDurationMs by remember { mutableIntStateOf(initial.beepDurationMs) }
    var beepCount by remember { mutableIntStateOf(initial.beepCount) }

    var voiceEnabled by remember { mutableStateOf(initial.voiceEnabled) }
    var voiceText by remember { mutableStateOf(initial.voiceText) }

    var vibrateEnabled by remember { mutableStateOf(initial.vibrateEnabled) }
    var vibrateDurationMs by remember { mutableIntStateOf(initial.vibrateDurationMs) }

    var cooldownSeconds by remember { mutableIntStateOf(initial.cooldownSeconds) }
    var repeatWhileActive by remember { mutableStateOf(initial.repeatWhileActive) }

    val selectedMetric = try { AlarmMetric.valueOf(metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val thresholdRangeInternal = when (selectedMetric) {
        AlarmMetric.SPEED -> 5f..100f
        AlarmMetric.BATTERY -> 0f..100f
        AlarmMetric.TEMPERATURE -> 20f..80f
        AlarmMetric.PWM -> 10f..100f
        AlarmMetric.VOLTAGE -> 50f..130f
        AlarmMetric.CURRENT -> 1f..60f
    }
    val displayedThreshold = displayThreshold(selectedMetric, threshold, imperial)
    val displayedRange = displayThreshold(selectedMetric, thresholdRangeInternal.start, imperial)..
        displayThreshold(selectedMetric, thresholdRangeInternal.endInclusive, imperial)
    val displayedUnit = displayUnit(selectedMetric, imperial)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (rule != null) stringResource(R.string.alarm_edit) else stringResource(R.string.alarm_new),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.alarm_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // --- Condition ---
                Text(stringResource(R.string.alarm_section_condition), fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = AccentBlue)

                Spacer(Modifier.height(6.dp))

                // Metric dropdown
                val metricOptions = AlarmMetric.entries.map { it.name to stringResource(it.labelRes) }
                DropdownSelect(
                    label = stringResource(R.string.alarm_metric_label),
                    selected = stringResource(selectedMetric.labelRes),
                    options = metricOptions,
                    onSelect = { metric = it }
                )

                Spacer(Modifier.height(6.dp))

                // Comparator dropdown
                val selectedComp = try { AlarmComparator.valueOf(comparator) } catch (_: Exception) { AlarmComparator.GREATER_THAN }
                val comparatorOptions = AlarmComparator.entries.map { it.name to "${it.symbol} ${stringResource(it.labelRes)}" }
                DropdownSelect(
                    label = stringResource(R.string.alarm_comparator_label),
                    selected = stringResource(selectedComp.labelRes),
                    options = comparatorOptions,
                    onSelect = { comparator = it }
                )

                Spacer(Modifier.height(6.dp))

                // Threshold slider
                Text(stringResource(R.string.alarm_threshold_fmt, "%.0f".format(displayedThreshold), displayedUnit),
                    fontSize = 13.sp)
                Slider(
                    value = displayedThreshold.coerceIn(displayedRange),
                    onValueChange = {
                        threshold = internalThreshold(selectedMetric, it, imperial)
                            .coerceIn(thresholdRangeInternal)
                    },
                    valueRange = displayedRange,
                    steps = ((displayedRange.endInclusive - displayedRange.start) - 1).toInt().coerceAtLeast(0)
                )

                Spacer(Modifier.height(12.dp))

                // --- Beep ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_beep),
                    color = AccentOrange,
                    enabled = beepEnabled,
                    onPreview = { onPreviewBeep(beepFrequency, beepDurationMs, beepCount) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_beep), fontSize = 13.sp)
                    Switch(checked = beepEnabled, onCheckedChange = { beepEnabled = it })
                }

                if (beepEnabled) {
                    Text(stringResource(R.string.alarm_beep_freq_fmt, beepFrequency), fontSize = 12.sp)
                    Slider(
                        value = beepFrequency.toFloat(),
                        onValueChange = { beepFrequency = it.toInt() },
                        valueRange = 400f..3000f,
                        steps = 25
                    )

                    Text(stringResource(R.string.alarm_beep_duration_fmt, beepDurationMs), fontSize = 12.sp)
                    Slider(
                        value = beepDurationMs.toFloat(),
                        onValueChange = { beepDurationMs = it.toInt() },
                        valueRange = 100f..1000f,
                        steps = 8
                    )

                    Text(stringResource(R.string.alarm_beep_repeats_fmt, beepCount), fontSize = 12.sp)
                    Slider(
                        value = beepCount.toFloat(),
                        onValueChange = { beepCount = it.toInt() },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Voice ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_voice),
                    color = AccentGreen,
                    enabled = voiceEnabled,
                    onPreview = { onPreviewVoice(voiceText, selectedMetric) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_voice), fontSize = 13.sp)
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }

                if (voiceEnabled) {
                    OutlinedTextField(
                        value = voiceText,
                        onValueChange = { voiceText = it },
                        label = { Text(stringResource(R.string.alarm_voice_template)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Text(
                        stringResource(R.string.alarm_voice_template_help),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Vibrate ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_vibrate),
                    color = AccentRed,
                    enabled = vibrateEnabled,
                    onPreview = { onPreviewVibrate(vibrateDurationMs) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_vibrate), fontSize = 13.sp)
                    Switch(checked = vibrateEnabled, onCheckedChange = { vibrateEnabled = it })
                }

                if (vibrateEnabled) {
                    Text(stringResource(R.string.alarm_vibrate_duration_fmt, vibrateDurationMs), fontSize = 12.sp)
                    Slider(
                        value = vibrateDurationMs.toFloat(),
                        onValueChange = { vibrateDurationMs = it.toInt() },
                        valueRange = 100f..2000f,
                        steps = 18
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Timing ---
                Text(stringResource(R.string.alarm_section_timing), fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = AccentBlue)

                Text(stringResource(R.string.alarm_cooldown_fmt, cooldownSeconds), fontSize = 12.sp)
                Slider(
                    value = cooldownSeconds.toFloat(),
                    onValueChange = { cooldownSeconds = it.toInt() },
                    valueRange = 3f..60f,
                    steps = 56
                )
                Text(
                    stringResource(R.string.alarm_cooldown_help),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_repeat), fontSize = 13.sp)
                    Switch(checked = repeatWhileActive, onCheckedChange = { repeatWhileActive = it })
                }
                Text(
                    stringResource(R.string.alarm_repeat_help),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                val example = if (repeatWhileActive) {
                    stringResource(R.string.alarm_repeat_on_example_fmt, cooldownSeconds)
                } else {
                    stringResource(R.string.alarm_repeat_off_example_fmt, cooldownSeconds)
                }
                Text(
                    example,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                Spacer(Modifier.height(16.dp))

                // --- Actions ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(
                            (rule ?: AlarmRule()).copy(
                                name = name,
                                metric = metric,
                                comparator = comparator,
                                threshold = threshold,
                                beepEnabled = beepEnabled,
                                beepFrequency = beepFrequency,
                                beepDurationMs = beepDurationMs,
                                beepCount = beepCount,
                                voiceEnabled = voiceEnabled,
                                voiceText = voiceText,
                                vibrateEnabled = vibrateEnabled,
                                vibrateDurationMs = vibrateDurationMs,
                                cooldownSeconds = cooldownSeconds,
                                repeatWhileActive = repeatWhileActive
                            )
                        )
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitleWithPreview(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onPreview: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = color)
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = onPreview,
            enabled = enabled,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.alarm_preview_fmt, title),
                modifier = Modifier.size(14.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSelect(
    label: String,
    selected: String,
    options: List<Pair<String, String>>, // value to displayLabel
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, displayLabel) ->
                DropdownMenuItem(
                    text = { Text(displayLabel) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
