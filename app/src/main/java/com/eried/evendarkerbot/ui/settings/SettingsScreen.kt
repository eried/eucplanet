package com.eried.evendarkerbot.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.evendarkerbot.data.model.FlicAction
import com.eried.evendarkerbot.service.VoiceOption
import com.eried.evendarkerbot.ui.theme.AccentBlue
import com.eried.evendarkerbot.ui.theme.AccentRed

private val tabs = listOf("General", "Voice", "Alarms", "Auto", "Integration")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToFlic: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val maxSpeedCap by viewModel.maxSpeedCap.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                0 -> GeneralTab(settings, maxSpeedCap, viewModel)
                1 -> VoiceTab(settings, viewModel)
                2 -> AlarmSettingsContent()
                3 -> AutomationsContent()
                4 -> FlicTab()
            }
        }
    }
}

// --- General Tab ---

@Composable
private fun GeneralTab(
    settings: com.eried.evendarkerbot.data.model.AppSettings,
    maxSpeedCap: Float,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Speed Limits")

        SliderSetting(
            label = "Tiltback Speed",
            value = settings.tiltbackSpeedKmh,
            range = 10f..maxSpeedCap,
            unit = "km/h",
            onValueChange = { viewModel.updateTiltbackSpeed(it) }
        )
        SliderSetting(
            label = "Alarm Speed",
            value = settings.alarmSpeedKmh,
            range = 10f..settings.tiltbackSpeedKmh,
            unit = "km/h",
            onValueChange = { viewModel.updateAlarmSpeed(it) }
        )

        SectionHeader("Legal Mode Speed")
        Text(
            "Applied when Legal Mode is ON. Must be lower than normal tiltback.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SliderSetting(
            label = "Legal Tiltback",
            value = settings.safetyTiltbackKmh,
            range = 10f..(settings.tiltbackSpeedKmh - 1f).coerceAtLeast(11f),
            unit = "km/h",
            onValueChange = { viewModel.updateSafetyTiltback(it) }
        )
        SliderSetting(
            label = "Legal Alarm",
            value = settings.safetyAlarmKmh,
            range = 10f..settings.safetyTiltbackKmh,
            unit = "km/h",
            onValueChange = { viewModel.updateSafetyAlarm(it) }
        )

        SectionHeader("Recording")
        SwitchSetting("Auto-record on start", settings.autoRecord) { viewModel.updateAutoRecord(it) }
        Text(
            "Starts recording automatically as soon as the wheel connects.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeader("Connection")
        SwitchSetting("Auto-connect on start", settings.autoConnect) { viewModel.updateAutoConnect(it) }

        settings.lastDeviceName?.let {
            Text(
                "Last device: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionHeader("Display")
        SwitchSetting("Imperial units (mph, \u00B0F, mi)", settings.imperialUnits) {
            viewModel.updateImperialUnits(it)
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Voice Tab ---

@Composable
private fun VoiceTab(
    settings: com.eried.evendarkerbot.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Announcements")

        SwitchSetting("Report status periodically", settings.voiceEnabled) {
            viewModel.updateVoiceEnabled(it)
        }

        if (settings.voiceEnabled) {
            SliderSetting(
                label = "Interval",
                value = settings.voiceIntervalSeconds.toFloat(),
                range = 10f..300f,
                unit = "sec",
                steps = 28,
                onValueChange = { viewModel.updateVoiceInterval((Math.round(it / 10f) * 10).coerceIn(10, 300)) }
            )
        }

        AnnounceSwitchSetting("Wheel lock / unlock", settings.announceWheelLock,
            onCheckedChange = { viewModel.updateAnnounceWheelLock(it) },
            onTest = { viewModel.testSpeak(listOf("Wheel locked", "Wheel unlocked").random()) })
        AnnounceSwitchSetting("Lights on / off", settings.announceLights,
            onCheckedChange = { viewModel.updateAnnounceLights(it) },
            onTest = { viewModel.testSpeak(listOf("Lights on", "Lights off").random()) })
        AnnounceSwitchSetting("Trip recording", settings.announceRecording,
            onCheckedChange = { viewModel.updateAnnounceRecording(it) },
            onTest = { viewModel.testSpeak(listOf("Recording started", "Recording finished").random()) })
        AnnounceSwitchSetting("Wheel connected / disconnected", settings.announceConnection,
            onCheckedChange = { viewModel.updateAnnounceConnection(it) },
            onTest = { viewModel.testSpeak(listOf("Wheel connected", "Wheel disconnected").random()) })
        AnnounceSwitchSetting("GPS signal lost / regained", settings.announceGps,
            onCheckedChange = { viewModel.updateAnnounceGps(it) },
            onTest = { viewModel.testSpeak(listOf("GPS signal acquired", "GPS signal lost").random()) })
        AnnounceSwitchSetting("Legal mode on / off", settings.announceSafetyMode,
            onCheckedChange = { viewModel.updateAnnounceSafetyMode(it) },
            onTest = { viewModel.testSpeak(listOf("Legal mode on", "Legal mode off").random()) })

        SectionHeader("Speech")

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
            label = "Speech Speed",
            value = settings.voiceSpeechRate,
            range = 0.5f..2.5f,
            unit = "x",
            steps = 19,
            format = "%.1f",
            onValueChange = { viewModel.updateVoiceSpeechRate(it) }
        )

        SectionHeader("Report Status")

        // Header: Label | Periodic | arrows | Trigger
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.weight(0.55f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Text("Periodic", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = {
                    viewModel.testSpeak("Speed 35, battery 80 percent, temperature 32 degrees, load 45 percent, trip 12.3 kilometers")
                })
            }
            Row(modifier = Modifier.weight(0.55f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Text("Trigger", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = {
                    viewModel.testSpeak("Speed 35, battery 80 percent, temperature 32 degrees, load 45 percent, trip 12.3 kilometers")
                })
            }
        }

        val reportOrder = settings.voiceReportOrder.split(",").map { it.trim() }

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
            "Speed" to ReportItemConfig("Speed", "Speed",
                settings.voiceReportSpeed, { viewModel.updateVoiceReportSpeed(it) },
                settings.triggerReportSpeed, { viewModel.updateTriggerReportSpeed(it) },
                "Speed 35"),
            "Battery" to ReportItemConfig("Battery", "Battery",
                settings.voiceReportBattery, { viewModel.updateVoiceReportBattery(it) },
                settings.triggerReportBattery, { viewModel.updateTriggerReportBattery(it) },
                "battery 80 percent"),
            "Temp" to ReportItemConfig("Temp", "Temp",
                settings.voiceReportTemp, { viewModel.updateVoiceReportTemp(it) },
                settings.triggerReportTemp, { viewModel.updateTriggerReportTemp(it) },
                "temperature 32 degrees"),
            "PWM" to ReportItemConfig("PWM", "PWM",
                settings.voiceReportPwm, { viewModel.updateVoiceReportPwm(it) },
                settings.triggerReportPwm, { viewModel.updateTriggerReportPwm(it) },
                "load 45 percent"),
            "Distance" to ReportItemConfig("Distance", "Distance",
                settings.voiceReportDistance, { viewModel.updateVoiceReportDistance(it) },
                settings.triggerReportDistance, { viewModel.updateTriggerReportDistance(it) },
                "trip 12.3 kilometers"),
            "Recording" to ReportItemConfig("Recording", "Recording",
                settings.voiceReportRecording, { viewModel.updateVoiceReportRecording(it) },
                settings.triggerReportRecording, { viewModel.updateTriggerReportRecording(it) },
                listOf("recording", "not recording").random())
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
        SectionHeader("Flic 2 Buttons")
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
                    "Hold your Flic 2 button for 6 seconds to put it in pairing mode, then tap Scan.",
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
                    ) { Text("Stop Scan") }
                } else {
                    if (scanStatus.isNotEmpty()) {
                        Text(scanStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = { viewModel.startScan() }) {
                        Text("Scan for Flic 2 Button")
                    }
                }
            }
        }

        // Paired buttons
        if (pairedButtons.isEmpty() && settings.flic1Address == null && settings.flic2Address == null) {
            Text(
                "No Flic buttons paired",
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

        SectionHeader("Volume Keys")
        Text(
            "Use the phone's volume buttons as wheel shortcuts. Works only while the app is visible on screen — Android doesn't reliably deliver volume events to background apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchSetting("Enable volume key actions", settings.volumeKeysEnabled) {
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
                    Text("Volume Up", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
                    ActionDropdown("Click", settings.volumeUpClick) { settingsViewModel.updateVolumeUpClick(it) }
                    ActionDropdown("Hold", settings.volumeUpHold) { settingsViewModel.updateVolumeUpHold(it) }
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
                    Text("Volume Down", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
                    ActionDropdown("Click", settings.volumeDownClick) { settingsViewModel.updateVolumeDownClick(it) }
                    ActionDropdown("Hold", settings.volumeDownHold) { settingsViewModel.updateVolumeDownHold(it) }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// --- Shared components ---

@Composable
private fun PlayButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(20.dp)
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Test",
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
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up",
                        modifier = Modifier.size(24.dp),
                        tint = if (canMoveUp) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down",
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
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    steps: Int? = null,
    format: String = "%.0f",
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
                steps = computedSteps
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
                                    Icon(Icons.Default.Check, contentDescription = "Save")
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
                                contentDescription = "Rename",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Text(address, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onForget) {
                    Icon(Icons.Default.Delete, contentDescription = "Forget", tint = AccentRed)
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionDropdown("Click", clickAction, onClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown("Double Click", doubleClickAction, onDoubleClickChange)
            Spacer(Modifier.height(8.dp))
            ActionDropdown("Hold", holdAction, onHoldChange)
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
            label = { Text("Voice") },
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
            value = currentAction.label,
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
                    text = { Text(action.label) },
                    onClick = {
                        onValueChange(action.name)
                        expanded = false
                    }
                )
            }
        }
    }
}
