package com.eried.eucplanet.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.FlicAction
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.util.Units

private val languageOptions = listOf(
    "en" to "English",
    "es" to "Español",
    "ru" to "Русский",
    "no" to "Norsk",
    "de" to "Deutsch"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToFlic: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsState by viewModel.settings.collectAsState()
    val maxSpeedCap by viewModel.maxSpeedCap.collectAsState()
    val ttsSwitchPrompt by viewModel.ttsSwitchPrompt.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val settings = settingsState ?: return

    ttsSwitchPrompt?.let { lang ->
        val langName = when (lang) {
            "en" -> stringResource(R.string.lang_name_en)
            "es" -> stringResource(R.string.lang_name_es)
            "ru" -> stringResource(R.string.lang_name_ru)
            "no" -> stringResource(R.string.lang_name_no)
            "de" -> stringResource(R.string.lang_name_de)
            else -> lang
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissTtsSwitch() },
            title = { Text(stringResource(R.string.tts_switch_title, langName)) },
            text = { Text(stringResource(R.string.tts_switch_body, langName)) },
            confirmButton = {
                Button(onClick = { viewModel.acceptTtsSwitch() }) {
                    Text(stringResource(R.string.tts_switch_yes))
                }
            },
            dismissButton = {
                Button(onClick = { viewModel.dismissTtsSwitch() }) {
                    Text(stringResource(R.string.tts_switch_no))
                }
            }
        )
    }

    val tabs = listOf(
        stringResource(R.string.tab_general),
        stringResource(R.string.tab_speed),
        stringResource(R.string.tab_voice),
        stringResource(R.string.tab_cloud),
        stringResource(R.string.tab_alarms),
        stringResource(R.string.tab_auto),
        stringResource(R.string.tab_integration)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> GeneralTab(settings, viewModel)
                1 -> SpeedTab(settings, maxSpeedCap, isConnected, viewModel)
                2 -> VoiceTab(settings, viewModel)
                3 -> CloudTab(settings, viewModel)
                4 -> AlarmSettingsContent()
                5 -> AutomationsContent()
                6 -> FlicTab()
            }
        }
    }
}

// --- General Tab ---

@Composable
private fun GeneralTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val themeOptions = listOf(
        "black" to stringResource(R.string.theme_black),
        "dark" to stringResource(R.string.theme_dark),
        "light" to stringResource(R.string.theme_light),
        "system" to stringResource(R.string.theme_system)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_recording))
        SwitchSetting(stringResource(R.string.auto_record_on_start), settings.autoRecord) { viewModel.updateAutoRecord(it) }
        Text(
            stringResource(R.string.auto_record_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeader(stringResource(R.string.section_connection))
        SwitchSetting(stringResource(R.string.auto_connect_on_start), settings.autoConnect) { viewModel.updateAutoConnect(it) }
        Text(
            stringResource(R.string.auto_connect_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        settings.lastDeviceName?.let {
            Text(
                stringResource(R.string.last_device, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionHeader(stringResource(R.string.section_display))
        SwitchSetting(stringResource(R.string.imperial_units), settings.imperialUnits) {
            viewModel.updateImperialUnits(it)
        }

        SimpleDropdown(
            label = "Language",
            currentKey = settings.language,
            options = languageOptions,
            onSelect = { viewModel.updateLanguage(it) }
        )

        SimpleDropdown(
            label = stringResource(R.string.theme),
            currentKey = settings.themeMode,
            options = themeOptions,
            onSelect = { viewModel.updateThemeMode(it) }
        )

        Text(stringResource(R.string.accent_color), style = MaterialTheme.typography.labelLarge)
        AccentPicker(
            current = settings.accentColor,
            onSelect = { viewModel.updateAccentColor(it) }
        )

        Spacer(Modifier.height(32.dp))
    }
}

// --- Speed Tab ---

@Composable
private fun SpeedTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    maxSpeedCap: Float,
    isConnected: Boolean,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isConnected) {
            Text(
                stringResource(R.string.speed_limits_disconnected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionHeader(stringResource(R.string.section_speed_limits))

        val imperial = settings.imperialUnits
        SpeedSliderSetting(
            label = stringResource(R.string.speed_tiltback),
            valueKmh = settings.tiltbackSpeedKmh,
            rangeKmh = 10f..maxSpeedCap,
            imperial = imperial,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateTiltbackSpeed(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_alarm),
            valueKmh = settings.alarmSpeedKmh,
            rangeKmh = 10f..settings.tiltbackSpeedKmh,
            imperial = imperial,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateAlarmSpeed(it) }
        )

        SectionHeader(stringResource(R.string.section_legal_mode_speed))
        Text(
            stringResource(R.string.legal_mode_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_tiltback),
            valueKmh = settings.safetyTiltbackKmh,
            rangeKmh = 10f..(settings.tiltbackSpeedKmh - 1f).coerceAtLeast(11f),
            imperial = imperial,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateSafetyTiltback(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_alarm),
            valueKmh = settings.safetyAlarmKmh,
            rangeKmh = 10f..settings.safetyTiltbackKmh,
            imperial = imperial,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateSafetyAlarm(it) }
        )

        Spacer(Modifier.height(32.dp))
    }
}

// --- Voice Tab ---

@Composable
private fun VoiceTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_announcements))

        SwitchSetting(stringResource(R.string.voice_enabled), settings.voiceEnabled) {
            viewModel.updateVoiceEnabled(it)
        }

        if (settings.voiceEnabled) {
            SwitchSetting(stringResource(R.string.voice_only_when_connected), settings.voiceOnlyWhenConnected) {
                viewModel.updateVoiceOnlyWhenConnected(it)
            }
            SliderSetting(
                label = stringResource(R.string.voice_interval),
                value = settings.voiceIntervalSeconds.toFloat(),
                range = 10f..300f,
                unit = stringResource(R.string.unit_sec),
                steps = 28,
                onValueChange = { viewModel.updateVoiceInterval((Math.round(it / 10f) * 10).coerceIn(10, 300)) }
            )
        }

        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        val sLock = stringResource(R.string.voice_wheel_locked)
        val sUnlock = stringResource(R.string.voice_wheel_unlocked)
        val sLightsOn = stringResource(R.string.voice_lights_on)
        val sLightsOff = stringResource(R.string.voice_lights_off)
        val sRecStart = stringResource(R.string.voice_recording_started)
        val sRecEnd = stringResource(R.string.voice_recording_finished)
        val sConn = stringResource(R.string.voice_wheel_connected)
        val sDisc = stringResource(R.string.voice_wheel_disconnected)
        val sGpsAcq = stringResource(R.string.voice_gps_acquired)
        val sGpsLost = stringResource(R.string.voice_gps_lost)
        val sLegalOn = stringResource(R.string.voice_legal_on)
        val sLegalOff = stringResource(R.string.voice_legal_off)
        val sWelcome = stringResource(R.string.voice_welcome)

        AnnounceSwitchSetting(stringResource(R.string.announce_wheel_lock), settings.announceWheelLock,
            onCheckedChange = { viewModel.updateAnnounceWheelLock(it) },
            onTest = { viewModel.testSpeak(listOf(sLock, sUnlock).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_lights), settings.announceLights,
            onCheckedChange = { viewModel.updateAnnounceLights(it) },
            onTest = { viewModel.testSpeak(listOf(sLightsOn, sLightsOff).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_recording), settings.announceRecording,
            onCheckedChange = { viewModel.updateAnnounceRecording(it) },
            onTest = { viewModel.testSpeak(listOf(sRecStart, sRecEnd).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_connection), settings.announceConnection,
            onCheckedChange = { viewModel.updateAnnounceConnection(it) },
            onTest = { viewModel.testSpeak(listOf(sConn, sDisc).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_gps), settings.announceGps,
            onCheckedChange = { viewModel.updateAnnounceGps(it) },
            onTest = { viewModel.testSpeak(listOf(sGpsAcq, sGpsLost).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_legal_mode), settings.announceSafetyMode,
            onCheckedChange = { viewModel.updateAnnounceSafetyMode(it) },
            onTest = { viewModel.testSpeak(listOf(sLegalOn, sLegalOff).random()) })
        AnnounceSwitchSetting(stringResource(R.string.announce_welcome), settings.announceWelcome,
            onCheckedChange = { viewModel.updateAnnounceWelcome(it) },
            onTest = { viewModel.testSpeak(sWelcome) })

        SectionHeader(stringResource(R.string.section_speech))

        // Voice type selector
        val voices by viewModel.availableVoices.collectAsState()
        if (voices.isNotEmpty()) {
            VoiceSelector(
                currentLocale = settings.voiceLocale,
                voices = voices,
                onVoiceSelected = { viewModel.updateVoiceLocale(it) }
            )
        }

        SliderSetting(
            label = stringResource(R.string.voice_speech_speed),
            value = settings.voiceSpeechRate,
            range = 0.5f..2.5f,
            unit = stringResource(R.string.unit_x),
            steps = 19,
            format = "%.1f",
            onValueChange = { viewModel.updateVoiceSpeechRate(it) }
        )

        SectionHeader(stringResource(R.string.section_report_status))

        val sSpeedEx = stringResource(R.string.voice_speed_fmt, "35")
        val sBatteryEx = stringResource(R.string.voice_battery_fmt, 80)
        val sTempEx = stringResource(R.string.voice_temp_fmt, "32")
        val sLoadEx = stringResource(R.string.voice_load_fmt, "45")
        val sTripEx = stringResource(R.string.voice_trip_fmt, "12.3")
        val sRecOn = stringResource(R.string.voice_recording_on)
        val sRecOff = stringResource(R.string.voice_recording_off)

        val reportOrder = settings.voiceReportOrder.split(",").map { it.trim() }

        fun exampleFor(key: String): String? = when (key) {
            "Speed" -> sSpeedEx
            "Battery" -> sBatteryEx
            "Temp" -> sTempEx
            "PWM" -> sLoadEx
            "Distance" -> sTripEx
            "Recording" -> listOf(sRecOn, sRecOff).random()
            else -> null
        }

        fun buildPreview(periodic: Boolean): String {
            val parts = reportOrder.mapNotNull { key ->
                val enabled = if (periodic) when (key) {
                    "Speed" -> settings.voiceReportSpeed
                    "Battery" -> settings.voiceReportBattery
                    "Temp" -> settings.voiceReportTemp
                    "PWM" -> settings.voiceReportPwm
                    "Distance" -> settings.voiceReportDistance
                    "Recording" -> settings.voiceReportRecording
                    else -> false
                } else when (key) {
                    "Speed" -> settings.triggerReportSpeed
                    "Battery" -> settings.triggerReportBattery
                    "Temp" -> settings.triggerReportTemp
                    "PWM" -> settings.triggerReportPwm
                    "Distance" -> settings.triggerReportDistance
                    "Recording" -> settings.triggerReportRecording
                    else -> false
                }
                if (enabled) exampleFor(key) else null
            }
            return parts.joinToString(", ")
        }

        // Header: Label | Periodic | arrows | Trigger
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.weight(0.55f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.col_periodic), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = {
                    val text = buildPreview(periodic = true)
                    if (text.isNotBlank()) viewModel.testSpeak(text)
                })
            }
            Row(modifier = Modifier.weight(0.55f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.col_trigger), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = {
                    val text = buildPreview(periodic = false)
                    if (text.isNotBlank()) viewModel.testSpeak(text)
                })
            }
        }

        data class ReportItemConfig(
            val key: String,
            val label: String,
            val periodicChecked: Boolean,
            val onPeriodicChange: (Boolean) -> Unit,
            val triggerChecked: Boolean,
            val onTriggerChange: (Boolean) -> Unit,
            val testText: String
        )

        val allItems = mapOf(
            "Speed" to ReportItemConfig("Speed", stringResource(R.string.report_speed),
                settings.voiceReportSpeed, { viewModel.updateVoiceReportSpeed(it) },
                settings.triggerReportSpeed, { viewModel.updateTriggerReportSpeed(it) },
                sSpeedEx),
            "Battery" to ReportItemConfig("Battery", stringResource(R.string.report_battery),
                settings.voiceReportBattery, { viewModel.updateVoiceReportBattery(it) },
                settings.triggerReportBattery, { viewModel.updateTriggerReportBattery(it) },
                sBatteryEx),
            "Temp" to ReportItemConfig("Temp", stringResource(R.string.report_temp),
                settings.voiceReportTemp, { viewModel.updateVoiceReportTemp(it) },
                settings.triggerReportTemp, { viewModel.updateTriggerReportTemp(it) },
                sTempEx),
            "PWM" to ReportItemConfig("PWM", stringResource(R.string.report_pwm),
                settings.voiceReportPwm, { viewModel.updateVoiceReportPwm(it) },
                settings.triggerReportPwm, { viewModel.updateTriggerReportPwm(it) },
                sLoadEx),
            "Distance" to ReportItemConfig("Distance", stringResource(R.string.report_distance),
                settings.voiceReportDistance, { viewModel.updateVoiceReportDistance(it) },
                settings.triggerReportDistance, { viewModel.updateTriggerReportDistance(it) },
                sTripEx),
            "Recording" to ReportItemConfig("Recording", stringResource(R.string.report_recording),
                settings.voiceReportRecording, { viewModel.updateVoiceReportRecording(it) },
                settings.triggerReportRecording, { viewModel.updateTriggerReportRecording(it) },
                listOf(sRecOn, sRecOff).random())
        )

        val orderedItems = reportOrder.mapNotNull { allItems[it] }

        orderedItems.forEachIndexed { index, item ->
            ReportRow(
                label = item.label,
                periodicChecked = item.periodicChecked,
                onPeriodicChange = item.onPeriodicChange,
                triggerChecked = item.triggerChecked,
                onTriggerChange = item.onTriggerChange,
                onTest = { viewModel.testSpeak(item.testText) },
                canMoveUp = index > 0,
                canMoveDown = index < orderedItems.size - 1,
                onMoveUp = { viewModel.moveReportItem(index, index - 1) },
                onMoveDown = { viewModel.moveReportItem(index, index + 1) }
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Integration Tab (Flic + Volume Keys) ---

@Composable
private fun FlicTab(
    viewModel: FlicViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    val pairedButtons by viewModel.pairedButtons.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_flic_buttons))
        // Scan section
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.flic_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    Text(scanStatus, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.stopScan() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) { Text(stringResource(R.string.flic_stop_scan)) }
                } else {
                    if (scanStatus.isNotEmpty()) {
                        Text(scanStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = { viewModel.startScan() }) {
                        Text(stringResource(R.string.flic_start_scan))
                    }
                }
            }
        }

        // Paired buttons
        if (pairedButtons.isEmpty() && settings.flic1Address == null && settings.flic2Address == null) {
            Text(
                stringResource(R.string.flic_no_buttons),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        settings.flic1Address?.let { addr ->
            ButtonConfig(
                title = settings.flic1Name, address = addr,
                onTitleChange = { viewModel.updateFlic1Name(it) },
                clickAction = settings.flic1Click,
                doubleClickAction = settings.flic1DoubleClick,
                holdAction = settings.flic1Hold,
                onClickChange = { viewModel.updateFlic1Click(it) },
                onDoubleClickChange = { viewModel.updateFlic1DoubleClick(it) },
                onHoldChange = { viewModel.updateFlic1Hold(it) },
                onForget = { viewModel.forgetButton(addr) }
            )
        }

        settings.flic2Address?.let { addr ->
            ButtonConfig(
                title = settings.flic2Name, address = addr,
                onTitleChange = { viewModel.updateFlic2Name(it) },
                clickAction = settings.flic2Click,
                doubleClickAction = settings.flic2DoubleClick,
                holdAction = settings.flic2Hold,
                onClickChange = { viewModel.updateFlic2Click(it) },
                onDoubleClickChange = { viewModel.updateFlic2DoubleClick(it) },
                onHoldChange = { viewModel.updateFlic2Hold(it) },
                onForget = { viewModel.forgetButton(addr) }
            )
        }

        SectionHeader(stringResource(R.string.section_volume_keys))
        Text(
            stringResource(R.string.volume_keys_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchSetting(stringResource(R.string.volume_keys_enable), settings.volumeKeysEnabled) {
            settingsViewModel.updateVolumeKeysEnabled(it)
        }
        if (settings.volumeKeysEnabled) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.volume_up), style = MaterialTheme.typography.titleMedium, color = AccentBlue)
                    ActionDropdown(stringResource(R.string.flic_click), settings.volumeUpClick) { settingsViewModel.updateVolumeUpClick(it) }
                    ActionDropdown(stringResource(R.string.flic_hold), settings.volumeUpHold) { settingsViewModel.updateVolumeUpHold(it) }
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.volume_down), style = MaterialTheme.typography.titleMedium, color = AccentBlue)
                    ActionDropdown(stringResource(R.string.flic_click), settings.volumeDownClick) { settingsViewModel.updateVolumeDownClick(it) }
                    ActionDropdown(stringResource(R.string.flic_hold), settings.volumeDownHold) { settingsViewModel.updateVolumeDownHold(it) }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Cloud Tab ---

@Composable
private fun CloudTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val cloudEvent by viewModel.cloudEvent.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.updateSyncFolder(uri)
    }

    // Drain one-shot events to Toasts
    val sFolderFailed = stringResource(R.string.cloud_backup_failed)
    val sBackupOk = stringResource(R.string.cloud_backup_success)
    val sBackupFail = stringResource(R.string.cloud_backup_failed)
    val sRestoreOk = stringResource(R.string.cloud_restore_success)
    val sRestoreFail = stringResource(R.string.cloud_restore_failed)
    val sEnqueued = stringResource(R.string.cloud_retry_enqueued)
    LaunchedEffect(cloudEvent) {
        val event = cloudEvent ?: return@LaunchedEffect
        val msg = when (event) {
            CloudEvent.FolderSet -> null
            CloudEvent.FolderFailed -> sFolderFailed
            CloudEvent.BackupSuccess -> sBackupOk
            CloudEvent.BackupFailed -> sBackupFail
            CloudEvent.RestoreSuccess -> sRestoreOk
            CloudEvent.RestoreFailed -> sRestoreFail
            CloudEvent.UploadEnqueued -> sEnqueued
        }
        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.consumeCloudEvent()
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.cloud_restore_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_restore_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.restoreSettingsNow()
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.action_restore)) }
            },
            dismissButton = {
                Button(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val folderDisplayName = viewModel.syncFolderDisplayName()
    val hasFolder = settings.syncFolderUri != null && folderDisplayName != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_cloud_folder))
        Text(
            stringResource(R.string.cloud_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CloudHelpCard()

        if (hasFolder) {
            Text(
                stringResource(R.string.cloud_folder_label, folderDisplayName!!),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { pickFolder.launch(null) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cloud_change_folder)) }
                Button(
                    onClick = { viewModel.clearSyncFolder() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cloud_remove_folder)) }
            }
        } else {
            Text(
                stringResource(R.string.cloud_no_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { pickFolder.launch(null) }) {
                Text(stringResource(R.string.cloud_choose_folder))
            }
        }

        if (hasFolder) {
            SectionHeader(stringResource(R.string.section_cloud_settings))
            val lastBackupText = settings.lastSettingsBackupAt?.let {
                val fmt = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())
                stringResource(R.string.cloud_last_backup, fmt.format(java.util.Date(it)))
            } ?: stringResource(R.string.cloud_last_backup_never)
            Text(lastBackupText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { viewModel.backupSettingsNow() },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cloud_backup_now)) }
                Button(
                    onClick = { showRestoreDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cloud_restore)) }
            }

            SectionHeader(stringResource(R.string.section_cloud_trips))
            Text(
                stringResource(R.string.cloud_trips_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { viewModel.retryUploadsNow() }) {
                Text(stringResource(R.string.cloud_retry_now))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CloudHelpCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.cloud_help_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                Text(
                    stringResource(R.string.cloud_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                )
            }
        }
    }
}

// --- Shared components ---

@Composable
private fun PlayButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(20.dp)
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_test),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun ReportRow(
    label: String,
    periodicChecked: Boolean,
    onPeriodicChange: (Boolean) -> Unit,
    triggerChecked: Boolean,
    onTriggerChange: (Boolean) -> Unit,
    onTest: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(6.dp))
            PlayButton(onClick = onTest)
            Spacer(Modifier.weight(1f))
            // Reorder arrows on the right of the label area, just left of the first switch
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up),
                        modifier = Modifier.size(24.dp),
                        tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down),
                        modifier = Modifier.size(24.dp),
                        tint = if (canMoveDown) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                }
            }
        }
        Switch(checked = periodicChecked, onCheckedChange = onPeriodicChange,
            modifier = Modifier.weight(0.55f))
        Switch(checked = triggerChecked, onCheckedChange = onTriggerChange,
            modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun AnnounceSwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(4.dp))
            PlayButton(onClick = onTest)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SpeedSliderSetting(
    label: String,
    valueKmh: Float,
    rangeKmh: ClosedFloatingPointRange<Float>,
    imperial: Boolean,
    enabled: Boolean = true,
    onValueChangeKmh: (Float) -> Unit
) {
    val displayValue = Units.speed(valueKmh, imperial)
    val displayStart = Units.speed(rangeKmh.start, imperial)
    val displayEnd = Units.speed(rangeKmh.endInclusive, imperial)
    SliderSetting(
        label = label,
        value = displayValue,
        range = displayStart..displayEnd,
        unit = Units.speedUnit(imperial),
        enabled = enabled,
        onValueChange = { displayed ->
            val kmh = if (imperial) displayed / 0.621371f else displayed
            onValueChangeKmh(kmh.coerceIn(rangeKmh))
        }
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    steps: Int? = null,
    format: String = "%.0f",
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${format.format(value)} $unit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val computedSteps = steps
                ?: ((range.endInclusive - range.start) - 1).toInt().coerceAtLeast(0)
            Slider(
                value = value.coerceIn(range),
                onValueChange = onValueChange,
                valueRange = range,
                steps = computedSteps,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    currentKey: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == currentKey }?.second ?: currentKey
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AccentPicker(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.eried.eucplanet.ui.theme.AccentOptions.forEach { opt ->
            val selected = opt.key == current
            val isRainbow = com.eried.eucplanet.ui.theme.isDefaultAccent(opt.key)
            val rainbowBrush = if (isRainbow) androidx.compose.ui.graphics.Brush.sweepGradient(
                listOf(
                    com.eried.eucplanet.ui.theme.AccentRed,
                    com.eried.eucplanet.ui.theme.AccentOrange,
                    com.eried.eucplanet.ui.theme.AccentYellow,
                    com.eried.eucplanet.ui.theme.AccentGreen,
                    com.eried.eucplanet.ui.theme.AccentTeal,
                    com.eried.eucplanet.ui.theme.AccentBlue,
                    com.eried.eucplanet.ui.theme.AccentPurple,
                    com.eried.eucplanet.ui.theme.AccentPink,
                    com.eried.eucplanet.ui.theme.AccentRed
                )
            ) else null
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(if (selected) 40.dp else 32.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .then(
                        if (rainbowBrush != null) Modifier.background(rainbowBrush)
                        else Modifier.background(opt.color)
                    )
                    .then(
                        if (selected) Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.onBackground,
                            shape = androidx.compose.foundation.shape.CircleShape
                        ) else Modifier
                    )
                    .clickable { onSelect(opt.key) }
            )
        }
    }
}

// --- Flic button config ---

@Composable
private fun ButtonConfig(
    title: String,
    address: String,
    clickAction: String,
    doubleClickAction: String,
    holdAction: String,
    onTitleChange: (String) -> Unit = {},
    onClickChange: (String) -> Unit,
    onDoubleClickChange: (String) -> Unit,
    onHoldChange: (String) -> Unit,
    onForget: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember(title) { mutableStateOf(title) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = AccentBlue),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    onTitleChange(editText)
                                    editing = false
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                                }
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { editing = true }
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = AccentBlue
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.flic_rename),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Text(address, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onForget) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.flic_forget), tint = AccentRed)
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionDropdown(stringResource(R.string.flic_click), clickAction, onClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown(stringResource(R.string.flic_double_click), doubleClickAction, onDoubleClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown(stringResource(R.string.flic_hold), holdAction, onHoldChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelector(
    currentLocale: String,
    voices: List<VoiceOption>,
    onVoiceSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentVoice = voices.find { it.locale.toString() == currentLocale }
    val displayText = currentVoice?.displayName ?: currentLocale

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.voice_selector_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.displayName) },
                    onClick = {
                        onVoiceSelected(voice.locale.toString())
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropdown(
    label: String,
    currentValue: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentAction = try {
        FlicAction.valueOf(currentValue)
    } catch (_: Exception) {
        FlicAction.NONE
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = stringResource(currentAction.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            FlicAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.labelRes)) },
                    onClick = {
                        onValueChange(action.name)
                        expanded = false
                    }
                )
            }
        }
    }
}
