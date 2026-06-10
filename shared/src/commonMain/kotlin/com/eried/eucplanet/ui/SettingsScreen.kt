package com.eried.eucplanet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * Shared Settings screen — a port of the Android settings surface as collapsible
 * sections (Speed / Voice / Display / General, the v1 core). Controls are real
 * Material3 sliders/switches; the Speed sliders push tiltback/alarm to the wheel
 * when connected (no-op in demo). App-level prefs are local state for now;
 * persistence lands with the storage actuals.
 */
@Composable
internal fun SettingsScreen(
    connected: Boolean,
    onApplyMaxSpeed: (tiltbackKmh: Float, alarmKmh: Float) -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {

            // --- Speed ---
            var tiltback by remember { mutableStateOf(45f) }
            var alarm by remember { mutableStateOf(38f) }
            Section(c, "Speed", expandedDefault = true) {
                SliderRow(c, "Tiltback (max) speed", "${tiltback.roundToInt()} km/h", tiltback, 10f..70f) {
                    tiltback = it
                }
                SliderRow(c, "Alarm speed", "${alarm.roundToInt()} km/h", alarm, 5f..70f) { alarm = it }
                ApplyRow(c, connected) { onApplyMaxSpeed(tiltback, alarm) }
            }

            // --- Voice ---
            Section(c, "Voice") {
                var tts by remember { mutableStateOf(true) }
                var rate by remember { mutableStateOf(50f) }
                var announceSpeed by remember { mutableStateOf(true) }
                var announceBattery by remember { mutableStateOf(true) }
                var announceTemp by remember { mutableStateOf(false) }
                SwitchRow(c, "Text-to-speech announcements", tts) { tts = it }
                SliderRow(c, "Speech rate", "${rate.roundToInt()}%", rate, 0f..100f) { rate = it }
                SwitchRow(c, "Report speed", announceSpeed) { announceSpeed = it }
                SwitchRow(c, "Report battery", announceBattery) { announceBattery = it }
                SwitchRow(c, "Report temperature", announceTemp) { announceTemp = it }
            }

            // --- Display ---
            Section(c, "Display") {
                var theme by remember { mutableStateOf(1) } // 0 Light, 1 Dark, 2 Pure Black
                var colorBand by remember { mutableStateOf(true) }
                LabelRow(c, "Theme")
                Segmented(c, listOf("Light", "Dark", "Pure Black"), theme) { theme = it }
                Spacer(Modifier.height(8.dp))
                SwitchRow(c, "Gauge color band (warn/danger)", colorBand) { colorBand = it }
            }

            // --- General ---
            Section(c, "General") {
                var autoConnect by remember { mutableStateOf(true) }
                var keepScreenOn by remember { mutableStateOf(true) }
                var autoRecord by remember { mutableStateOf(false) }
                SwitchRow(c, "Auto-connect last wheel", autoConnect) { autoConnect = it }
                SwitchRow(c, "Keep screen on while riding", keepScreenOn) { keepScreenOn = it }
                SwitchRow(c, "Auto-start trip recording", autoRecord) { autoRecord = it }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Settings persist once the iOS storage actuals land; speed limits apply to the wheel live.",
                color = c.textDisabled, fontSize = 10.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(
    c: AppThemeColors,
    title: String,
    expandedDefault: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(c.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = c.sectionHeader, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (expanded) "▾" else "▸", color = c.textSecondary, fontSize = 14.sp)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun LabelRow(c: AppThemeColors, label: String) {
    Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(c: AppThemeColors, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.switchOn,
                uncheckedTrackColor = c.switchOff,
                uncheckedBorderColor = c.outline,
                checkedBorderColor = c.switchOn,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    c: AppThemeColors,
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = current, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = c.sliderActive,
                activeTrackColor = c.sliderActive,
                inactiveTrackColor = c.sliderTrack,
            ),
        )
    }
}

@Composable
private fun ApplyRow(c: AppThemeColors, connected: Boolean, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (connected) "Tap to write these limits to the wheel" else "Connect a wheel to apply",
            color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f),
        )
        Text(
            "Apply",
            color = if (connected) c.onPrimary else c.textDisabled,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (connected) c.primary else c.surfaceVariant)
                .clickable(enabled = connected) { onApply() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun Segmented(c: AppThemeColors, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) c.primary else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt, color = if (sel) c.onPrimary else c.textSecondary,
                    fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun ScreenTopBar(c: AppThemeColors, title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹ Back", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
