package com.eried.eucplanet.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
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
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedSegmentedColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.ui.theme.themedSliderColors
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableColumn

/** Human seconds for a lead time in ms: "0.5", "1", "2" (drops a trailing .0). */
private fun leadSeconds(ms: Int): String =
    String.format(java.util.Locale.US, "%.1f", ms / 1000f).removeSuffix(".0")

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
    val rulesSorted by viewModel.rulesSorted.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Alarm thresholds only span speed and temperature; distance has no alarm metric.
    val speedUnit by viewModel.speedUnit.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()
    val voiceLocale by viewModel.voiceLocale.collectAsState()
    val showRadarMetrics by viewModel.showRadarMetrics.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AlarmRule?>(null) }
    var deleteCandidate by remember { mutableStateOf<AlarmRule?>(null) }

    // Default alarm voice template, resolved in the rider's TTS voice locale
    // so a newly created alarm's prompt is in the language the engine
    // actually speaks. Placeholders like {metric}/{value} stay literal and
    // get expanded at speak time. Recomputed only when voiceLocale changes.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val defaultVoiceText = remember(voiceLocale) {
        val tag = voiceLocale.replace("_", "-")
        val locale = java.util.Locale.forLanguageTag(tag)
        val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
            setLocale(locale)
        }
        ctx.createConfigurationContext(cfg).getString(R.string.alarm_voice_default)
    }

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

        // Drag the handle to reorder (same component the settings voice list
        // uses). Tapping a card's text opens the editor.
        ReorderableColumn(
            list = rules,
            onSettle = { from, to -> viewModel.moveRule(from, to) },
            onMove = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { _, rule, _ ->
            key(rule.id) {
                AlarmRuleCard(
                    rule = rule,
                    speedUnit = speedUnit,
                    tempUnit = tempUnit,
                    onToggle = { viewModel.updateRule(rule.copy(enabled = it)) },
                    onEdit = { editingRule = rule; showEditor = true },
                    dragHandleModifier = Modifier.draggableHandle(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // New alarm + Auto-sort are always both shown at half width each, so the
        // layout doesn't jump as rules are added. Auto-sort stays disabled until
        // there are at least two rules AND they're out of order.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { editingRule = null; showEditor = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.alarm_add))
            }
            Button(
                onClick = { viewModel.autoSmartSort() },
                enabled = !rulesSorted,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.alarm_smart_sort))
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showEditor) {
        AlarmRuleEditorDialog(
            rule = editingRule,
            speedUnit = speedUnit,
            tempUnit = tempUnit,
            defaultVoiceText = defaultVoiceText,
            showRadarMetrics = showRadarMetrics,
            onSave = { rule ->
                if (editingRule != null) viewModel.updateRule(rule)
                else viewModel.addRule(rule)
                showEditor = false
            },
            onDismiss = { showEditor = false },
            onDelete = editingRule?.let { r -> { showEditor = false; deleteCandidate = r } },
            onPreviewBeep = { freq, dur, cnt, mod -> viewModel.previewBeep(freq, dur, cnt, mod) },
            onPreviewVoice = { text, metric, thr -> viewModel.previewVoice(text, metric, thr) },
            onPreviewVibrate = { dur -> viewModel.previewVibrate(dur) }
        )
    }

    deleteCandidate?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.alarm_delete_title)) },
            text = { Text(stringResource(R.string.alarm_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule)
                    deleteCandidate = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.appColors.statusDanger) }
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
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    val metric = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val comp = AlarmComparator.parse(rule.comparator)

    val color = when {
        !rule.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        // One distinct colour per metric, so rules that share a metric read as
        // a visual group in the list.
        else -> when (metric) {
            AlarmMetric.SPEED -> MaterialTheme.appColors.statusWarn
            AlarmMetric.BATTERY -> MaterialTheme.appColors.statusGood
            AlarmMetric.TEMPERATURE -> MaterialTheme.appColors.statusDanger
            AlarmMetric.PWM -> MaterialTheme.appColors.gaugeWarn
            AlarmMetric.VOLTAGE -> MaterialTheme.appColors.metricVoltage
            AlarmMetric.CURRENT -> MaterialTheme.appColors.metricPosition
            AlarmMetric.RADAR_DISTANCE -> MaterialTheme.appColors.statusDanger
            AlarmMetric.RADAR_APPROACH_SPEED -> MaterialTheme.appColors.statusDanger
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        // Drag handle | tap-to-edit text | enable switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.action_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier.size(28.dp)
            )
            Spacer(Modifier.width(4.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit() }
                    .padding(vertical = 12.dp)
            ) {
                val shownThresh = displayThreshold(metric, rule.threshold, speedUnit, tempUnit).roundToInt()
                val shownUnit = displayUnit(metric, speedUnit, tempUnit)
                val metricLabel = stringResource(metric.labelRes)
                Text(
                    rule.name.ifBlank { "$metricLabel ${comp.symbol} ${shownThresh}${shownUnit}" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = color
                )
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
                if (rule.leadTimeMs > 0) {
                    Text(
                        stringResource(R.string.alarm_summary_predict_fmt, leadSeconds(rule.leadTimeMs)),
                        fontSize = 11.sp,
                        color = color
                    )
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle, colors = themedSwitchColors())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmRuleEditorDialog(
    rule: AlarmRule?,
    speedUnit: String,
    tempUnit: String,
    defaultVoiceText: String,
    showRadarMetrics: Boolean,
    onSave: (AlarmRule) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onPreviewBeep: (Int, Int, Int, Int) -> Unit,
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
    var beepModulation by remember { mutableIntStateOf(initial.beepModulation) }

    var voiceEnabled by remember { mutableStateOf(initial.voiceEnabled) }
    // For a brand-new alarm, seed with the voice-locale-resolved default
    // (the AlarmRule data class can't reach into resources, so its baked-in
    // default is always English; the editor overrides it here so the user
    // sees and saves the localized phrase). When editing an existing rule
    // we keep whatever the user already saved.
    var voiceText by remember {
        mutableStateOf(if (rule == null) defaultVoiceText else initial.voiceText)
    }

    var vibrateEnabled by remember { mutableStateOf(initial.vibrateEnabled) }
    var vibrateDurationMs by remember { mutableIntStateOf(initial.vibrateDurationMs) }
    var vibrateTarget by remember { mutableStateOf(initial.vibrateTarget) }

    var cooldownSeconds by remember { mutableIntStateOf(initial.cooldownSeconds) }
    var repeatWhileActive by remember { mutableStateOf(initial.repeatWhileActive) }
    var leadTimeMs by remember { mutableIntStateOf(initial.leadTimeMs) }
    // Advanced (cooldown / repeat / anticipation) starts collapsed for a new
    // rule -- the defaults are fine -- and opens automatically when editing a
    // rule that already departs from them.
    val defaults = remember { AlarmRule() }
    var advancedOpen by remember {
        mutableStateOf(
            initial.cooldownSeconds != defaults.cooldownSeconds ||
                initial.repeatWhileActive != defaults.repeatWhileActive ||
                initial.leadTimeMs != defaults.leadTimeMs
        )
    }

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
        // Varia's range is ~140 m. Below 5 m the radar is effectively in
        // the dead-zone behind the rider; below 10 m is "imminent" on an
        // EUC at typical road speed.
        AlarmMetric.RADAR_DISTANCE -> 5f..140f
        // Approach speeds typically run 10-120 km/h depending on road
        // type. 5..150 keeps the slider usable on both ends.
        AlarmMetric.RADAR_APPROACH_SPEED -> 5f..150f
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
            ) {
                // Pinned title (stays put while the fields scroll).
                Text(
                    if (rule != null) stringResource(R.string.alarm_edit) else stringResource(R.string.alarm_new),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(12.dp))

                // Scrollable middle: only the fields scroll; title above and the
                // action row below stay pinned. fill = false lets a short form
                // wrap instead of always stretching to the max dialog height.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                // Metric (60%) + comparator (40%) share a row so the editor
                // stays compact vertically. Comparator field shows just the
                // glyph (≥ or <) when collapsed but opens to full-word labels
                // so first-time users still understand what each option means.
                // Radar metrics only appear in the dropdown when a radar is
                // paired or an existing rule already uses one (see
                // [AlarmViewModel.showRadarMetrics]). The currently-selected
                // metric stays in the list either way, so editing a rule
                // whose metric was just hidden never loses the selection.
                val radarMetricNames = setOf(
                    AlarmMetric.RADAR_DISTANCE.name,
                    AlarmMetric.RADAR_APPROACH_SPEED.name
                )
                val metricOptions = AlarmMetric.entries
                    .filter { showRadarMetrics || it.name !in radarMetricNames || it.name == metric }
                    .map { it.name to stringResource(it.labelRes) }
                val selectedComp = AlarmComparator.parse(comparator)
                val comparatorOptions = AlarmComparator.entries.map { entry ->
                    entry.name to stringResource(entry.labelRes)
                }
                // Metric, Condition and Threshold are all the same (half) width
                // as the Cooldown field for a consistent grid.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownSelect(
                            label = stringResource(R.string.alarm_metric_label),
                            selected = stringResource(selectedMetric.labelRes),
                            options = metricOptions,
                            onSelect = { metric = it }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DropdownSelect(
                            label = stringResource(R.string.alarm_comparator_label),
                            selected = selectedComp.symbol,
                            options = comparatorOptions,
                            onSelect = { comparator = it }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Threshold: numeric up/down, half width to match the fields above.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberUpDown(
                        value = displayedThreshold.roundToInt(),
                        onValueChange = { newDisp ->
                            threshold = internalThreshold(selectedMetric, newDisp.toFloat(), speedUnit, tempUnit)
                                .coerceIn(thresholdRangeInternal)
                        },
                        range = displayedRange.start.roundToInt()..displayedRange.endInclusive.roundToInt(),
                        suffix = displayedUnit,
                        label = stringResource(R.string.alarm_threshold_label),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))

                // --- Beep ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_beep),
                    color = MaterialTheme.appColors.statusWarn,
                    enabled = beepEnabled,
                    onPreview = { onPreviewBeep(beepFrequency, beepDurationMs, beepCount, beepModulation) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_beep), fontSize = 13.sp)
                    Switch(checked = beepEnabled, onCheckedChange = { beepEnabled = it },
                        colors = themedSwitchColors(),)
                }

                if (beepEnabled) {
                    // Frequency + Duration share a row; the unit (Hz / ms) tells
                    // them apart. 200 Hz floor = soft "thunk", 3 kHz = piercing.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NumberUpDown(
                            value = beepFrequency,
                            onValueChange = { beepFrequency = it },
                            range = 200..3000, step = 100, suffix = "Hz",
                            label = stringResource(R.string.alarm_label_frequency),
                            modifier = Modifier.weight(1f),
                        )
                        NumberUpDown(
                            value = beepDurationMs,
                            onValueChange = { beepDurationMs = it },
                            range = 100..1000, step = 50, suffix = "ms",
                            label = stringResource(R.string.alarm_label_duration),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Repeat count is a small value, so it stays half-width rather
                    // than stretching across the dialog.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NumberUpDown(
                            value = beepCount,
                            onValueChange = { beepCount = it },
                            range = 1..5, step = 1, suffix = "x",
                            label = stringResource(R.string.alarm_label_repeats),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    // Pitch: Fixed (always beepFrequency) or Rises with severity.
                    Text(
                        stringResource(R.string.alarm_beep_pitch_label),
                        fontSize = 12.sp,
                        color = MaterialTheme.appColors.fieldLabel,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    val pitchEntries = listOf(
                        0 to stringResource(R.string.alarm_beep_pitch_fixed),
                        1 to stringResource(R.string.alarm_beep_pitch_rise),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        pitchEntries.forEachIndexed { index, (value, lbl) ->
                            SegmentedButton(
                                modifier = Modifier.fillMaxHeight(),
                                selected = value == beepModulation,
                                onClick = { beepModulation = value },
                                shape = SegmentedButtonDefaults.itemShape(index, pitchEntries.size),
                                colors = themedSegmentedColors(),
                            ) { Text(lbl) }
                        }
                    }
                    val metricName = stringResource(selectedMetric.labelRes)
                    val beepCap = (beepFrequency * 2).coerceAtMost(4000)
                    HintText(
                        when {
                            beepModulation == 1 ->
                                stringResource(R.string.alarm_beep_help_rise, beepFrequency, metricName, beepCap)
                            beepCount > 1 ->
                                stringResource(R.string.alarm_beep_help_fixed_multi, beepFrequency, beepDurationMs, beepCount)
                            else ->
                                stringResource(R.string.alarm_beep_help_fixed_single, beepFrequency, beepDurationMs)
                        },
                        small = true
                    )
                }

                Spacer(Modifier.height(8.dp))

                // --- Voice ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_voice),
                    color = MaterialTheme.appColors.statusGood,
                    enabled = voiceEnabled,
                    onPreview = { onPreviewVoice(voiceText, selectedMetric, displayedThreshold) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_voice), fontSize = 13.sp)
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it },
                        colors = themedSwitchColors(),)
                }

                if (voiceEnabled) {
                    OutlinedTextField(
                        value = voiceText,
                        onValueChange = { voiceText = it },
                        label = { Text(stringResource(R.string.alarm_voice_template)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        colors = themedFieldColors(),
                    )
                    HintText(stringResource(R.string.alarm_voice_template_help), small = true)
                }

                Spacer(Modifier.height(8.dp))

                // --- Vibrate ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_vibrate),
                    color = MaterialTheme.appColors.statusDanger,
                    enabled = vibrateEnabled,
                    onPreview = { onPreviewVibrate(vibrateDurationMs) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_enable_vibrate), fontSize = 13.sp)
                    Switch(checked = vibrateEnabled, onCheckedChange = { vibrateEnabled = it },
                        colors = themedSwitchColors(),)
                }

                if (vibrateEnabled) {
                    // Duration on its own row (half width, matching the other
                    // fields), then the All/Phone/Watch target on a second line.
                    // "BOTH" stays first + default; the storage key stays "BOTH"
                    // for backup/sync compatibility while the visible label is "All".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NumberUpDown(
                            value = vibrateDurationMs,
                            onValueChange = { vibrateDurationMs = it },
                            range = 100..2000, step = 100, suffix = "ms",
                            label = stringResource(R.string.alarm_label_duration),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.alarm_label_vibrate_on),
                        fontSize = 12.sp,
                        color = MaterialTheme.appColors.fieldLabel
                    )
                    val targetEntries = listOf(
                        "BOTH" to stringResource(R.string.alarm_vibrate_target_both),
                        "PHONE" to stringResource(R.string.alarm_vibrate_target_phone),
                        "WATCH" to stringResource(R.string.alarm_vibrate_target_watch)
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        targetEntries.forEachIndexed { index, (key, label) ->
                            SegmentedButton(
                                modifier = Modifier.fillMaxHeight(),
                                selected = key == vibrateTarget,
                                onClick = { vibrateTarget = key },
                                shape = SegmentedButtonDefaults.itemShape(index, targetEntries.size),
                                colors = themedSegmentedColors(),
                            ) { Text(label) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // --- Advanced (collapsed) ---
                // Cooldown, repeat mode and anticipation live here. Their
                // defaults (5 s, Single, Realtime) suit most alarms, so a new
                // rule never needs to open this. It auto-opens when editing a
                // rule that already uses non-default values.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedOpen = !advancedOpen },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.alarm_section_advanced),
                        fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        color = MaterialTheme.appColors.metricVoltage)
                    Icon(
                        if (advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.appColors.metricVoltage
                    )
                }

                if (advancedOpen) {
                    Spacer(Modifier.height(8.dp))

                    // Build the live "condition" phrases once so every help line
                    // reads back the rule the rider actually set (metric name,
                    // threshold + unit, and the comparator direction). "triggered"
                    // is the alarm side; "safe" is its inverse (for the re-arm).
                    val metricName = stringResource(selectedMetric.labelRes)
                    val thrText = "${displayedThreshold.roundToInt()} $displayedUnit"
                    val isGe = AlarmComparator.parse(comparator) == AlarmComparator.GREATER_EQUAL
                    val triggeredPhrase = stringResource(
                        if (isGe) R.string.alarm_state_atleast else R.string.alarm_state_below,
                        metricName, thrText
                    )
                    val safePhrase = stringResource(
                        if (isGe) R.string.alarm_state_below else R.string.alarm_state_atleast,
                        metricName, thrText
                    )

                    // Cooldown (half width) + Single/Multi repeat selector beside it.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        NumberUpDown(
                            value = cooldownSeconds,
                            onValueChange = { cooldownSeconds = it },
                            range = 0..120,
                            suffix = "s",
                            label = stringResource(R.string.alarm_cooldown_label),
                            modifier = Modifier.weight(1f),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.alarm_repeat_label),
                                fontSize = 12.sp,
                                color = MaterialTheme.appColors.fieldLabel,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            val repeatEntries = listOf(
                                false to stringResource(R.string.alarm_repeat_single),
                                true to stringResource(R.string.alarm_repeat_multi),
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                repeatEntries.forEachIndexed { index, (value, lbl) ->
                                    SegmentedButton(
                                        modifier = Modifier.fillMaxHeight(),
                                        selected = value == repeatWhileActive,
                                        onClick = { repeatWhileActive = value },
                                        shape = SegmentedButtonDefaults.itemShape(index, repeatEntries.size),
                                        colors = themedSegmentedColors(),
                                    ) { Text(lbl) }
                                }
                            }
                        }
                    }
                    HintText(
                        when {
                            // Cooldown 0 = no rate limit. In Many mode that means a
                            // fresh alert on every telemetry reading while held; in
                            // Once mode it re-arms the instant the value goes safe.
                            repeatWhileActive && cooldownSeconds == 0 ->
                                stringResource(R.string.alarm_repeat_help_many_instant, triggeredPhrase)
                            repeatWhileActive ->
                                stringResource(R.string.alarm_repeat_help_many, cooldownSeconds, triggeredPhrase)
                            cooldownSeconds == 0 ->
                                stringResource(R.string.alarm_repeat_help_once_instant, triggeredPhrase, safePhrase)
                            else ->
                                stringResource(R.string.alarm_repeat_help_once, triggeredPhrase, safePhrase, cooldownSeconds)
                        },
                        small = true
                    )

                    Spacer(Modifier.height(8.dp))

                    // Anticipation as a compact segmented row: Realtime (0) keeps
                    // the classic fire-on-threshold behaviour; the others fire
                    // early when the recent trend is about to cross the threshold.
                    Text(stringResource(R.string.alarm_predict_label),
                        fontSize = 12.sp, color = MaterialTheme.appColors.fieldLabel)
                    val realtimeLabel = stringResource(R.string.alarm_predict_realtime)
                    val leadValues = listOf(0, 500, 1000, 2000, 3000)
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        leadValues.forEachIndexed { index, ms ->
                            SegmentedButton(
                                modifier = Modifier.fillMaxHeight(),
                                selected = leadTimeMs == ms,
                                onClick = { leadTimeMs = ms },
                                shape = SegmentedButtonDefaults.itemShape(index, leadValues.size),
                                colors = themedSegmentedColors(),
                                icon = {},
                            ) {
                                Text(
                                    if (ms == 0) realtimeLabel else "${leadSeconds(ms)}s",
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    HintText(
                        if (leadTimeMs <= 0)
                            stringResource(R.string.alarm_predict_help_now, triggeredPhrase)
                        else
                            stringResource(R.string.alarm_predict_help_lead, leadSeconds(leadTimeMs), triggeredPhrase),
                        small = true
                    )
                }
                } // end scrollable middle

                Spacer(Modifier.height(16.dp))

                // --- Actions --- (pinned to the bottom)
                // Delete sits bottom-left (only when editing an existing rule);
                // Cancel / Save stay bottom-right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rule != null && onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(
                                stringResource(R.string.alarm_delete_action),
                                color = MaterialTheme.appColors.statusDanger
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        beepModulation = beepModulation,
                                        voiceEnabled = voiceEnabled,
                                        voiceText = voiceText,
                                        vibrateEnabled = vibrateEnabled,
                                        vibrateDurationMs = vibrateDurationMs,
                                        vibrateTarget = vibrateTarget,
                                        cooldownSeconds = cooldownSeconds,
                                        repeatWhileActive = repeatWhileActive,
                                        leadTimeMs = leadTimeMs
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
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

/**
 * Numeric up/down as one cohesive pill: a borderless centered number with its
 * unit, flanked by - / + steppers inside a single rounded field-coloured
 * surface. Optional caption above. Values clamp to [range]; the number reflects
 * the live value when unfocused and lets the user type freely while focused
 * (reconciling to the clamped value on blur).
 */
@Composable
internal fun NumberUpDown(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    suffix: String = "",
    label: String? = null,
    enabled: Boolean = true,
    // Decimal / signed support: the value stays an Int (the caller scales it,
    // e.g. tenths of a percent), [format] renders it for display and [parse]
    // turns typed text back into that Int. Defaults keep plain integer behaviour.
    format: (Int) -> String = { it.toString() },
    parse: (String) -> Int? = { it.toIntOrNull() },
    allowSign: Boolean = false,
) {
    val fieldText = MaterialTheme.appColors.fieldText
    val fieldLabelColor = MaterialTheme.appColors.fieldLabel
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    // Width the typed number to the widest value in range so the unit stays put
    // and the digits + unit read as one centred group.
    val numWidth = (maxOf(2, format(range.first).length, format(range.last).length) * 12).dp

    // rememberUpdatedState so the hold-to-repeat loop below always steps from the
    // freshly committed value, not the value captured when the press started.
    val latestValue by rememberUpdatedState(value)
    fun stepBy(delta: Int) {
        val nv = (latestValue + delta).coerceIn(range)
        if (nv != latestValue) { text = format(nv); onValueChange(nv) }
    }

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.5f)) {
        if (label != null) {
            Text(
                label,
                fontSize = 12.sp,
                color = fieldLabelColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.appColors.fieldBackground,
            border = BorderStroke(1.dp, MaterialTheme.appColors.fieldBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RepeatingStepper(
                    icon = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.alarm_threshold_decrease),
                    enabled = enabled && value > range.first,
                    tint = fieldText,
                    onStep = { stepBy(-step) },
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = if (focused) text else format(value),
                        onValueChange = { raw ->
                            val filtered = if (allowSign)
                                raw.filter { it.isDigit() || it == '-' || it == '.' }.take(7)
                            else raw.filter { it.isDigit() }.take(6)
                            text = filtered
                            parse(filtered)?.let { if (it in range) onValueChange(it) }
                        },
                        singleLine = true,
                        enabled = enabled,
                        textStyle = TextStyle(
                            color = fieldText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = SolidColor(fieldText),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (allowSign) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        modifier = Modifier
                            .width(numWidth)
                            .onFocusChanged { f ->
                                if (f.isFocused) text = format(value)
                                focused = f.isFocused
                            }
                    )
                    if (suffix.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(suffix, fontSize = 14.sp, color = fieldLabelColor)
                    }
                }
                RepeatingStepper(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.alarm_threshold_increase),
                    enabled = enabled && value < range.last,
                    tint = fieldText,
                    onStep = { stepBy(step) },
                )
            }
        }
    }
}

/**
 * 48dp icon button for the NumberUpDown steppers: fires [onStep] once on press,
 * then auto-repeats with acceleration while held so the rider can sweep a value
 * fast (handy for fine 0.1-step fields like speed calibration).
 */
@Composable
private fun RepeatingStepper(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onStep: () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    step()                                   // immediate first tick
                    val job = scope.launch {
                        kotlinx.coroutines.delay(400)        // hold before auto-repeat
                        var d = 260L
                        while (true) {
                            step()
                            kotlinx.coroutines.delay(d)
                            d = (d * 80 / 100).coerceAtLeast(40L)   // accelerate, floor 40ms
                        }
                    }
                    try { awaitRelease() } finally { job.cancel() }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
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
