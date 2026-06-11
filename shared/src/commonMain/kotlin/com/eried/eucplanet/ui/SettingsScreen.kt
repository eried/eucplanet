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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.model.AlarmComparator
import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.ui.settings.SettingsSectionId
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * Shared Settings screen — a port of the Android settings surface as collapsible
 * sections, bound to the shared [AppSettings] via [onUpdate] (so changes flow
 * through the SettingsStore and into the gauge / alarms / automations live). The
 * Speed sliders also push tiltback/alarm to the connected wheel.
 *
 * The *set and order* of sections is shared with the Android app via
 * [SettingsSectionId] (the single source of truth), so the two platforms can't
 * drift; only the section bodies below are iOS-specific (the v1 subset).
 */
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    connected: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onApplyMaxSpeed: (tiltbackKmh: Float, alarmKmh: Float) -> Unit,
    onServiceMode: () -> Unit,
    onThemeEditor: () -> Unit,
    onEditAlarm: (AlarmRule) -> Unit,
    onBack: () -> Unit,
) {
    val c = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(c.appBackground)) {
        ScreenTopBar(c, "Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {

            // Render every shared section in its canonical order. The `when` is
            // used as an expression (via `.let`) so Kotlin enforces exhaustiveness:
            // add a SettingsSectionId in :shared and this stops compiling until iOS
            // handles it — that's the guarantee the two platforms can't drift.
            SettingsSectionId.entries.forEach { id ->
                when (id) {
                    SettingsSectionId.General -> Section(c, "General", Icons.Filled.Tune, expandedDefault = true) {
                        SwitchRow(c, "Auto-connect last wheel", settings.autoConnectLastWheel) { onUpdate { s -> s.copy(autoConnectLastWheel = it) } }
                        SwitchRow(c, "Keep screen on while riding", settings.keepScreenOn) { onUpdate { s -> s.copy(keepScreenOn = it) } }
                        SwitchRow(c, "Auto-start trip recording", settings.autoStartRecording) { onUpdate { s -> s.copy(autoStartRecording = it) } }
                    }

                    SettingsSectionId.Dashboard -> Section(c, "Dashboard", Icons.Filled.Dashboard) {
                        LabelRow(c, "Metric tile columns")
                        Segmented(c, listOf("2", "3"), (settings.dashboardColumns - 2).coerceIn(0, 1)) { onUpdate { s -> s.copy(dashboardColumns = it + 2) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Show MIN / MAX corner stats", settings.statCorners) { onUpdate { s -> s.copy(statCorners = it) } }
                        Note(c, "Custom tile order + action-grid editor is Android-only for now.")
                    }

                    SettingsSectionId.Display -> Section(c, "Display", Icons.Filled.DisplaySettings) {
                        LabelRow(c, "Units")
                        // Selected system is DERIVED from the three per-unit choices,
                        // like Android: all-metric -> Metric, all-imperial -> Imperial,
                        // any other mix -> Custom (which reveals the per-unit pickers).
                        val unitSystem = when {
                            settings.unitSpeed == "kmh" && settings.unitDistance == "km" && settings.unitTemp == "C" -> 0
                            settings.unitSpeed == "mph" && settings.unitDistance == "mi" && settings.unitTemp == "F" -> 1
                            else -> 2
                        }
                        Segmented(c, listOf("Metric", "Imperial", "Custom"), unitSystem) { idx ->
                            onUpdate { s ->
                                when (idx) {
                                    0 -> s.copy(unitSpeed = "kmh", unitDistance = "km", unitTemp = "C")
                                    1 -> s.copy(unitSpeed = "mph", unitDistance = "mi", unitTemp = "F")
                                    // Tapping Custom from a preset nudges speed to m/s so the
                                    // Custom segment actually selects (matches Android); an
                                    // already-custom combo is left as the user set it.
                                    else -> if (unitSystem != 2) s.copy(unitSpeed = "ms") else s
                                }
                            }
                        }
                        if (unitSystem == 2) {
                            Spacer(Modifier.height(8.dp))
                            UnitPicker(c, "Speed", listOf("km/h" to "kmh", "mph" to "mph", "m/s" to "ms", "kn" to "kn"), settings.unitSpeed) { onUpdate { s -> s.copy(unitSpeed = it) } }
                            UnitPicker(c, "Distance", listOf("km" to "km", "mi" to "mi", "m" to "m", "ft" to "ft", "mil" to "mil"), settings.unitDistance) { onUpdate { s -> s.copy(unitDistance = it) } }
                            UnitPicker(c, "Temperature", listOf("°C" to "C", "°F" to "F", "K" to "K"), settings.unitTemp) { onUpdate { s -> s.copy(unitTemp = it) } }
                        }
                        Spacer(Modifier.height(10.dp))
                        LabelRow(c, "Theme")
                        Segmented(c, listOf("Light", "Dark", "Pure Black"), settings.theme) { onUpdate { s -> s.copy(theme = it) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Gauge color band (warn/danger)", settings.gaugeColorBand) { onUpdate { s -> s.copy(gaugeColorBand = it) } }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                                .clickable { onThemeEditor() }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Customize theme colors", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (settings.customThemeEnabled) Text("custom on", color = c.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text("›", color = c.primary, fontSize = 16.sp)
                        }
                    }

                    SettingsSectionId.Speed -> Section(c, "Speed", Icons.Filled.Speed) {
                        SliderRow(c, "Tiltback (max) speed", "${settings.tiltbackKmh.roundToInt()} km/h", settings.tiltbackKmh, 10f..70f) {
                            onUpdate { s -> s.copy(tiltbackKmh = it) }
                        }
                        SliderRow(c, "Alarm speed", "${settings.alarmKmh.roundToInt()} km/h", settings.alarmKmh, 5f..70f) {
                            onUpdate { s -> s.copy(alarmKmh = it) }
                        }
                        SliderRow(c, "Legal-mode tiltback", "${settings.legalTiltbackKmh.roundToInt()} km/h", settings.legalTiltbackKmh, 10f..40f) {
                            onUpdate { s -> s.copy(legalTiltbackKmh = it) }
                        }
                        SliderRow(c, "Legal-mode alarm", "${settings.legalAlarmKmh.roundToInt()} km/h", settings.legalAlarmKmh, 5f..40f) {
                            onUpdate { s -> s.copy(legalAlarmKmh = it) }
                        }
                        ApplyRow(c, connected) { onApplyMaxSpeed(settings.tiltbackKmh, settings.alarmKmh) }
                    }

                    SettingsSectionId.Voice -> Section(c, "Voice", Icons.Filled.RecordVoiceOver) {
                        SwitchRow(c, "Text-to-speech announcements", settings.ttsEnabled) { onUpdate { s -> s.copy(ttsEnabled = it) } }
                        SliderRow(c, "Speech rate", "${settings.speechRate.roundToInt()}%", settings.speechRate, 0f..100f) { onUpdate { s -> s.copy(speechRate = it) } }
                        SliderRow(c, "Announce interval", "${settings.announceIntervalSec}s", settings.announceIntervalSec.toFloat(), 10f..300f) { onUpdate { s -> s.copy(announceIntervalSec = it.roundToInt()) } }
                        Spacer(Modifier.height(6.dp))
                        LabelRow(c, "Periodic report")
                        SwitchRow(c, "Report speed", settings.announceSpeed) { onUpdate { s -> s.copy(announceSpeed = it) } }
                        SwitchRow(c, "Report battery", settings.announceBattery) { onUpdate { s -> s.copy(announceBattery = it) } }
                        SwitchRow(c, "Report temperature", settings.announceTemp) { onUpdate { s -> s.copy(announceTemp = it) } }
                        Spacer(Modifier.height(6.dp))
                        LabelRow(c, "Spoken events")
                        SwitchRow(c, "Lights on / off", settings.announceLights) { onUpdate { s -> s.copy(announceLights = it) } }
                        SwitchRow(c, "Wheel lock / unlock", settings.announceWheelLock) { onUpdate { s -> s.copy(announceWheelLock = it) } }
                        SwitchRow(c, "Legal mode on / off", settings.announceLegalMode) { onUpdate { s -> s.copy(announceLegalMode = it) } }
                        SwitchRow(c, "Recording start / stop", settings.announceRecording) { onUpdate { s -> s.copy(announceRecording = it) } }
                        Note(c, "Connection / GPS / welcome announcements arrive with the iOS connection + location actuals.")
                    }

                    // Hidden on iOS — not supported on the v1 ride slice, so omitted
                    // entirely rather than shown as dead toggles (engine-sound synth,
                    // cloud sync, GPS-scheduled automations, maps/navigation, external
                    // GPS, Flic/Radar, Wear OS / Garmin). The shared enum still forces
                    // a deliberate show/hide decision whenever Android adds a section.
                    SettingsSectionId.Motor -> {}

                    SettingsSectionId.Cloud -> {}

                    SettingsSectionId.Alarms -> Section(c, "Alarms", Icons.Filled.NotificationsActive) {
                        if (settings.alarmRules.isEmpty()) {
                            Note(c, "No alarms. Add one to be warned on speed, temperature, PWM, voltage, current or battery.")
                        }
                        settings.alarmRules.forEach { rule ->
                            AlarmRuleRow(c, rule, onEdit = { onEditAlarm(rule) }) { en ->
                                onUpdate { s -> s.copy(alarmRules = s.alarmRules.map { if (it.id == rule.id) it.copy(enabled = en) else it }) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                                .clickable {
                                    val nextId = (settings.alarmRules.maxOfOrNull { it.id } ?: 0L) + 1L
                                    onEditAlarm(AlarmRule(id = nextId))
                                }.padding(vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("+ Add alarm", color = c.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    SettingsSectionId.Automations -> {}

                    SettingsSectionId.Navigator -> {}

                    SettingsSectionId.Location -> {}

                    SettingsSectionId.Integration -> {}

                    SettingsSectionId.Watch -> {}
                }.let { /* exhaustive: a new SettingsSectionId without a branch fails to compile here */ }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
                    .clickable { onServiceMode() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Service Mode / Wheel Diagnostics", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("›", color = c.primary, fontSize = 16.sp)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Settings persist to device storage (NSUserDefaults on iOS). Speed limits apply to the wheel live.",
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
    icon: ImageVector,
    expandedDefault: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(17.dp))
            Text(title, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(24.dp),
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
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
private fun Note(c: AppThemeColors, text: String) {
    Text(text, color = c.textDisabled, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
}

/** A labelled per-unit picker: [options] is (display label -> stored key). */
@Composable
private fun UnitPicker(c: AppThemeColors, label: String, options: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        val idx = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        Segmented(c, options.map { it.first }, idx) { onSelect(options[it].second) }
    }
}

@Composable
private fun AlarmRuleRow(c: AppThemeColors, rule: AlarmRule, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val metric = AlarmMetric.parse(rule.metric)
    val cmp = AlarmComparator.parse(rule.comparator)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant)
            .clickable { onEdit() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name.ifBlank { metric.label }, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${metric.label} ${cmp.symbol} ${rule.threshold.roundToInt()} ${metric.unit}", color = c.textSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = rule.enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary, checkedTrackColor = c.switchOn,
                uncheckedTrackColor = c.switchOff, uncheckedBorderColor = c.outline, checkedBorderColor = c.switchOn,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text("›", color = c.primary, fontSize = 16.sp)
    }
}

@Composable
private fun SwitchRow(c: AppThemeColors, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Modifier.fillMaxWidth().background(c.topBar).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { onBack() }.padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
