package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentOrange
import com.eried.eucplanet.ui.theme.AccentPurple
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.ui.theme.AccentYellow
import com.eried.eucplanet.util.Units

private fun displayThreshold(metric: AlarmMetric, valueInternal: Float, speedUnit: String, tempUnit: String): Float =
    when (metric) {
        AlarmMetric.SPEED -> Units.speed(valueInternal, speedUnit)
        AlarmMetric.TEMPERATURE -> Units.temperature(valueInternal, tempUnit)
        else -> valueInternal
    }

private fun internalThreshold(metric: AlarmMetric, valueDisplayed: Float, speedUnit: String, tempUnit: String): Float =
    when {
        metric == AlarmMetric.SPEED -> Units.speedToKmh(valueDisplayed, speedUnit)
        metric == AlarmMetric.TEMPERATURE -> Units.temperatureToCelsius(valueDisplayed, tempUnit)
        else -> valueDisplayed
    }

@androidx.compose.runtime.Composable
private fun displayUnit(metric: AlarmMetric, speedUnit: String, tempUnit: String): String =
    when (metric) {
        AlarmMetric.SPEED -> Units.speedUnit(androidx.compose.ui.platform.LocalContext.current, speedUnit)
        AlarmMetric.TEMPERATURE -> Units.tempUnit(tempUnit)
        else -> metric.unit
    }

@Composable
fun AlarmSettingsContent(
    viewModel: AlarmViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    // Alarm thresholds only span speed and temperature; distance has no alarm metric.
    val speedUnit by viewModel.speedUnit.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteCandidate by remember { mutableStateOf<AlarmRule?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        HintText(stringResource(R.string.alarm_help), small = true)

        Spacer(Modifier.height(12.dp))

        if (rules.isEmpty()) {
            InfoHint(
                text = stringResource(R.string.alarm_empty),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        rules.forEachIndexed { index, rule ->
            AlarmRuleCard(
                rule = rule,
                speedUnit = speedUnit,
                tempUnit = tempUnit,
                isFirst = index == 0,
                isLast = index == rules.lastIndex,
                onToggle = { viewModel.updateRule(rule.copy(enabled = it)) },
                onEdit = { editingRule = rule; showEditor = true },
                onDelete = { deleteCandidate = rule },
                onMoveUp = { viewModel.moveUp(rule) },
                onMoveDown = { viewModel.moveDown(rule) }
            )
            Spacer(Modifier.height(8.dp))
        }

        LeftAlignedScanButton(
            label = stringResource(R.string.alarm_add),
            onClick = { editingRule = null; showEditor = true },
            leadingIcon = Icons.Default.Add
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showEditor) {
        AlarmRuleEditorDialog(
            rule = editingRule,
            speedUnit = speedUnit,
            tempUnit = tempUnit,
            onSave = { rule ->
                if (editingRule != null) viewModel.updateRule(rule)
                else viewModel.addRule(rule)
                showEditor = false
            },
            onDismiss = { showEditor = false },
            onPreviewBeep = { freq, dur, cnt -> viewModel.previewBeep(freq, dur, cnt) },
            onPreviewVoice = { text, metric, thr -> viewModel.previewVoice(text, metric, thr) },
            onPreviewVibrate = { dur -> viewModel.previewVibrate(dur) }
        )
    }

    deleteCandidate?.let { rule ->
        val metric = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { AlarmMetric.SPEED }
        val comp = AlarmComparator.parse(rule.comparator)
        val shownThresh = displayThreshold(metric, rule.threshold, speedUnit, tempUnit).toInt()
        val shownUnit = displayUnit(metric, speedUnit, tempUnit)
        val metricLabel = stringResource(metric.labelRes)
        val label = rule.name.ifBlank { "$metricLabel ${comp.symbol} ${shownThresh}${shownUnit}" }
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = { Text(stringResource(R.string.alarm_delete_body, label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule)
                    deleteCandidate = null
                }) { Text(stringResource(R.string.action_delete), color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun AlarmRuleCard(
    rule: AlarmRule,
    speedUnit: String,
    tempUnit: String,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val metric = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val comp = AlarmComparator.parse(rule.comparator)

    val color = when {
        !rule.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        // One distinct colour per metric, so rules that share a metric read as
        // a visual group in the list.
        else -> when (metric) {
            AlarmMetric.SPEED -> AccentOrange
            AlarmMetric.BATTERY -> AccentGreen
            AlarmMetric.TEMPERATURE -> AccentRed
            AlarmMetric.PWM -> AccentYellow
            AlarmMetric.VOLTAGE -> AccentBlue
            AlarmMetric.CURRENT -> AccentPurple
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
                    val shownThresh = displayThreshold(metric, rule.threshold, speedUnit, tempUnit).toInt()
                    val shownUnit = displayUnit(metric, speedUnit, tempUnit)
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
    speedUnit: String,
    tempUnit: String,
    onSave: (AlarmRule) -> Unit,
    onDismiss: () -> Unit,
    onPreviewBeep: (Int, Int, Int) -> Unit,
    onPreviewVoice: (String, AlarmMetric, Float) -> Unit,
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
    var vibrateTarget by remember { mutableStateOf(initial.vibrateTarget) }

    var cooldownSeconds by remember { mutableIntStateOf(initial.cooldownSeconds) }
    var repeatWhileActive by remember { mutableStateOf(initial.repeatWhileActive) }

    val selectedMetric = try { AlarmMetric.valueOf(metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val thresholdRangeInternal = when (selectedMetric) {
        // Speed cap depends on the user's unit: high-performance wheels run
        // way past 100 km/h, so the range reads 5..150 km/h, 5..100 mph or
        // 1..40 m/s (each ceiling expressed back in km/h for the internal range).
        AlarmMetric.SPEED -> when (speedUnit) {
            "mph" -> Units.speedToKmh(5f, "mph")..Units.speedToKmh(100f, "mph")
            "ms" -> Units.speedToKmh(1f, "ms")..Units.speedToKmh(40f, "ms")
            else -> 5f..150f
        }
        AlarmMetric.BATTERY -> 0f..100f
        AlarmMetric.TEMPERATURE -> 20f..80f
        AlarmMetric.PWM -> 10f..100f
        AlarmMetric.VOLTAGE -> 20f..300f
        AlarmMetric.CURRENT -> 1f..50f
    }
    val displayedThreshold = displayThreshold(selectedMetric, threshold, speedUnit, tempUnit)
    val displayedRange = displayThreshold(selectedMetric, thresholdRangeInternal.start, speedUnit, tempUnit)..
        displayThreshold(selectedMetric, thresholdRangeInternal.endInclusive, speedUnit, tempUnit)
    val displayedUnit = displayUnit(selectedMetric, speedUnit, tempUnit)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Cap the dialog's height so the inner verticalScroll has a bounded
        // parent, without this the Column grows past the viewport and the
        // Save button at the bottom is unreachable on long forms. 88% leaves
        // room for the status bar and the IME (which the inner imePadding
        // takes care of).
        val maxDialogHeight = androidx.compose.ui.platform.LocalConfiguration.current
            .screenHeightDp.dp * 0.88f
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = maxDialogHeight)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (rule != null) stringResource(R.string.alarm_edit) else stringResource(R.string.alarm_new),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(12.dp))

                // Metric (60%) + comparator (40%) share a row so the editor
                // stays compact vertically. Comparator field shows just the
                // glyph (≥ or <) when collapsed but opens to full-word labels
                // so first-time users still understand what each option means.
                val metricOptions = AlarmMetric.entries.map { it.name to stringResource(it.labelRes) }
                val selectedComp = AlarmComparator.parse(comparator)
                val comparatorOptions = AlarmComparator.entries.map { entry ->
                    entry.name to stringResource(entry.labelRes)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(0.6f)) {
                        DropdownSelect(
                            label = stringResource(R.string.alarm_metric_label),
                            selected = stringResource(selectedMetric.labelRes),
                            options = metricOptions,
                            onSelect = { metric = it }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(0.4f)) {
                        DropdownSelect(
                            label = stringResource(R.string.alarm_comparator_label),
                            selected = selectedComp.symbol,
                            options = comparatorOptions,
                            onSelect = { comparator = it }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Threshold: slider for fast sweep, +/- buttons for fine
                // single-unit nudges. Tapping the value re-centres focus on the
                // step buttons (the slider is hard to drop on an exact number).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            // Step in display units so users get a clean 1 mph
                            // or 1 km/h nudge regardless of unit system.
                            val newDisp = (displayedThreshold - 1f).coerceIn(displayedRange)
                            threshold = internalThreshold(selectedMetric, newDisp, speedUnit, tempUnit)
                                .coerceIn(thresholdRangeInternal)
                        },
                        enabled = displayedThreshold > displayedRange.start + 0.001f,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = stringResource(R.string.alarm_threshold_decrease),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        stringResource(R.string.alarm_threshold_fmt, "%.0f".format(displayedThreshold), displayedUnit),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val newDisp = (displayedThreshold + 1f).coerceIn(displayedRange)
                            threshold = internalThreshold(selectedMetric, newDisp, speedUnit, tempUnit)
                                .coerceIn(thresholdRangeInternal)
                        },
                        enabled = displayedThreshold < displayedRange.endInclusive - 0.001f,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.alarm_threshold_increase),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Slider(
                    value = displayedThreshold.coerceIn(displayedRange),
                    onValueChange = {
                        threshold = internalThreshold(selectedMetric, it, speedUnit, tempUnit)
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
                        // 200 Hz floor gives a softer "thunk" useful for low-
                        // urgency reminders (e.g. battery dip). Top stays at
                        // 3 kHz where the piezo gets piercing.
                        valueRange = 200f..3000f,
                        steps = 27
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
                    onPreview = { onPreviewVoice(voiceText, selectedMetric, displayedThreshold) }
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
                    HintText(stringResource(R.string.alarm_voice_template_help), small = true)
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

                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.alarm_vibrate_target_label),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // "BOTH" is first + the default for new alarms, the rider doesn't
                    // need to think about whether a watch is paired or not (no watch
                    // connected → that branch silently no-ops, see WatchVibrator). The
                    // storage key stays "BOTH" for compatibility; the user-visible label
                    // is "All" since "Both" reads as "phone + watch only" while "All"
                    // covers any future channels (e.g., paired earbud haptics).
                    val targetEntries = listOf(
                        "BOTH" to stringResource(R.string.alarm_vibrate_target_both),
                        "PHONE" to stringResource(R.string.alarm_vibrate_target_phone),
                        "WATCH" to stringResource(R.string.alarm_vibrate_target_watch)
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                    ) {
                        targetEntries.forEachIndexed { index, (key, label) ->
                            SegmentedButton(
                                modifier = Modifier.fillMaxHeight(),
                                selected = key == vibrateTarget,
                                onClick = { vibrateTarget = key },
                                shape = SegmentedButtonDefaults.itemShape(index, targetEntries.size)
                            ) { Text(label) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- Timing ---
                Text(stringResource(R.string.alarm_section_timing), fontWeight = FontWeight.Medium, fontSize = 13.sp,
                    color = AccentBlue)

                Text(stringResource(R.string.alarm_cooldown_fmt, cooldownSeconds), fontSize = 12.sp)
                Slider(
                    value = cooldownSeconds.toFloat(),
                    onValueChange = {
                        // Snap to 5 s steps so the slider stops on a clean
                        // round number rather than landing on 17 s etc.
                        cooldownSeconds = (kotlin.math.round(it / 5f) * 5f)
                            .toInt().coerceIn(5, 60)
                    },
                    valueRange = 5f..60f,
                    steps = 10
                )
                HintText(stringResource(R.string.alarm_cooldown_help), small = true)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_repeat), fontSize = 13.sp)
                    Switch(checked = repeatWhileActive, onCheckedChange = { repeatWhileActive = it })
                }
                HintText(stringResource(R.string.alarm_repeat_help), small = true)

                Spacer(Modifier.height(16.dp))

                // --- Actions ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    val hasOutput = beepEnabled || voiceEnabled || vibrateEnabled
                    Button(
                        enabled = hasOutput,
                        onClick = {
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
                                    vibrateTarget = vibrateTarget,
                                    cooldownSeconds = cooldownSeconds,
                                    repeatWhileActive = repeatWhileActive
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }

                // Breathing room below the action row, without this the
                // buttons sit on the rounded corner of the card and look like
                // they're being clipped.
                Spacer(Modifier.height(12.dp))
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
