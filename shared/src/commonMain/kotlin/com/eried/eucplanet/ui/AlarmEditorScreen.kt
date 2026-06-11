package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * Editor for a single [AlarmRule] — the per-rule half of the ported Android alarm
 * customizer. Edits are live: every change calls [onChange] so the rules list and
 * the running alarm engine pick them up immediately. (Cooldown / repeat-while-
 * active are kept in the model but not yet exposed — they land with the stateful
 * firing engine, so there are no dead controls here.)
 */
@Composable
internal fun AlarmEditorScreen(
    initial: AlarmRule,
    onChange: (AlarmRule) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    var rule by remember { mutableStateOf(initial) }
    fun set(r: AlarmRule) { rule = r; onChange(r) }
    val metric = AlarmMetric.parse(rule.metric)
    val below = AlarmComparator.parse(rule.comparator) == AlarmComparator.LESS_THAN

    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, if (rule.name.isBlank()) "New alarm" else rule.name, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {

            FieldLabel(c, "Name")
            AlarmTextField(c, rule.name, "e.g. Overspeed") { set(rule.copy(name = it)) }

            FieldLabel(c, "Metric")
            ChipRow(c, AlarmMetric.entries.map { it.label }, AlarmMetric.entries.indexOf(metric)) { i ->
                val m = AlarmMetric.entries[i]
                set(rule.copy(metric = m.name, threshold = rule.threshold.coerceIn(m.range.start, m.range.endInclusive)))
            }

            FieldLabel(c, "Trigger when ${metric.label} is")
            ChipRow(c, listOf("≥  at or above", "<  below"), if (below) 1 else 0) { i ->
                set(rule.copy(comparator = if (i == 1) AlarmComparator.LESS_THAN.name else AlarmComparator.GREATER_EQUAL.name))
            }
            SliderField(c, "Threshold", "${rule.threshold.roundToInt()} ${metric.unit}", rule.threshold, metric.range) { set(rule.copy(threshold = it)) }

            FieldLabel(c, "Actions")
            SwitchField(c, "Speak when triggered", rule.voiceEnabled) { set(rule.copy(voiceEnabled = it)) }
            if (rule.voiceEnabled) {
                AlarmTextField(c, rule.voiceText, "Warning! {metric} at {value}") { set(rule.copy(voiceText = it)) }
                Text("{metric} and {value} are filled in when the alarm fires.", color = c.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
            SwitchField(c, "Vibrate (haptic)", rule.vibrateEnabled) { set(rule.copy(vibrateEnabled = it)) }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.statusDanger.copy(alpha = 0.15f))
                    .clickable { onDelete() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Delete alarm", color = c.statusDanger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FieldLabel(c: AppThemeColors, text: String) {
    Text(text.uppercase(), color = c.sectionHeader, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun AlarmTextField(c: AppThemeColors, value: String, placeholder: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = c.textDisabled, fontSize = 14.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary, fontSize = 14.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant,
            unfocusedContainerColor = c.surfaceVariant,
            focusedIndicatorColor = c.primary,
            unfocusedIndicatorColor = c.outline,
            cursorColor = c.primary,
        ),
    )
}

@Composable
private fun ChipRow(c: AppThemeColors, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, opt ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(20.dp))
                    .background(if (sel) c.primary else c.surfaceVariant)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(opt, color = if (sel) c.onPrimary else c.textSecondary, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SliderField(c: AppThemeColors, label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.sliderActive, activeTrackColor = c.sliderActive, inactiveTrackColor = c.sliderTrack),
        )
    }
}

@Composable
private fun SwitchField(c: AppThemeColors, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary, checkedTrackColor = c.switchOn,
                uncheckedTrackColor = c.switchOff, uncheckedBorderColor = c.outline, checkedBorderColor = c.switchOn,
            ),
        )
    }
}
