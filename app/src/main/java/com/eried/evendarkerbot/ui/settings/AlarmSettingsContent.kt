package com.eried.evendarkerbot.ui.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.evendarkerbot.data.model.AlarmComparator
import com.eried.evendarkerbot.data.model.AlarmMetric
import com.eried.evendarkerbot.data.model.AlarmRule
import com.eried.evendarkerbot.ui.theme.AccentBlue
import com.eried.evendarkerbot.ui.theme.AccentGreen
import com.eried.evendarkerbot.ui.theme.AccentOrange
import com.eried.evendarkerbot.ui.theme.AccentRed

@Composable
fun AlarmSettingsContent(
    viewModel: AlarmViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
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
            "Rules are checked top-to-bottom per metric. First matching rule for each metric fires.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // Template variables help
        Text(
            "Voice variables: {speed} {battery} {temp} {pwm} {voltage} {current} {trip} {value} {metric} {threshold}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        Spacer(Modifier.height(12.dp))

        if (rules.isEmpty()) {
            Text(
                "No alarm rules configured.\nTap + to add one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        rules.forEachIndexed { index, rule ->
            AlarmRuleCard(
                rule = rule,
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
            Text("Add Alarm Rule")
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showEditor) {
        AlarmRuleEditorDialog(
            rule = editingRule,
            onSave = { rule ->
                if (editingRule != null) viewModel.updateRule(rule)
                else viewModel.addRule(rule)
                showEditor = false
            },
            onDismiss = { showEditor = false },
            onPreviewBeep = { freq, dur, cnt -> viewModel.previewBeep(freq, dur, cnt) },
            onPreviewVoice = { text -> viewModel.previewVoice(text) },
            onPreviewVibrate = { dur -> viewModel.previewVibrate(dur) }
        )
    }
}

@Composable
private fun AlarmRuleCard(
    rule: AlarmRule,
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
                    Text(
                        rule.name.ifBlank { "${metric.label} ${comp.symbol} ${rule.threshold.toInt()}${metric.unit}" },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = color
                    )
                    // Action summary
                    val actions = buildList {
                        if (rule.beepEnabled) add("Beep ${rule.beepFrequency}Hz×${rule.beepCount}")
                        if (rule.voiceEnabled) add("Voice")
                        if (rule.vibrateEnabled) add("Vibrate")
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
                        Icon(Icons.Default.ArrowUpward, "Move up", modifier = Modifier.size(16.dp))
                    }
                }
                if (!isLast) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowDownward, "Move down", modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(16.dp),
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
    onSave: (AlarmRule) -> Unit,
    onDismiss: () -> Unit,
    onPreviewBeep: (Int, Int, Int) -> Unit,
    onPreviewVoice: (String) -> Unit,
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
    val thresholdRange = when (selectedMetric) {
        AlarmMetric.SPEED -> 5f..100f
        AlarmMetric.BATTERY -> 0f..100f
        AlarmMetric.TEMPERATURE -> 20f..80f
        AlarmMetric.PWM -> 10f..100f
        AlarmMetric.VOLTAGE -> 50f..130f
        AlarmMetric.CURRENT -> 1f..60f
    }

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
                    if (rule != null) "Edit Alarm" else "New Alarm",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // --- Condition ---
                Text("Condition", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = AccentBlue)

                Spacer(Modifier.height(6.dp))

                // Metric dropdown
                DropdownSelect(
                    label = "Metric",
                    selected = selectedMetric.label,
                    options = AlarmMetric.entries.map { it.name to it.label },
                    onSelect = { metric = it }
                )

                Spacer(Modifier.height(6.dp))

                // Comparator dropdown
                val selectedComp = try { AlarmComparator.valueOf(comparator) } catch (_: Exception) { AlarmComparator.GREATER_THAN }
                DropdownSelect(
                    label = "Condition",
                    selected = selectedComp.label,
                    options = AlarmComparator.entries.map { it.name to "${it.symbol} ${it.label}" },
                    onSelect = { comparator = it }
                )

                Spacer(Modifier.height(6.dp))

                // Threshold slider
                Text("Threshold: ${"%.0f".format(threshold)} ${selectedMetric.unit}",
                    fontSize = 13.sp)
                Slider(
                    value = threshold.coerceIn(thresholdRange),
                    onValueChange = { threshold = it },
                    valueRange = thresholdRange,
                    steps = ((thresholdRange.endInclusive - thresholdRange.start) - 1).toInt().coerceAtLeast(0)
                )

                Spacer(Modifier.height(12.dp))

                // --- Beep ---
                SectionTitleWithPreview(
                    title = "Beep",
                    color = AccentOrange,
                    enabled = beepEnabled,
                    onPreview = { onPreviewBeep(beepFrequency, beepDurationMs, beepCount) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable beep", fontSize = 13.sp)
                    Switch(checked = beepEnabled, onCheckedChange = { beepEnabled = it })
                }

                if (beepEnabled) {
                    Text("Frequency: ${beepFrequency}Hz", fontSize = 12.sp)
                    Slider(
                        value = beepFrequency.toFloat(),
                        onValueChange = { beepFrequency = it.toInt() },
                        valueRange = 400f..3000f,
                        steps = 25
                    )

                    Text("Duration: ${beepDurationMs}ms", fontSize = 12.sp)
                    Slider(
                        value = beepDurationMs.toFloat(),
                        onValueChange = { beepDurationMs = it.toInt() },
                        valueRange = 100f..1000f,
                        steps = 8
                    )

                    Text("Repeats: $beepCount", fontSize = 12.sp)
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
                    title = "Voice",
                    color = AccentGreen,
                    enabled = voiceEnabled,
                    onPreview = { onPreviewVoice(voiceText) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable voice", fontSize = 13.sp)
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }

                if (voiceEnabled) {
                    OutlinedTextField(
                        value = voiceText,
                        onValueChange = { voiceText = it },
                        label = { Text("Text template") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Text(
                        "Use {speed}, {battery}, {temp}, {value}, {metric}, etc.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Vibrate ---
                SectionTitleWithPreview(
                    title = "Vibrate",
                    color = AccentRed,
                    enabled = vibrateEnabled,
                    onPreview = { onPreviewVibrate(vibrateDurationMs) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable vibration", fontSize = 13.sp)
                    Switch(checked = vibrateEnabled, onCheckedChange = { vibrateEnabled = it })
                }

                if (vibrateEnabled) {
                    Text("Duration: ${vibrateDurationMs}ms", fontSize = 12.sp)
                    Slider(
                        value = vibrateDurationMs.toFloat(),
                        onValueChange = { vibrateDurationMs = it.toInt() },
                        valueRange = 100f..2000f,
                        steps = 18
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Timing ---
                Text("Timing", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = AccentBlue)

                Text("Cooldown: ${cooldownSeconds}s", fontSize = 12.sp)
                Slider(
                    value = cooldownSeconds.toFloat(),
                    onValueChange = { cooldownSeconds = it.toInt() },
                    valueRange = 3f..60f,
                    steps = 56
                )
                Text(
                    "Minimum time between fires for this rule, even if the condition keeps matching.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Repeat while active", fontSize = 13.sp)
                    Switch(checked = repeatWhileActive, onCheckedChange = { repeatWhileActive = it })
                }
                Text(
                    "OFF: fires once when the condition starts matching, then stays silent until it stops matching.\n" +
                    "ON: keeps re-firing every Cooldown seconds for as long as the condition is still true.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                val example = if (repeatWhileActive) {
                    "Example: speed > 30, cooldown ${cooldownSeconds}s, repeat ON — if you hold 35 km/h for a minute you'll be warned every ${cooldownSeconds}s."
                } else {
                    "Example: speed > 30, cooldown ${cooldownSeconds}s, repeat OFF — one warning when you cross 30, then silent; you must drop below 30 (and wait ${cooldownSeconds}s) before it can fire again."
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
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
                        Text("Save")
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
                contentDescription = "Preview $title",
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
