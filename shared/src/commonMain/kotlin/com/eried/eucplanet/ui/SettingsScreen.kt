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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
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
                        SwitchRow(c, "Back button exits app", settings.backButtonExits) { onUpdate { s -> s.copy(backButtonExits = it) } }
                    }

                    SettingsSectionId.Dashboard -> Section(c, "Dashboard", Icons.Filled.Dashboard) {
                        LabelRow(c, "Metric tile columns")
                        Segmented(c, listOf("2", "3"), (settings.dashboardColumns - 2).coerceIn(0, 1)) { onUpdate { s -> s.copy(dashboardColumns = it + 2) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Show MIN / MAX corner stats", settings.statCorners) { onUpdate { s -> s.copy(statCorners = it) } }
                        Note(c, "Custom tile order + action-grid editor is Android-only for now.")
                    }

                    SettingsSectionId.Display -> Section(c, "Display", Icons.Filled.DisplaySettings) {
                        LabelRow(c, "Theme")
                        Segmented(c, listOf("Light", "Dark", "Pure Black"), settings.theme) { onUpdate { s -> s.copy(theme = it) } }
                        Spacer(Modifier.height(10.dp))
                        LabelRow(c, "Accent")
                        Segmented(c, listOf("Cyan", "Green", "Orange", "Pink"), settings.accent) { onUpdate { s -> s.copy(accent = it) } }
                        Spacer(Modifier.height(8.dp))
                        SwitchRow(c, "Gauge color band (warn/danger)", settings.gaugeColorBand) { onUpdate { s -> s.copy(gaugeColorBand = it) } }
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
                        SwitchRow(c, "Report speed", settings.announceSpeed) { onUpdate { s -> s.copy(announceSpeed = it) } }
                        SwitchRow(c, "Report battery", settings.announceBattery) { onUpdate { s -> s.copy(announceBattery = it) } }
                        SwitchRow(c, "Report temperature", settings.announceTemp) { onUpdate { s -> s.copy(announceTemp = it) } }
                        SwitchRow(c, "Announce light changes", settings.announceLights) { onUpdate { s -> s.copy(announceLights = it) } }
                    }

                    SettingsSectionId.Motor -> Section(c, "Motor", Icons.Filled.Motorcycle) {
                        SwitchRow(c, "Engine sound synthesis", settings.engineSound) { onUpdate { s -> s.copy(engineSound = it) } }
                        SliderRow(c, "Engine volume", "${settings.engineVolume.roundToInt()}%", settings.engineVolume, 0f..100f) { onUpdate { s -> s.copy(engineVolume = it) } }
                        Note(c, "Full engine-sound synthesis (type / muffler / gearbox / idle) is Android-only for now.")
                    }

                    SettingsSectionId.Cloud -> Section(c, "Cloud", Icons.Filled.Archive) {
                        SwitchRow(c, "Sync settings to cloud", settings.cloudSyncSettings) { onUpdate { s -> s.copy(cloudSyncSettings = it) } }
                        SwitchRow(c, "Auto-backup trips", settings.autoBackupTrips) { onUpdate { s -> s.copy(autoBackupTrips = it) } }
                        Note(c, "Cloud folder sync lands with the iOS storage actuals.")
                    }

                    SettingsSectionId.Alarms -> Section(c, "Alarms", Icons.Filled.NotificationsActive) {
                        SwitchRow(c, "Speed alarm", settings.speedAlarmEnabled) { onUpdate { s -> s.copy(speedAlarmEnabled = it) } }
                        SliderRow(c, "Speed threshold", "${settings.speedAlarmKmh.roundToInt()} km/h", settings.speedAlarmKmh, 10f..80f) { onUpdate { s -> s.copy(speedAlarmKmh = it) } }
                        SwitchRow(c, "Temperature alarm", settings.tempAlarmEnabled) { onUpdate { s -> s.copy(tempAlarmEnabled = it) } }
                        SliderRow(c, "Temp threshold", "${settings.tempAlarmC.roundToInt()}°C", settings.tempAlarmC, 40f..90f) { onUpdate { s -> s.copy(tempAlarmC = it) } }
                        SwitchRow(c, "Current alarm", settings.currentAlarmEnabled) { onUpdate { s -> s.copy(currentAlarmEnabled = it) } }
                        SliderRow(c, "Current threshold", "${settings.currentAlarmA.roundToInt()} A", settings.currentAlarmA, 10f..120f) { onUpdate { s -> s.copy(currentAlarmA = it) } }
                        SwitchRow(c, "PWM alarm", settings.pwmAlarmEnabled) { onUpdate { s -> s.copy(pwmAlarmEnabled = it) } }
                        SliderRow(c, "PWM threshold", "${settings.pwmAlarmPct.roundToInt()}%", settings.pwmAlarmPct, 50f..95f) { onUpdate { s -> s.copy(pwmAlarmPct = it) } }
                    }

                    SettingsSectionId.Automations -> Section(c, "Automations", Icons.Filled.AutoAwesome) {
                        SwitchRow(c, "Auto lights at speed", settings.autoLights) { onUpdate { s -> s.copy(autoLights = it) } }
                        SliderRow(c, "Lights-on speed", "${settings.autoLightsSpeedKmh.roundToInt()} km/h", settings.autoLightsSpeedKmh, 0f..20f) { onUpdate { s -> s.copy(autoLightsSpeedKmh = it) } }
                        SwitchRow(c, "Auto volume ramp by speed", settings.autoVolume) { onUpdate { s -> s.copy(autoVolume = it) } }
                    }

                    SettingsSectionId.Navigator -> Section(c, "Navigator", Icons.Filled.Navigation) {
                        SwitchRow(c, "Voice guidance", settings.navVoiceGuidance) { onUpdate { s -> s.copy(navVoiceGuidance = it) } }
                        Note(c, "Maps / route navigation is Android-only (out of the iOS v1 scope).")
                    }

                    SettingsSectionId.Location -> Section(c, "Location", Icons.Filled.Sensors) {
                        SwitchRow(c, "Prioritise external GPS", settings.externalGpsPriority) { onUpdate { s -> s.copy(externalGpsPriority = it) } }
                        SwitchRow(c, "Show GPS speed on dashboard", settings.showGpsOnDashboard) { onUpdate { s -> s.copy(showGpsOnDashboard = it) } }
                    }

                    SettingsSectionId.Integration -> Section(c, "Integration", Icons.Filled.Extension) {
                        SwitchRow(c, "Flic button", settings.flicEnabled) { onUpdate { s -> s.copy(flicEnabled = it) } }
                        SwitchRow(c, "Volume-key controls", settings.volumeKeyControls) { onUpdate { s -> s.copy(volumeKeyControls = it) } }
                        SwitchRow(c, "Radar (obstacle detection)", settings.radarEnabled) { onUpdate { s -> s.copy(radarEnabled = it) } }
                        Note(c, "Flic / Radar / HUD hardware integration is Android-only for now.")
                    }

                    SettingsSectionId.Watch -> Section(c, "Watch", Icons.Filled.Watch) {
                        SwitchRow(c, "Keep watch screen on", settings.watchKeepOn) { onUpdate { s -> s.copy(watchKeepOn = it) } }
                        SwitchRow(c, "Auto-start on watch", settings.watchAutoStart) { onUpdate { s -> s.copy(watchAutoStart = it) } }
                        Note(c, "Apple Watch companion is planned; Wear OS / Garmin pairs with Android only.")
                    }
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
