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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.service.AlarmLogic
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.FieldNotchLabel
import androidx.compose.ui.graphics.Color
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
    when (metric) {
        AlarmMetric.SPEED -> Units.speedToKmh(valueDisplayed, speedUnit)
        AlarmMetric.TEMPERATURE -> Units.temperatureToCelsius(valueDisplayed, tempUnit)
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
    val groups by viewModel.groupedRules.collectAsState()
    val studioPlaying by viewModel.studioPlaying.collectAsState()
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
        // The priority hint only makes sense once there are alarms to prioritise.
        if (groups.isNotEmpty()) {
            HintText(stringResource(R.string.alarm_help), small = true)
            Spacer(Modifier.height(12.dp))
        }

        if (groups.isEmpty()) {
            InfoHint(
                text = stringResource(R.string.alarm_empty),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        // Alarms group by metric. Drag a group's handle to set its PRIORITY:
        // the top group's alarm wins; lower groups only sound in its cooldown
        // gaps. Within a group, rules auto-sort by severity. Tap a rule to edit.
        ReorderableColumn(
            list = groups,
            onSettle = { from, to -> viewModel.moveGroup(from, to) },
            onMove = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { _, group, _ ->
            key(group.metric) {
                val groupMetric = try { AlarmMetric.valueOf(group.metric) } catch (_: Exception) { AlarmMetric.SPEED }
                val accent = metricAccent(groupMetric)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Coloured band names the metric once (big); the rules below
                    // drop it and read just "condition threshold unit".
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .padding(start = 6.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.action_reorder),
                            tint = accent,
                            modifier = Modifier.draggableHandle().size(26.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(groupMetric.labelRes),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = accent
                        )
                    }
                    group.rules.forEach { rule ->
                        key(rule.id) {
                            AlarmRuleCard(
                                rule = rule,
                                speedUnit = speedUnit,
                                tempUnit = tempUnit,
                                onToggle = { viewModel.updateRule(rule.copy(enabled = it)) },
                                onEdit = { editingRule = rule; showEditor = true },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // New alarm sits on the left half of the row (auto-sort removed; group
        // order is the priority, so an automatic re-sort would fight the rider).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { editingRule = null; showEditor = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.alarm_add))
            }
            Spacer(Modifier.weight(1f))
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
            onPreviewBeep = { freq, dur, cnt, gap, vol -> viewModel.previewBeep(freq, dur, cnt, gap, vol) },
            onPreviewTone = { freq, vol -> viewModel.previewToneAt(freq, vol) },
            onPreviewVoice = { text, metric, thr -> viewModel.previewVoice(text, metric, thr) },
            onPreviewVibrate = { dur -> viewModel.previewVibrate(dur) },
            studioPlaying = studioPlaying,
            onStudioTone = { f, d, c, g, v -> viewModel.setStudioTone(f, d, c, g, v) },
            onStudioToggle = { repeat -> viewModel.toggleStudioPlay(repeat) },
            onStudioStop = { viewModel.stopStudio() },
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
                }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_delete), color = MaterialTheme.appColors.statusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** One accent colour per metric, so each group reads as a unit. */
@Composable
private fun metricAccent(metric: AlarmMetric): androidx.compose.ui.graphics.Color = when (metric) {
    AlarmMetric.SPEED -> MaterialTheme.appColors.statusWarn
    AlarmMetric.BATTERY -> MaterialTheme.appColors.statusGood
    AlarmMetric.TEMPERATURE -> MaterialTheme.appColors.statusDanger
    AlarmMetric.PWM -> MaterialTheme.appColors.gaugeWarn
    AlarmMetric.VOLTAGE -> MaterialTheme.appColors.metricVoltage
    AlarmMetric.CURRENT -> MaterialTheme.appColors.metricPosition
    AlarmMetric.RADAR_DISTANCE, AlarmMetric.RADAR_APPROACH_SPEED -> MaterialTheme.appColors.statusDanger
}

@Composable
private fun AlarmRuleCard(
    rule: AlarmRule,
    speedUnit: String,
    tempUnit: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val metric = try { AlarmMetric.valueOf(rule.metric) } catch (_: Exception) { AlarmMetric.SPEED }
    val comp = AlarmComparator.parse(rule.comparator)

    // Readable on-colour for the condition text -- the coloured group band above
    // already carries the metric's accent, and some accents (e.g. PWM's gauge-warn
    // yellow) are fill colours that read poorly as text on the card surface.
    val color = if (!rule.enabled)
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.onSurface

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        // Drag handle | tap-to-edit text | enable switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Indent group rules under the group's drag handle.
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEdit() }
                    .padding(vertical = 12.dp)
            ) {
                val shownThresh = displayThreshold(metric, rule.threshold, speedUnit, tempUnit).roundToInt()
                val shownUnit = displayUnit(metric, speedUnit, tempUnit)
                // The coloured group band already names the metric, so a rule
                // reads just "condition threshold unit" (e.g. "≥ 32 km/h").
                Text(
                    rule.name.ifBlank { "${comp.symbol} ${shownThresh}${shownUnit}" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = color
                )
                // Append a "(pitch and vol)" note for whichever modulation is set
                // (1x = 100 = none), kept short so the rule row still fits.
                val pitchAdaptive = rule.beepModulation != 100
                val volAdaptive = rule.beepVolumeModulation != 100
                val adaptiveNote = when {
                    pitchAdaptive && volAdaptive -> stringResource(R.string.alarm_summary_adaptive_pitch_vol)
                    pitchAdaptive -> stringResource(R.string.alarm_summary_adaptive_pitch)
                    volAdaptive -> stringResource(R.string.alarm_summary_adaptive_vol)
                    else -> ""
                }
                val beepSummary = stringResource(R.string.alarm_summary_beep_fmt, rule.beepFrequency, rule.beepCount) +
                    (if (adaptiveNote.isNotEmpty()) " ($adaptiveNote)" else "")
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
    onPreviewBeep: (Int, Int, Int, Int, Int) -> Unit,
    onPreviewTone: (Int, Int) -> Unit,
    onPreviewVoice: (String, AlarmMetric, Float) -> Unit,
    onPreviewVibrate: (Int) -> Unit,
    studioPlaying: Boolean = false,
    onStudioTone: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onStudioToggle: (Boolean) -> Unit = {},
    onStudioStop: () -> Unit = {},
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
    var beepGapMs by remember { mutableIntStateOf(initial.beepGapMs) }
    var beepVolume by remember { mutableIntStateOf(initial.beepVolume) }
    var beepVolumeModulation by remember { mutableIntStateOf(initial.beepVolumeModulation) }
    var beepModulationReachPct by remember { mutableIntStateOf(initial.beepModulationReachPct) }
    var beepVolumeReachPct by remember { mutableIntStateOf(initial.beepVolumeReachPct) }
    var showStudio by remember { mutableStateOf(false) }

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
    // Advanced (cooldown / repeat / anticipation) -- collapsed by default; the
    // rider expands it when they want those.
    var advancedOpen by remember { mutableStateOf(false) }

    val selectedMetric = try { AlarmMetric.valueOf(metric) } catch (_: Exception) { AlarmMetric.SPEED }
    // The metric's valid internal range comes from AlarmLogic (single source shared
    // with the engine + studio). The threshold is edited in display units;
    // displayedRange converts the ends.
    val thresholdRangeInternal =
        AlarmLogic.metricReadMin(selectedMetric.name)..AlarmLogic.metricReadMax(selectedMetric.name)
    // When the rider switches metric, pull the threshold into the new metric's
    // range so it never holds an impossible value (e.g. a 150 km/h speed becoming
    // a 150% PWM).
    LaunchedEffect(selectedMetric) {
        val clamped = threshold.coerceIn(thresholdRangeInternal)
        if (clamped != threshold) threshold = clamped
    }
    val displayedThreshold = displayThreshold(selectedMetric, threshold, speedUnit, tempUnit)
    val displayedRange = displayThreshold(selectedMetric, thresholdRangeInternal.start, speedUnit, tempUnit)..
        displayThreshold(selectedMetric, thresholdRangeInternal.endInclusive, speedUnit, tempUnit)
    val displayedUnit = displayUnit(selectedMetric, speedUnit, tempUnit)

    if (showStudio) {
        BeepStudioDialog(
            metric = selectedMetric,
            unit = displayedUnit,
            toDisplay = { displayThreshold(selectedMetric, it, speedUnit, tempUnit) },
            comparator = comparator,
            threshold = threshold,
            baseFreq = beepFrequency,
            durationMs = beepDurationMs,
            count = beepCount,
            gapMs = beepGapMs,
            baseVolume = beepVolume,
            pitchReachPct = beepModulation,
            volReachPct = beepVolumeModulation,
            playing = studioPlaying,
            onLiveTone = { f, v -> onStudioTone(f, beepDurationMs, beepCount, beepGapMs, v) },
            onTogglePlay = onStudioToggle,
            onCommit = { p, v -> beepModulation = p; beepVolumeModulation = v },
            onDismiss = { onStudioStop(); showStudio = false },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Don't dismiss the alarm editor on an outside tap - that silently drops
        // the whole edit. Close only via the explicit buttons / back.
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        )
    ) {
        // Cap the dialog's height so the inner verticalScroll has a bounded
        // parent, without this the Column grows past the viewport and the
        // Save button at the bottom is unreachable on long forms. 88% leaves
        // room for the status bar and the IME (which the inner imePadding
        // takes care of).
        val maxDialogHeight = androidx.compose.ui.platform.LocalConfiguration.current
            .screenHeightDp.dp * 0.88f
        Card(
            shape = RoundedCornerShape(12.dp),
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
                        fieldHeight = 56.dp,
                    )
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))

                // --- Beep ---
                SectionTitleWithPreview(
                    title = stringResource(R.string.alarm_section_beep),
                    color = MaterialTheme.appColors.statusWarn,
                    enabled = beepEnabled,
                    onPreview = { onPreviewBeep(beepFrequency, beepDurationMs, beepCount, beepGapMs, beepVolume) }
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
                            // 50 ms minimum: the one-buffer TonePlayer renders short
                            // tones cleanly, so a near-constant beep is gap 0 + a short
                            // duration + a high count.
                            range = 50..1000, step = 50, suffix = "ms",
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
                    // Advanced (gap, volume, Adaptive beep) -- collapsed by default.
                    var beepAdvanced by remember { mutableStateOf(false) }
                    Text(
                        text = (if (beepAdvanced) "▴ " else "▾ ") + stringResource(R.string.alarm_beep_advanced),
                        fontSize = 13.sp,
                        color = MaterialTheme.appColors.fieldLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { beepAdvanced = !beepAdvanced }
                            .padding(vertical = 6.dp),
                    )
                    if (beepAdvanced) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            NumberUpDown(
                                value = beepGapMs,
                                onValueChange = { beepGapMs = it },
                                range = 0..2000, step = 20, suffix = "ms",
                                label = stringResource(R.string.alarm_beep_gap_label),
                                modifier = Modifier.weight(1f),
                            )
                            NumberUpDown(
                                value = beepVolume,
                                onValueChange = { beepVolume = it },
                                range = 0..100, step = 5, suffix = "%",
                                label = stringResource(R.string.alarm_beep_volume_label),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // Pitch + volume modulation is set in a dedicated full-screen
                        // preview (roomy controls + live audio), not crammed inline.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showStudio = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.alarm_beep_studio))
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
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
                        shape = RoundedCornerShape(12.dp),
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
                    val targetEntries = listOf(
                        "BOTH" to stringResource(R.string.alarm_vibrate_target_both),
                        "PHONE" to stringResource(R.string.alarm_vibrate_target_phone),
                        "WATCH" to stringResource(R.string.alarm_vibrate_target_watch)
                    )
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            targetEntries.forEachIndexed { index, (key, label) ->
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    selected = key == vibrateTarget,
                                    onClick = { vibrateTarget = key },
                                    shape = SegmentedButtonDefaults.itemShape(index, targetEntries.size, baseShape = RoundedCornerShape(12.dp)),
                                    colors = themedSegmentedColors(),
                                ) { Text(label) }
                            }
                        }
                        FieldNotchLabel(
                            stringResource(R.string.alarm_label_vibrate_on),
                        )
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
                        val repeatEntries = listOf(
                            false to stringResource(R.string.alarm_repeat_single),
                            true to stringResource(R.string.alarm_repeat_multi),
                        )
                        Box(modifier = Modifier.weight(1f).padding(top = 9.dp)) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                repeatEntries.forEachIndexed { index, (value, lbl) ->
                                    SegmentedButton(
                                        modifier = Modifier.fillMaxHeight(),
                                        selected = value == repeatWhileActive,
                                        onClick = { repeatWhileActive = value },
                                        shape = SegmentedButtonDefaults.itemShape(index, repeatEntries.size, baseShape = RoundedCornerShape(12.dp)),
                                        colors = themedSegmentedColors(),
                                    ) { Text(lbl) }
                                }
                            }
                            FieldNotchLabel(
                                stringResource(R.string.alarm_repeat_label),
                            )
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
                    val realtimeLabel = stringResource(R.string.alarm_predict_realtime)
                    val leadValues = listOf(0, 500, 1000, 2000, 3000)
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            leadValues.forEachIndexed { index, ms ->
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    selected = leadTimeMs == ms,
                                    onClick = { leadTimeMs = ms },
                                    shape = SegmentedButtonDefaults.itemShape(index, leadValues.size, baseShape = RoundedCornerShape(12.dp)),
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
                        FieldNotchLabel(
                            stringResource(R.string.alarm_predict_label),
                        )
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
                        TextButton(onClick = onDelete, shape = RoundedCornerShape(12.dp)) {
                            Text(
                                stringResource(R.string.alarm_delete_action),
                                color = MaterialTheme.appColors.statusDanger
                            )
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        val hasOutput = beepEnabled || voiceEnabled || vibrateEnabled
                        Button(
                            enabled = hasOutput,
                            shape = RoundedCornerShape(12.dp),
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
                                        beepGapMs = beepGapMs,
                                        beepVolume = beepVolume,
                                        beepVolumeModulation = beepVolumeModulation,
                                        beepModulationReachPct = beepModulationReachPct,
                                        beepVolumeReachPct = beepVolumeReachPct,
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

/**
 * Full-screen "Beep preview": roomy controls for pitch + volume modulation with a
 * live, hearable preview. The metric slider (top) drives a display-only response
 * curve (Hz/volume vs the metric) and a beep timeline (the repeats + gaps as they
 * play). Press Play and leave it running to hear the change as you move things.
 * Pitch + volume FACTOR are set here; gap + base volume stay in the Advanced tab.
 */
@Composable
private fun BeepStudioDialog(
    metric: AlarmMetric,
    unit: String,
    toDisplay: (Float) -> Float,   // internal value -> the rider's display unit (mph / F / ...)
    comparator: String,
    threshold: Float,
    baseFreq: Int,
    durationMs: Int,
    count: Int,
    gapMs: Int,
    baseVolume: Int,
    pitchReachPct: Int,
    volReachPct: Int,
    playing: Boolean,
    onLiveTone: (freq: Int, volume: Int) -> Unit,
    onTogglePlay: (repeat: Boolean) -> Unit,
    onCommit: (pitch: Int, volume: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ge = AlarmComparator.parse(comparator) == AlarmComparator.GREATER_EQUAL
    // The test slider stays inside the metric's real range: >= goes from the
    // threshold up to the metric max (e.g. PWM 100), < goes from 0 (no negatives)
    // up to the threshold. The factor is reached at that far limit.
    val maxOvershoot = AlarmLogic.metricReachSpan(metric.name, comparator, threshold)
    val sliderMin = if (ge) threshold else (threshold - maxOvershoot).coerceAtLeast(0f)
    val sliderMax = if (ge) threshold + maxOvershoot else threshold

    // Local edits -- committed only on Save, so Cancel discards them. The live
    // preview uses these locals so you hear the in-progress edit.
    var pitchFactor by remember { mutableIntStateOf(pitchReachPct) }
    var volFactor by remember { mutableIntStateOf(volReachPct) }
    // Always start the test input exactly at the threshold (no modulation yet).
    var simValue by remember { mutableFloatStateOf(threshold) }
    var repeat by remember { mutableStateOf(true) }

    fun overshootOf(v: Float) = (if (ge) v - threshold else threshold - v).coerceIn(0f, maxOvershoot)
    fun freqAtV(v: Float) = AlarmLogic.modulatedBeepHz(baseFreq, v, comparator, threshold, pitchFactor, metric.name)
    fun volAtV(v: Float) = AlarmLogic.modulatedVolumePct(baseVolume, volFactor, v, comparator, threshold, metric.name)
    fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else String.format("%.1f", v)

    LaunchedEffect(simValue, pitchFactor, volFactor, baseFreq, baseVolume) {
        onLiveTone(freqAtV(simValue), volAtV(simValue))
    }

    // Playhead for the timeline: restarts from the beginning each time playback
    // starts (so Play always begins at the first beep), approx. one beep cycle.
    val cycleMs = (count * (durationMs + 50) + count * gapMs.coerceAtLeast(1)).coerceAtLeast(250)
    val playhead = remember { Animatable(0f) }
    LaunchedEffect(playing, cycleMs) {
        if (playing) {
            playhead.snapTo(0f)
            playhead.animateTo(1f, infiniteRepeatable(tween(cycleMs, easing = LinearEasing), RepeatMode.Restart))
        } else {
            playhead.snapTo(0f)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // dismissOnClickOutside defaulted to true, so a stray touch in the card's
        // side margins / empty area (easy to hit while dragging the modulation
        // graph) closed the studio mid-edit. Only Cancel/Save/back close it now.
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    stringResource(R.string.alarm_beep_studio),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Pitch + volume FACTOR (numeric) -- just below the title.
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberUpDown(
                        value = pitchFactor,
                        onValueChange = { pitchFactor = it },
                        range = 10..1000, step = 10,
                        format = { "%.1fx".format(it / 100f) },
                        parse = { s -> s.removeSuffix("x").trim().toFloatOrNull()?.let { (it * 100).roundToInt() } },
                        label = stringResource(R.string.alarm_studio_pitch_factor),
                        modifier = Modifier.weight(1f),
                    )
                    NumberUpDown(
                        value = volFactor,
                        onValueChange = { volFactor = it },
                        range = 10..1000, step = 10,
                        format = { "%.1fx".format(it / 100f) },
                        parse = { s -> s.removeSuffix("x").trim().toFloatOrNull()?.let { (it * 100).roundToInt() } },
                        label = stringResource(R.string.alarm_studio_volume_factor),
                        modifier = Modifier.weight(1f),
                    )
                }

                // Metric tracker + live readout, with Play + Repeat next to it.
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alarm_studio_test_input, stringResource(metric.labelRes), fmt(toDisplay(simValue)), unit),
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.alarm_studio_now, freqAtV(simValue), volAtV(simValue)),
                        color = MaterialTheme.appColors.fieldLabel, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onTogglePlay(repeat) }) {
                        Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.alarm_studio_stop else R.string.alarm_studio_play),
                            tint = MaterialTheme.appColors.statusGood)
                    }
                    IconToggleButton(checked = repeat, onCheckedChange = {
                        repeat = it
                        // Turning repeat off mid-play stops the loop instead of letting
                        // it run forever (onTogglePlay stops when already playing).
                        if (!it && playing) onTogglePlay(false)
                    }) {
                        Icon(Icons.Default.Repeat, contentDescription = "Repeat",
                            tint = if (repeat) MaterialTheme.appColors.statusWarn else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    Slider(value = simValue, onValueChange = { simValue = it },
                        valueRange = sliderMin..sliderMax, modifier = Modifier.weight(1f))
                }

                // Response curve (display only).
                BeepCurveDisplay(
                    comparator = comparator,
                    metric = metric.name,
                    threshold = threshold,
                    baseFreq = baseFreq,
                    baseVolume = baseVolume,
                    pitchReachPct = pitchFactor,
                    volReachPct = volFactor,
                    markerO = overshootOf(simValue),
                    maxOvershoot = maxOvershoot,
                    modifier = Modifier.fillMaxWidth().height(128.dp).padding(top = 6.dp),
                )

                // Beep timeline (repeats + gaps + trailing gap), playhead while playing.
                Spacer(Modifier.height(10.dp))
                BeepTimeline(
                    conditionText = "${AlarmComparator.parse(comparator).symbol} ${fmt(toDisplay(threshold))} $unit",
                    durationMs = durationMs,
                    count = count,
                    gapMs = gapMs,
                    freqHz = freqAtV(simValue),
                    playheadFrac = if (playing) playhead.value else null,
                    modifier = Modifier.fillMaxWidth().height(70.dp),
                )

                // Reset on the left; Cancel / Save on the right (like the rule editor).
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Reset commits no modulation (1x pitch and volume) and closes.
                    TextButton(onClick = { onCommit(100, 100); onDismiss() }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.alarm_studio_reset))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onCommit(pitchFactor, volFactor); onDismiss() }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

/**
 * Time-domain view of the beep: a trigger marker + condition at the start, then
 * the [count] beep blocks (width ~ duration) separated by gap spaces, as they
 * play. Gap 0 makes the blocks touch (a continuous tone).
 */
@Composable
private fun BeepTimeline(
    conditionText: String,
    durationMs: Int,
    count: Int,
    gapMs: Int,
    freqHz: Int,
    playheadFrac: Float? = null,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.appColors.statusWarn
    val markerCol = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.appColors.fieldLabel
    val txt = with(LocalDensity.current) { 10.sp.toPx() }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val nv = drawContext.canvas.nativeCanvas
        val pCond = android.graphics.Paint().apply { color = markerCol.toArgb(); textSize = txt; isAntiAlias = true }
        val pLbl = android.graphics.Paint().apply { color = labelColor.toArgb(); textSize = txt * 0.85f; isAntiAlias = true }
        val pBlk = android.graphics.Paint().apply { color = markerCol.toArgb(); textSize = txt * 0.85f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
        val hzStr = "$freqHz"

        nv.drawText(conditionText, 4f, txt + 2f, pCond)

        val ty0 = h * 0.40f; val ty1 = h * 0.82f
        val dur = durationMs.toFloat().coerceAtLeast(1f)
        val gap = gapMs.toFloat()
        val total = (count * dur + count * gap).coerceAtLeast(1f)   // include the trailing gap
        val triggerX = 6f
        val avail = w - triggerX - 6f
        val sx = avail / total

        // trigger marker (vertical line) at the start
        drawLine(markerCol, Offset(triggerX, ty0 - 6f), Offset(triggerX, ty1 + 6f), 3f)
        var t = 0f
        for (i in 0 until count) {
            val x0 = triggerX + t * sx
            val bw = (dur * sx).coerceAtLeast(3f)
            drawRect(accent.copy(alpha = 0.75f), topLeft = Offset(x0, ty0), size = Size(bw, ty1 - ty0))
            // The Hz this block plays at, centred on it if it fits.
            if (bw > pBlk.measureText(hzStr) + 6f) {
                nv.drawText(hzStr, x0 + bw / 2f, (ty0 + ty1) / 2f + txt / 3f, pBlk)
            }
            t += dur
            // A gap after every beep -- the last one is the trailing gap before the loop repeats.
            val gx0 = triggerX + t * sx; t += gap
            if (gap > 0f) drawLine(grid, Offset(gx0, (ty0 + ty1) / 2f), Offset(triggerX + t * sx, (ty0 + ty1) / 2f), 2f)
        }
        // playhead sweeping across while playing
        playheadFrac?.let { f ->
            val px = triggerX + f.coerceIn(0f, 1f) * avail
            drawLine(markerCol, Offset(px, ty0 - 8f), Offset(px, ty1 + 8f), 3f)
        }
        nv.drawText("${count}× · gap ${gapMs} ms", 4f, h - 3f, pLbl)
    }
}

/**
 * Display-only modulation curve for the studio: pitch (orange, log Hz) + volume
 * (purple, % in the lower band) vs the metric value, with a marker at [markerO].
 */
@Composable
private fun BeepCurveDisplay(
    comparator: String,
    metric: String,
    threshold: Float,
    baseFreq: Int,
    baseVolume: Int,
    pitchReachPct: Int,
    volReachPct: Int,
    markerO: Float,
    maxOvershoot: Float,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.appColors.statusWarn
    val volColor = MaterialTheme.appColors.metricPosition
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val markerColor = MaterialTheme.colorScheme.onSurface
    val ge = AlarmComparator.parse(comparator) == AlarmComparator.GREATER_EQUAL
    val fMin = 150f
    val fMax = AlarmLogic.BEEP_MOD_MAX_HZ.toFloat()
    val lnMin = kotlin.math.ln(fMin)
    val lnMax = kotlin.math.ln(fMax)
    val txt = with(LocalDensity.current) { 10.sp.toPx() }
    fun valueAt(o: Float) = if (ge) threshold + o else threshold - o
    fun freqAt(o: Float) = AlarmLogic.modulatedBeepHz(baseFreq, valueAt(o), comparator, threshold, pitchReachPct, metric).toFloat()
    fun volAt(o: Float) = AlarmLogic.modulatedVolumePct(baseVolume, volReachPct, valueAt(o), comparator, threshold, metric).toFloat()

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val pl = 48f; val pr = w - 48f
        val top = h * 0.08f; val bot = h * 0.92f
        val volTop = top + (bot - top) * 0.45f
        fun xOf(o: Float) = pl + (o / maxOvershoot) * (pr - pl)
        fun yFreq(f: Float) = bot - (kotlin.math.ln(f.coerceIn(fMin, fMax)) - lnMin) / (lnMax - lnMin) * (bot - top)
        fun yVol(v: Float) = bot - (v / 100f) * (bot - volTop)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 10f))
        val nv = drawContext.canvas.nativeCanvas
        val pHz = android.graphics.Paint().apply { color = accent.copy(alpha = 0.85f).toArgb(); textSize = txt; isAntiAlias = true }
        val pVol = android.graphics.Paint().apply { color = volColor.copy(alpha = 0.85f).toArgb(); textSize = txt; isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT }

        drawLine(grid, Offset(pl, top), Offset(pl, bot), 2f)
        drawLine(grid, Offset(pl, bot), Offset(pr, bot), 2f)
        for (f in listOf(1000f, 5000f, 10000f)) {
            val y = yFreq(f)
            drawLine(accent.copy(alpha = 0.16f), Offset(pl, y), Offset(pr, y), 1.5f, pathEffect = dash)
            nv.drawText("${(f / 1000).toInt()}k", 2f, y + txt / 3f, pHz)
        }
        for (v in listOf(0f, 50f, 100f)) {
            val y = yVol(v)
            drawLine(volColor.copy(alpha = 0.20f), Offset(pl, y), Offset(pr, y), 1.5f, pathEffect = dash)
            nv.drawText("${v.toInt()}%", w - 2f, y + txt / 3f, pVol)
        }

        val pPath = Path(); val vPath = Path(); val pArea = Path()
        val steps = 64
        for (i in 0..steps) {
            val o = maxOvershoot * i / steps
            val x = xOf(o); val yf = yFreq(freqAt(o))
            if (i == 0) { pPath.moveTo(x, yf); vPath.moveTo(x, yVol(volAt(o))); pArea.moveTo(x, yf) }
            else { pPath.lineTo(x, yf); vPath.lineTo(x, yVol(volAt(o))); pArea.lineTo(x, yf) }
        }
        pArea.lineTo(pr, bot); pArea.lineTo(pl, bot); pArea.close()
        // diagonal hatched fill under the pitch curve
        clipPath(pArea) {
            val diag = bot - top
            var hx = pl - diag
            while (hx < pr) {
                drawLine(accent.copy(alpha = 0.20f), Offset(hx, bot), Offset(hx + diag, top), 1.5f)
                hx += 14f
            }
        }
        drawPath(pPath, accent, style = Stroke(width = 6f))
        drawPath(vPath, volColor, style = Stroke(width = 6f))

        val mx = xOf(markerO)
        drawLine(markerColor, Offset(mx, top), Offset(mx, bot), 2.5f)
        drawCircle(accent, 13f, Offset(mx, yFreq(freqAt(markerO))))
        drawCircle(volColor, 13f, Offset(mx, yVol(volAt(markerO))))
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
    // How the number sits next to the unit. End (default) right-aligns it so it
    // hugs the unit, and because the unit is a fixed trailing element it is
    // always fully visible even in a narrow row. Center keeps the older look.
    numberAlign: TextAlign = TextAlign.End,
    // Field box height. 56dp so numeric fields match the system combo box height
    // (and the metric/condition dropdowns in the alarm editor).
    fieldHeight: Dp = 56.dp,
) {
    val fieldText = MaterialTheme.appColors.fieldText
    val fieldLabelColor = MaterialTheme.appColors.fieldLabel
    val fieldBorder = MaterialTheme.appColors.fieldBorder
    val focusBorder = MaterialTheme.appColors.primary
    val density = LocalDensity.current
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    // rememberUpdatedState so the hold-to-repeat loop below always steps from the
    // freshly committed value, not the value captured when the press started.
    val latestValue by rememberUpdatedState(value)
    fun stepBy(delta: Int) {
        val nv = (latestValue + delta).coerceIn(range)
        if (nv != latestValue) { text = format(nv); onValueChange(nv) }
    }

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.5f)) {
        // Reserve room above the field for the floating label so its top is never
        // clipped by the row/section above (it straddles the top border).
        Box(modifier = Modifier.padding(top = if (label != null) 8.dp else 0.dp)) {
            // Resting state is just the value + unit in a bordered field, so it
            // stays compact and never clips no matter how narrow the row or how
            // wide the value (the old always-visible steppers ate the width and
            // clipped the number on dense screens). Tapping the field focuses it
            // (system keyboard) and reveals the up/down bubble above it.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.appColors.fieldBackground,
                border = BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    if (focused) focusBorder else fieldBorder,
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .height(fieldHeight)
                        .padding(horizontal = 14.dp),
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
                            textAlign = numberAlign
                        ),
                        cursorBrush = SolidColor(fieldText),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (allowSign) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { f ->
                                if (f.isFocused) text = format(value)
                                focused = f.isFocused
                            }
                    )
                    if (suffix.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            suffix,
                            fontSize = 13.sp,
                            color = fieldLabelColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            // Floating label notched into the top border like the combo boxes;
            // takes the focus colour when active.
            if (label != null) {
                FieldNotchLabel(
                    label,
                    color = if (focused) focusBorder else Color.Unspecified,
                )
            }
            // Up/down stepper bubble: a vertical pill (up over down) that appears
            // only while focused, docked just past the field's trailing edge and
            // vertically centred on it -- flipping to the leading edge when there
            // is no room -- so it never competes for row width. Non-focusable so
            // tapping a stepper keeps the keyboard up.
            if (focused && enabled) {
                val gapPx = with(density) { 6.dp.roundToPx() }
                val bubblePosition = remember(gapPx) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val y = (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
                                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                            val trailing = anchorBounds.right + gapPx
                            val x = if (trailing + popupContentSize.width <= windowSize.width) trailing
                            else (anchorBounds.left - popupContentSize.width - gapPx).coerceAtLeast(0)
                            return IntOffset(x, y)
                        }
                    }
                }
                Popup(
                    popupPositionProvider = bubblePosition,
                    properties = PopupProperties(focusable = false, clippingEnabled = false),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.appColors.fieldBackground,
                        border = BorderStroke(1.dp, fieldBorder),
                        shadowElevation = 6.dp,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RepeatingStepper(
                                icon = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.alarm_threshold_increase),
                                enabled = value < range.last,
                                tint = fieldText,
                                onStep = { stepBy(step) },
                            )
                            Box(
                                Modifier
                                    .width(24.dp)
                                    .height(1.dp)
                                    .background(fieldBorder)
                            )
                            RepeatingStepper(
                                icon = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.alarm_threshold_decrease),
                                enabled = value > range.first,
                                tint = fieldText,
                                onStep = { stepBy(-step) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 48dp icon button for the NumberUpDown up/down bubble: fires [onStep] once on
 * press, then auto-repeats with acceleration while held so the rider can sweep a
 * value fast (handy for fine 0.1-step fields like speed calibration).
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
            shape = RoundedCornerShape(12.dp),
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
