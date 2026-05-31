package com.eried.eucplanet.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.eried.eucplanet.ui.theme.AccentOrange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.FlicAction
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.common.LocalSettingsSearchQuery
import com.eried.eucplanet.ui.common.highlightMatches
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.util.Units
import sh.calvin.reorderable.ReorderableColumn

private val languageOptions = listOf(
    "en" to "English",
    "da" to "Dansk",
    "de" to "Deutsch",
    "es" to "Español",
    "es-419" to "Español (Latinoamérica)",
    "fr" to "Français",
    "it" to "Italiano",
    "nl" to "Nederlands",
    "no" to "Norsk",
    "pl" to "Polski",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "sv" to "Svenska",
    "uk" to "Українська",
    "zh" to "中文"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToFlic: () -> Unit = {},
    initialTab: Int = 0,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsState by viewModel.settings.collectAsState()
    val maxSpeedCap by viewModel.maxSpeedCap.collectAsState()
    val ttsSwitchPrompt by viewModel.ttsSwitchPrompt.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val engineParked by viewModel.engineParked.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // One snackbar host for every transient confirmation in Settings, cloud
    // backup success / failure, cheat-console toasts. Replaces the older
    // Android Toast popups so the styling matches Overlay Studio / Navigator
    // (Material3 Snackbar, no app-icon decoration, swipe to dismiss).
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var cheatSheet by remember { mutableStateOf<com.eried.eucplanet.cheats.CheatState.Result.ShowSheet?>(null) }
    cheatSheet?.let { sheet ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cheatSheet = null },
            title = { Text(sheet.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sheet.rows.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    row.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val s = row.state
                            when (s) {
                                is com.eried.eucplanet.cheats.CheatState.State.Bool -> {
                                    Icon(
                                        imageVector = if (s.on) Icons.Filled.CheckCircle
                                                      else Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = if (s.on) "on" else "off",
                                        tint = if (s.on) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                is com.eried.eucplanet.cheats.CheatState.State.Value -> {
                                    Text(
                                        s.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                is com.eried.eucplanet.cheats.CheatState.State.Off -> {
                                    Icon(
                                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = "off",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                is com.eried.eucplanet.cheats.CheatState.State.Action -> { /* no indicator */ }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { cheatSheet = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
    val expandedSections = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.toList() },
            restore = { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(it) } }
        )
    ) {
        val initial = initialTabSectionKey(initialTab)
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            if (initial != null) add(initial)
        }
    }

    val targetSectionKey = remember(initialTab) { initialTabSectionKey(initialTab) }
    val scrollState = rememberScrollState()
    var scrollContainerTop by remember { mutableStateOf<Float?>(null) }
    var targetSectionTop by remember { mutableStateOf<Float?>(null) }
    var hasScrolledToSection by rememberSaveable(initialTab) { mutableStateOf(false) }

    LaunchedEffect(scrollContainerTop, targetSectionTop, hasScrolledToSection) {
        val container = scrollContainerTop
        val target = targetSectionTop
        if (!hasScrolledToSection && targetSectionKey != null &&
            container != null && target != null) {
            val offset = (target - container).toInt().coerceAtLeast(0)
            if (offset > 0) scrollState.animateScrollTo(offset)
            hasScrolledToSection = true
        }
    }

    val settings = settingsState ?: return

    ttsSwitchPrompt?.let { lang ->
        // Single source of truth: the same `languageOptions` list the
        // dropdown uses. No parallel strings.xml entries to drift from it.
        val langName = languageOptions.firstOrNull { it.first == lang }?.second ?: lang
        // The dialog renders in the user's CURRENT language because the
        // locale switch is deferred until they confirm. Three choices:
        //  - Yes: switch language AND TTS voice
        //  - No: switch language only, keep current TTS voice
        //  - Cancel: don't switch at all
        AlertDialog(
            onDismissRequest = { viewModel.cancelLanguageSwitch() },
            title = { Text(stringResource(R.string.tts_switch_title, langName)) },
            text = { Text(stringResource(R.string.tts_switch_body, langName)) },
            confirmButton = {
                Button(onClick = { viewModel.acceptTtsSwitch() }) {
                    Text(stringResource(R.string.tts_switch_yes))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = { viewModel.cancelLanguageSwitch() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(onClick = { viewModel.dismissTtsSwitch() }) {
                        Text(stringResource(R.string.tts_switch_no))
                    }
                }
            }
        )
    }

    data class SectionDef(
        val key: String,
        val title: String,
        val icon: ImageVector,
        val searchCorpus: String,
        val content: @Composable () -> Unit
    )

    val titleGeneral = stringResource(R.string.tab_general)
    val titleDisplay = stringResource(R.string.tab_display)
    val titleSpeed = stringResource(R.string.tab_speed)
    val titleVoice = stringResource(R.string.tab_voice)
    val titleMotor = stringResource(R.string.section_engine_sound)
    val titleCloud = stringResource(R.string.tab_cloud)
    val titleAlarms = stringResource(R.string.tab_alarms)
    val titleAuto = stringResource(R.string.tab_auto)
    val titleIntegration = stringResource(R.string.tab_integration)
    val titleWatch = stringResource(R.string.tab_watch)
    val titleNavigator = stringResource(R.string.nav_setting_params)
    val titleGpsSensors = stringResource(R.string.section_external_gps)

    val corpusGeneral = listOf(
        titleGeneral,
        stringResource(R.string.section_recording),
        stringResource(R.string.auto_record_on_start),
        stringResource(R.string.auto_record_start_in_motion),
        stringResource(R.string.auto_record_stop_idle_seconds),
        stringResource(R.string.section_connection),
        stringResource(R.string.auto_connect_on_start),
        stringResource(R.string.section_application),
        stringResource(R.string.back_button_action)
    ).joinToString(" ")

    val corpusDisplay = listOf(
        titleDisplay,
        stringResource(R.string.section_display),
        stringResource(R.string.units_label),
        stringResource(R.string.theme),
        stringResource(R.string.accent_color),
        stringResource(R.string.show_gauge_color_band),
        stringResource(R.string.language)
    ).joinToString(" ")

    val corpusSpeed = listOf(
        titleSpeed,
        stringResource(R.string.section_speed_limits),
        stringResource(R.string.speed_tiltback),
        stringResource(R.string.speed_alarm),
        stringResource(R.string.section_legal_mode_speed),
        stringResource(R.string.speed_legal_tiltback),
        stringResource(R.string.speed_legal_alarm)
    ).joinToString(" ")

    val corpusVoice = listOf(
        titleVoice,
        stringResource(R.string.section_speech),
        stringResource(R.string.voice_speech_speed),
        stringResource(R.string.voice_audio_focus_label),
        stringResource(R.string.voice_output_channel_label),
        stringResource(R.string.section_announcements),
        stringResource(R.string.voice_enabled),
        stringResource(R.string.voice_interval),
        stringResource(R.string.announce_wheel_lock),
        stringResource(R.string.announce_lights),
        stringResource(R.string.announce_recording),
        stringResource(R.string.announce_connection),
        stringResource(R.string.announce_gps),
        stringResource(R.string.announce_legal_mode),
        stringResource(R.string.announce_welcome),
        stringResource(R.string.section_report_status),
        stringResource(R.string.report_speed),
        stringResource(R.string.report_battery),
        stringResource(R.string.report_temp),
        stringResource(R.string.report_pwm),
        stringResource(R.string.report_distance),
        stringResource(R.string.report_recording),
        stringResource(R.string.report_time)
    ).joinToString(" ")

    val corpusMotor = listOf(
        titleMotor,
        stringResource(R.string.engine_sound_enabled),
        stringResource(R.string.engine_type_label),
        stringResource(R.string.engine_volume),
        stringResource(R.string.engine_muffler_label),
        stringResource(R.string.engine_gearbox_label),
        stringResource(R.string.engine_idle_label),
        stringResource(R.string.engine_decel_label),
        stringResource(R.string.engine_brake_label),
        stringResource(R.string.engine_duck_label),
        stringResource(R.string.engine_headphones_only)
    ).joinToString(" ")

    val corpusCloud = listOf(
        titleCloud,
        stringResource(R.string.section_cloud_folder),
        stringResource(R.string.section_cloud_settings),
        stringResource(R.string.section_cloud_trips)
    ).joinToString(" ")

    val corpusAlarms = titleAlarms + " " + stringResource(R.string.alarm_help)

    val corpusAuto = listOf(
        titleAuto,
        stringResource(R.string.auto_lights_title),
        stringResource(R.string.auto_volume_title),
        stringResource(R.string.auto_volume_desc)
    ).joinToString(" ")

    val corpusIntegration = listOf(
        titleIntegration,
        stringResource(R.string.section_flic_buttons),
        stringResource(R.string.section_volume_keys),
        stringResource(R.string.volume_keys_enable),
        stringResource(R.string.section_hud_companion),
        stringResource(R.string.hud_server_enabled),
        stringResource(R.string.hud_search_corpus)
    ).joinToString(" ")

    val corpusNavigator = listOf(
        titleNavigator,
        stringResource(R.string.nav_setting_voice),
        stringResource(R.string.nav_setting_default_mode),
        stringResource(R.string.nav_setting_arrival_radius),
        stringResource(R.string.nav_setting_offroute),
        stringResource(R.string.nav_setting_endpoints)
    ).joinToString(" ")

    val corpusGpsSensors = listOf(
        titleGpsSensors,
        stringResource(R.string.gps_show_on_dashboard),
        stringResource(R.string.gps_prioritize_external),
        stringResource(R.string.external_gps_caption)
    ).joinToString(" ")

    val corpusWatch = listOf(
        titleWatch,
        stringResource(R.string.section_watch_general),
        stringResource(R.string.section_watch_display),
        stringResource(R.string.watch_keep_on),
        stringResource(R.string.watch_auto_start),
        stringResource(R.string.watch_close_on_exit),
        stringResource(R.string.watch_show_wheel_battery),
        stringResource(R.string.watch_show_phone_battery),
        stringResource(R.string.watch_show_watch_battery),
        stringResource(R.string.watch_pwm_display),
        stringResource(R.string.watch_show_speed_unit)
    ).joinToString(" ")

    val sections: List<SectionDef> = listOf(
        SectionDef("general", titleGeneral, Icons.Default.Tune, corpusGeneral) {
            GeneralTab(settings, viewModel)
        },
        SectionDef("display", titleDisplay, Icons.Default.DisplaySettings, corpusDisplay) {
            DisplayTab(settings, viewModel)
        },
        SectionDef("speed", titleSpeed, Icons.Default.Speed, corpusSpeed) {
            SpeedTab(settings, maxSpeedCap, isConnected, viewModel)
        },
        SectionDef("voice", titleVoice, Icons.Default.RecordVoiceOver, corpusVoice) {
            VoiceTab(settings, viewModel)
        },
        SectionDef("motor", titleMotor, Icons.Default.Motorcycle, corpusMotor) {
            EngineSoundSection(settings, viewModel, engineParked)
        },
        SectionDef("cloud", titleCloud, Icons.Default.Archive, corpusCloud) {
            CloudTab(settings, viewModel, snackbar, snackbarScope)
        },
        SectionDef("alarms", titleAlarms, Icons.Default.NotificationsActive, corpusAlarms) {
            AlarmSettingsContent()
        },
        SectionDef("auto", titleAuto, Icons.Default.AutoAwesome, corpusAuto) {
            AutomationsContent()
        },
        SectionDef("navigator", titleNavigator, Icons.Default.Navigation, corpusNavigator) {
            NavigatorSettingsContent()
        },
        SectionDef("location", titleGpsSensors, Icons.Default.Sensors, corpusGpsSensors) {
            ExternalGpsSection()
        },
        SectionDef("integration", titleIntegration, Icons.Default.Extension, corpusIntegration) {
            FlicTab()
        },
        SectionDef("watch", titleWatch, Icons.Default.Watch, corpusWatch) {
            WatchTab(settings, viewModel)
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val ctxLocal = androidx.compose.ui.platform.LocalContext.current
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_settings)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        // Quake-style console: typed cheats (daredevilNN, godmode, bug) are
                        // intercepted on IME Enter before they become a search query. No
                        // match → field stays populated and the normal text-search filter
                        // already running below keeps narrowing the visible sections.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                val result = viewModel.cheatState.tryConsume(searchQuery)
                                if (result != null) {
                                    when (result) {
                                        is com.eried.eucplanet.cheats.CheatState.Result.ShowSheet -> {
                                            cheatSheet = result
                                        }
                                        is com.eried.eucplanet.cheats.CheatState.Result.OpenUrl -> {
                                            snackbarScope.launch { snackbar.showSnackbar(result.toast) }
                                            try {
                                                ctxLocal.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(result.url)
                                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            } catch (_: Throwable) { /* no browser installed */ }
                                        }
                                        is com.eried.eucplanet.cheats.CheatState.Result.Toast -> {
                                            snackbarScope.launch { snackbar.showSnackbar(result.toast) }
                                        }
                                    }
                                    searchQuery = ""
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 10.dp)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val settingsFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onGloballyPositioned {
                    if (scrollContainerTop == null) {
                        scrollContainerTop = it.positionInWindow().y
                    }
                }
                // Drop the keyboard the moment the rider touches anything in
                // the settings list. Once they've left the search field for a
                // real interaction (toggle, dialog, sub-screen, collapsible
                // expand) the IME is just a 50%-screen obstacle. requireUnconsumed
                // = false so we see the down even when the underlying widget
                // already grabbed it; we only read, never consume.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        settingsFocusManager.clearFocus()
                    }
                }
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            val query = searchQuery.trim()
            val visibleSections = sections.filter { sec ->
                query.isEmpty() || sec.searchCorpus.contains(query, ignoreCase = true)
            }

            androidx.compose.runtime.CompositionLocalProvider(LocalSettingsSearchQuery provides query) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleSections.forEach { sec ->
                        val explicitlyExpanded = expandedSections.contains(sec.key)
                        val isExpanded = explicitlyExpanded || query.isNotEmpty()
                        val sectionModifier = if (sec.key == targetSectionKey) {
                            Modifier.onGloballyPositioned {
                                if (targetSectionTop == null) {
                                    targetSectionTop = it.positionInWindow().y
                                }
                            }
                        } else {
                            Modifier
                        }
                        CollapsibleSection(
                            modifier = sectionModifier,
                            title = sec.title,
                            icon = sec.icon,
                            expanded = isExpanded,
                            query = query,
                            autoExpandedByQuery = !explicitlyExpanded && query.isNotEmpty(),
                            onToggle = {
                                if (explicitlyExpanded) expandedSections.remove(sec.key)
                                else expandedSections.add(sec.key)
                            }
                        ) {
                            sec.content()
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

private fun initialTabSectionKey(initialTab: Int): String? = when (initialTab) {
    1 -> "display"
    2 -> "speed"
    3 -> "voice"
    4 -> "cloud"
    5 -> "alarms"
    6 -> "auto"
    7 -> "integration"
    8 -> "navigator"
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = "",
    autoExpandedByQuery: Boolean = false,
    content: @Composable () -> Unit
) {
    // Bring the section header into view when the rider explicitly toggles
    // it open. Skip the auto-scroll when the section was forced open by the
    // search filter, otherwise every visible section fights for the scroll
    // position as the rider types and the page jumps to the topmost match.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, autoExpandedByQuery) {
        if (expanded && !autoExpandedByQuery) {
            kotlinx.coroutines.delay(80)
            runCatching { requester.bringIntoView() }
        }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(17.dp))
                Text(
                    highlightMatches(title, query),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                ) {
                    content()
                }
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_recording))
        BringIntoViewSection(expanded = settings.autoRecord) {
        SwitchSetting(stringResource(R.string.auto_record_on_start), settings.autoRecord) { viewModel.updateAutoRecord(it) }
        HintText(stringResource(R.string.auto_record_caption), small = true)
        if (settings.autoRecord) {
            SwitchSetting(
                stringResource(R.string.auto_record_start_in_motion),
                settings.autoRecordStartInMotion
            ) { viewModel.updateAutoRecordStartInMotion(it) }
            HintText(stringResource(R.string.auto_record_start_in_motion_caption), small = true)
            if (settings.autoRecordStartInMotion) {
                val idleSec = settings.autoRecordStopIdleSeconds
                SliderSetting(
                    label = stringResource(R.string.auto_record_stop_idle_seconds),
                    value = idleSec.toFloat(),
                    range = 30f..600f,
                    unit = "",
                    steps = 18,
                    valueText = "%d:%02d".format(idleSec / 60, idleSec % 60),
                    onValueChange = {
                        viewModel.updateAutoRecordStopIdleSeconds(
                            (Math.round(it / 30f) * 30).coerceIn(30, 600)
                        )
                    }
                )
            }
        }
        }   // end autoRecord BringIntoViewSection

        SectionHeader(stringResource(R.string.section_connection))
        SwitchSetting(stringResource(R.string.auto_connect_on_start), settings.autoConnect) { viewModel.updateAutoConnect(it) }
        HintText(stringResource(R.string.auto_connect_caption), small = true)

        Text(
            stringResource(R.string.wheel_name_display),
            style = MaterialTheme.typography.bodyLarge
        )
        val wheelNameOptions = listOf(
            "NONE" to stringResource(R.string.wheel_name_none),
            "MODEL" to stringResource(R.string.wheel_name_model),
            "BRAND" to stringResource(R.string.wheel_name_brand)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            wheelNameOptions.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = key == settings.wheelNameDisplay,
                    onClick = { viewModel.updateWheelNameDisplay(key) },
                    shape = SegmentedButtonDefaults.itemShape(index, wheelNameOptions.size)
                ) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        settings.lastDeviceName?.let {
            Text(
                stringResource(R.string.last_device, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionHeader(stringResource(R.string.section_application))

        Text(
            stringResource(R.string.back_button_action),
            style = MaterialTheme.typography.bodyLarge
        )
        val backOptions = listOf(
            "ASK" to stringResource(R.string.back_button_action_ask),
            "BACKGROUND" to stringResource(R.string.back_button_action_background),
            "STOP_ALL" to stringResource(R.string.back_button_action_stop)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            backOptions.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = key == settings.backButtonAction,
                    onClick = { viewModel.updateBackButtonAction(key) },
                    shape = SegmentedButtonDefaults.itemShape(index, backOptions.size)
                ) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HintText(stringResource(R.string.back_button_action_desc), small = true)

    }
}

// --- Display Tab ---

@Composable
private fun DisplayTab(
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UnitsSetting(settings = settings, viewModel = viewModel)

        SwitchSetting(stringResource(R.string.phone_keep_screen_on), settings.phoneKeepScreenOn) {
            viewModel.updatePhoneKeepScreenOn(it)
        }

        SimpleDropdown(
            label = stringResource(R.string.language),
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

        SwitchSetting(
            stringResource(R.string.show_gauge_color_band),
            settings.showGaugeColorBand
        ) { viewModel.updateShowGaugeColorBand(it) }

        if (settings.showGaugeColorBand) {
            // Safe band reads as the universal "ok" colour on the real dial
            // (phone + watch), so the settings preview matches that: always
            // green, never tinted by the user's accent.
            GaugeThresholdSlider(
                orangePct = settings.gaugeOrangeThresholdPct,
                redPct = settings.gaugeRedThresholdPct,
                safeColor = com.eried.eucplanet.ui.theme.AccentGreen,
                onChange = { o, r -> viewModel.updateGaugeThresholds(o, r) }
            )
        }

    }
}

/**
 * Measurement-units control. Top row is a Metric / Imperial / Custom segmented
 * selector whose selected segment is DERIVED from the three per-unit choices.
 * Tapping Custom reveals the per-unit rows and, if the saved combo is an exact
 * preset, nudges speed to knots and distance to Norwegian mile so the Custom
 * segment genuinely selects.
 * Tapping Metric or Imperial applies that preset but does NOT hide the rows
 * once they are on screen. The rows start hidden on a fresh open unless the
 * saved combo is Custom. Per-unit changes apply immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsSetting(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val currentSystem = Units.unitSystemOf(settings)
    var expanded by remember {
        mutableStateOf(currentSystem == Units.UnitSystem.CUSTOM)
    }

    val systemOptions = listOf(
        Units.UnitSystem.METRIC to stringResource(R.string.units_metric),
        Units.UnitSystem.IMPERIAL to stringResource(R.string.units_imperial),
        Units.UnitSystem.CUSTOM to stringResource(R.string.units_custom)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.units_label),
            style = MaterialTheme.typography.labelLarge
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            systemOptions.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == currentSystem,
                    onClick = {
                        when (value) {
                            // Apply the preset but keep the per-unit rows
                            // visible if they are already on screen, only a
                            // fresh reopen hides them (see [expanded] init).
                            Units.UnitSystem.METRIC -> viewModel.applyUnitPreset(false)
                            Units.UnitSystem.IMPERIAL -> viewModel.applyUnitPreset(true)
                            Units.UnitSystem.CUSTOM -> {
                                // Tapping Custom must actually land on Custom.
                                // If the saved combo is an exact preset, nudge
                                // speed to knots and distance to Norwegian mile
                                // so the Custom segment selects; an already-
                                // custom combo is left as the user saved it.
                                if (currentSystem != Units.UnitSystem.CUSTOM) {
                                    viewModel.applyCustomNudge()
                                }
                                expanded = true
                            }
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, systemOptions.size)
                ) { Text(label) }
            }
        }

        if (expanded) {
            BringIntoViewSection(expanded = true, spacing = 8.dp) {
                SimpleDropdown(
                    label = stringResource(R.string.units_speed),
                    currentKey = Units.effectiveSpeedUnit(settings),
                    options = listOf(
                        "kmh" to stringResource(R.string.units_speed_kmh),
                        "mph" to stringResource(R.string.units_speed_mph),
                        "ms" to stringResource(R.string.units_speed_ms),
                        "kn" to stringResource(R.string.units_speed_kn)
                    ),
                    onSelect = { viewModel.setUnitSpeed(it) }
                )
                SimpleDropdown(
                    label = stringResource(R.string.units_distance),
                    currentKey = Units.effectiveDistanceUnit(settings),
                    options = listOf(
                        "km" to stringResource(R.string.units_distance_km),
                        "mi" to stringResource(R.string.units_distance_mi),
                        "m" to stringResource(R.string.units_distance_m),
                        "ft" to stringResource(R.string.units_distance_ft),
                        "mil" to stringResource(R.string.units_distance_mil)
                    ),
                    onSelect = { viewModel.setUnitDistance(it) }
                )
                SimpleDropdown(
                    label = stringResource(R.string.units_temperature),
                    currentKey = Units.effectiveTempUnit(settings),
                    options = listOf(
                        "C" to stringResource(R.string.units_temp_c),
                        "F" to stringResource(R.string.units_temp_f),
                        "K" to stringResource(R.string.units_temp_k)
                    ),
                    onSelect = { viewModel.setUnitTemp(it) }
                )
            }
        }
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isConnected) {
            InfoHint(stringResource(R.string.speed_limits_disconnected))
        }

        val speedUnit = Units.effectiveSpeedUnit(settings)

        // --- Speed calibration ---
        // Sits ABOVE the speed-limit sliders since the calibrated value is
        // what the limits compare against. Always visible, disabled when no
        // wheel is connected because the value is keyed by the wheel's BLE
        // name and only loaded once we know which wheel we're talking to.
        SectionHeader(stringResource(R.string.section_speed_calibration))
        HintText(stringResource(R.string.speed_calibration_caption), small = true)
        val calPct = settings.speedCalibrationOffsetPct
        SliderSetting(
            label = stringResource(R.string.speed_calibration_label),
            value = calPct,
            range = -15f..15f,
            unit = "%",
            steps = 299,
            valueText = "%+.1f %%".format(calPct),
            enabled = isConnected,
            onValueChange = { viewModel.updateSpeedCalibrationOffsetPct(it) }
        )

        SectionHeader(stringResource(R.string.section_speed_limits))
        SpeedSliderSetting(
            label = stringResource(R.string.speed_tiltback),
            valueKmh = settings.tiltbackSpeedKmh,
            rangeKmh = 10f..maxSpeedCap,
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateTiltbackSpeed(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_alarm),
            valueKmh = settings.alarmSpeedKmh,
            rangeKmh = 10f..settings.tiltbackSpeedKmh,
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateAlarmSpeed(it) }
        )

        SectionHeader(stringResource(R.string.section_legal_mode_speed))
        HintText(stringResource(R.string.legal_mode_caption))
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_tiltback),
            valueKmh = settings.safetyTiltbackKmh,
            rangeKmh = 10f..(settings.tiltbackSpeedKmh - 1f).coerceAtLeast(11f),
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateSafetyTiltback(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_alarm),
            valueKmh = settings.safetyAlarmKmh,
            rangeKmh = 10f..settings.safetyTiltbackKmh,
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateSafetyAlarm(it) }
        )

    }
}

// --- Voice Tab ---

@Composable
private fun VoiceTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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

        AudioFocusSelector(
            current = settings.voiceAudioFocus,
            onChange = { viewModel.updateVoiceAudioFocus(it) }
        )

        OutputChannelSelector(
            current = settings.voiceOutputChannel,
            onChange = { viewModel.updateVoiceOutputChannel(it) }
        )

        SectionHeader(stringResource(R.string.section_announcements))

        BringIntoViewSection(expanded = settings.voiceEnabled) {
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
        }   // end voiceEnabled BringIntoViewSection

        // Navigation turn-by-turn voice guidance, uses the same row as the
        // announcement toggles below, so its preview button lines up with them.
        val navSample = stringResource(R.string.nav_arrived)
        AnnounceSwitchSetting(
            stringResource(R.string.nav_setting_voice),
            settings.navVoiceEnabled,
            onCheckedChange = { viewModel.updateNavVoiceEnabled(it) },
            onTest = { viewModel.testSpeak(navSample) }
        )
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

        SectionHeader(stringResource(R.string.section_report_status))

        val sSpeedEx = stringResource(R.string.voice_speed_fmt, "35")
        val sBatteryEx = stringResource(R.string.voice_battery_fmt, 80)
        val sTempEx = stringResource(R.string.voice_temp_fmt, "32")
        val sLoadEx = stringResource(R.string.voice_load_fmt, "45")
        // Preview must match what the voice actually says, imperial users
        // get the miles variant so the page can't lie about the format.
        val sTripEx = stringResource(
            when (Units.effectiveDistanceUnit(settings)) {
                "mi" -> R.string.voice_trip_miles_fmt
                "m" -> R.string.voice_trip_meters_fmt
                else -> R.string.voice_trip_fmt
            },
            if (Units.effectiveDistanceUnit(settings) == "m") "12300" else "12.3"
        )
        val sRecOn = stringResource(R.string.voice_recording_on)
        val sRecOff = stringResource(R.string.voice_recording_off)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val sTimeEx = stringResource(
            R.string.voice_time_fmt,
            android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date())
        )

        val reportOrder = settings.voiceReportOrder.split(",").map { it.trim() }

        fun exampleFor(key: String): String? = when (key) {
            "Speed" -> sSpeedEx
            "Battery" -> sBatteryEx
            "Temp" -> sTempEx
            "PWM" -> sLoadEx
            "Distance" -> sTripEx
            "Recording" -> listOf(sRecOn, sRecOff).random()
            "Time" -> sTimeEx
            "Navigation" -> navSample
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
                    "Time" -> settings.voiceReportTime
                    "Navigation" -> settings.voiceReportNavigation
                    else -> false
                } else when (key) {
                    "Speed" -> settings.triggerReportSpeed
                    "Battery" -> settings.triggerReportBattery
                    "Temp" -> settings.triggerReportTemp
                    "PWM" -> settings.triggerReportPwm
                    "Distance" -> settings.triggerReportDistance
                    "Recording" -> settings.triggerReportRecording
                    "Time" -> settings.triggerReportTime
                    "Navigation" -> settings.triggerReportNavigation
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
                listOf(sRecOn, sRecOff).random()),
            "Time" to ReportItemConfig("Time", stringResource(R.string.report_time),
                settings.voiceReportTime, { viewModel.updateVoiceReportTime(it) },
                settings.triggerReportTime, { viewModel.updateTriggerReportTime(it) },
                sTimeEx),
            "Navigation" to ReportItemConfig("Navigation", stringResource(R.string.report_navigation),
                settings.voiceReportNavigation, { viewModel.updateVoiceReportNavigation(it) },
                settings.triggerReportNavigation, { viewModel.updateTriggerReportNavigation(it) },
                navSample)
        )

        // Existing users may have a saved order that predates new report items (e.g. "Time").
        // Append any known items missing from the saved order so they still appear.
        val orderedItems = (reportOrder.mapNotNull { allItems[it] } +
            allItems.filterKeys { it !in reportOrder }.values).toList()

        val haptic = LocalHapticFeedback.current
        ReorderableColumn(
            list = orderedItems,
            onSettle = { from, to -> viewModel.moveReportItem(from, to) },
            onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            modifier = Modifier.fillMaxWidth()
        ) { _, item, _ ->
            key(item.key) {
                ReportRow(
                    label = item.label,
                    periodicChecked = item.periodicChecked,
                    onPeriodicChange = item.onPeriodicChange,
                    triggerChecked = item.triggerChecked,
                    onTriggerChange = item.onTriggerChange,
                    onTest = { viewModel.testSpeak(item.testText) },
                    dragHandleModifier = Modifier.draggableHandle()
                )
            }
        }

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
    val pairedButtons by viewModel.pairedButtons.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_flic_buttons))

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

        settings.flic3Address?.let { addr ->
            ButtonConfig(
                title = settings.flic3Name, address = addr,
                onTitleChange = { viewModel.updateFlic3Name(it) },
                clickAction = settings.flic3Click,
                doubleClickAction = settings.flic3DoubleClick,
                holdAction = settings.flic3Hold,
                onClickChange = { viewModel.updateFlic3Click(it) },
                onDoubleClickChange = { viewModel.updateFlic3DoubleClick(it) },
                onHoldChange = { viewModel.updateFlic3Hold(it) },
                onForget = { viewModel.forgetButton(addr) }
            )
        }

        settings.flic4Address?.let { addr ->
            ButtonConfig(
                title = settings.flic4Name, address = addr,
                onTitleChange = { viewModel.updateFlic4Name(it) },
                clickAction = settings.flic4Click,
                doubleClickAction = settings.flic4DoubleClick,
                holdAction = settings.flic4Hold,
                onClickChange = { viewModel.updateFlic4Click(it) },
                onDoubleClickChange = { viewModel.updateFlic4DoubleClick(it) },
                onHoldChange = { viewModel.updateFlic4Hold(it) },
                onForget = { viewModel.forgetButton(addr) }
            )
        }

        // Scan section, only shown when there's an empty slot to fill.
        // Layout matches Volume Keys: hint sits outside the card as small italic
        // body text, action button sits inside the card and stretches full width
        // for an obvious tap target.
        val allSlotsFull = settings.flic1Address != null && settings.flic2Address != null &&
                settings.flic3Address != null && settings.flic4Address != null
        if (!allSlotsFull) {
            HintText(stringResource(R.string.flic_scan_hint), small = true)
            // No outer card here either, its 16 dp inner padding pushed
            // the button right of the hint above. Spinner + button now sit
            // directly in the section column so they share the column edge.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
                    LeftAlignedScanButton(
                        label = stringResource(R.string.flic_stop_scan),
                        onClick = { viewModel.stopScan() },
                        containerColor = AccentRed
                    )
                } else {
                    LeftAlignedScanButton(
                        label = stringResource(R.string.flic_start_scan),
                        onClick = { viewModel.startScan() }
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.section_volume_keys))
        HintText(stringResource(R.string.volume_keys_caption), small = true)
        BringIntoViewSection(expanded = settings.volumeKeysEnabled) {
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
        }   // end volumeKeysEnabled BringIntoViewSection

        settingsViewModel.settings.collectAsState().value?.let { s ->
            HudIntegrationSection(settings = s, viewModel = settingsViewModel)
        }
    }
}

// --- Watch Tab ---

@Composable
private fun WatchTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(stringResource(R.string.section_watch_general))

        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_auto_start),
            description = stringResource(R.string.watch_auto_start_desc),
            checked = settings.watchAutoStart,
            onCheckedChange = { viewModel.updateWatchAutoStart(it) },
            onTest = { viewModel.testWatchWake() }
        )
        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_close_on_exit),
            description = stringResource(R.string.watch_close_on_exit_desc),
            checked = settings.watchCloseOnExit,
            onCheckedChange = { viewModel.updateWatchCloseOnExit(it) }
        )
        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_keep_on),
            description = stringResource(R.string.watch_keep_on_desc),
            checked = settings.watchKeepScreenOn,
            onCheckedChange = { viewModel.updateWatchKeepScreenOn(it) }
        )
        Text(
            stringResource(R.string.watch_update_rate),
            style = MaterialTheme.typography.bodyLarge
        )
        val updateRateOptions = listOf(
            "CONSERVATIVE" to stringResource(R.string.watch_update_conservative),
            "NORMAL" to stringResource(R.string.watch_update_normal),
            "FAST" to stringResource(R.string.watch_update_fast)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            updateRateOptions.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = key == settings.watchUpdateRate,
                    onClick = { viewModel.updateWatchUpdateRate(key) },
                    shape = SegmentedButtonDefaults.itemShape(index, updateRateOptions.size)
                ) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // Supporting text after the control, it describes a consequence of
        // the choice (battery cost), so it reads as a footnote, not a header.
        Text(
            stringResource(R.string.watch_update_rate_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionHeader(stringResource(R.string.section_watch_display))

        SwitchSetting(
            stringResource(R.string.watch_show_wheel_battery),
            settings.watchShowWheelBattery
        ) { viewModel.updateWatchShowWheelBattery(it) }
        SwitchSetting(
            stringResource(R.string.watch_show_phone_battery),
            settings.watchShowPhoneBattery
        ) { viewModel.updateWatchShowPhoneBattery(it) }
        SwitchSetting(
            stringResource(R.string.watch_show_watch_battery),
            settings.watchShowWatchBattery
        ) { viewModel.updateWatchShowWatchBattery(it) }

        Text(
            stringResource(R.string.watch_pwm_display),
            style = MaterialTheme.typography.bodyLarge
        )
        val loadOptions = listOf(
            "BAR" to stringResource(R.string.watch_pwm_bar),
            "NUMBERS" to stringResource(R.string.watch_pwm_numbers),
            "BOTH" to stringResource(R.string.watch_pwm_both)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            loadOptions.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = key == settings.watchPwmDisplay,
                    onClick = { viewModel.updateWatchPwmDisplay(key) },
                    shape = SegmentedButtonDefaults.itemShape(index, loadOptions.size)
                ) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_prioritize_pwm),
            description = stringResource(R.string.watch_prioritize_pwm_desc),
            checked = settings.watchPrioritizePwm,
            onCheckedChange = { viewModel.updateWatchPrioritizePwm(it) }
        )

        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_show_speed_unit),
            description = stringResource(R.string.watch_show_speed_unit_desc),
            checked = settings.watchShowSpeedUnit,
            onCheckedChange = { viewModel.updateWatchShowSpeedUnit(it) }
        )

        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_show_navigation),
            description = stringResource(R.string.watch_show_navigation_desc),
            checked = settings.watchShowNavigation,
            onCheckedChange = { viewModel.updateWatchShowNavigation(it) }
        )

        Text(
            stringResource(R.string.watch_dial_rotation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SliderSetting(
            label = "",
            // Fixed -90 / 90 captions under the slider ends; the live angle is
            // intentionally not shown, the thumb position is the feedback.
            value = settings.watchDialRotationDeg.toFloat(),
            range = -90f..90f,
            unit = "°",
            steps = 35,                         // 36 segments → -90,-85,…,+90
            minLabel = "-90",
            maxLabel = "90",
            onValueChange = { viewModel.updateWatchDialRotationDeg(it.toInt()) }
        )

        // Hardware-button mappings hidden for now, Samsung Watch Ultra and
        // most Galaxy Wear OS devices don't deliver KEYCODE_STEM_* events to
        // third-party apps. Keeping the AppSettings columns and dispatch
        // plumbing in place so the section can come back when we test on a
        // watch that actually surfaces stem keys.

        // On-screen button bindings (replaces the hardcoded Horn / Light
        // buttons that used to live on the watch dial). These DO work on
        // every Wear OS watch since they're regular touch targets.
        SectionHeader(stringResource(R.string.section_watch_screen_buttons))
        WatchActionPicker(
            label = "${stringResource(R.string.watch_screen_button_1)} – ${stringResource(R.string.watch_button_click_label)}",
            currentKey = settings.watchScreen1Click,
            onSelect = { viewModel.updateWatchScreen1Click(it) }
        )
        WatchActionPicker(
            label = "${stringResource(R.string.watch_screen_button_1)} – ${stringResource(R.string.watch_button_hold_label)}",
            currentKey = settings.watchScreen1Hold,
            onSelect = { viewModel.updateWatchScreen1Hold(it) }
        )
        WatchActionPicker(
            label = "${stringResource(R.string.watch_screen_button_2)} – ${stringResource(R.string.watch_button_click_label)}",
            currentKey = settings.watchScreen2Click,
            onSelect = { viewModel.updateWatchScreen2Click(it) }
        )
        WatchActionPicker(
            label = "${stringResource(R.string.watch_screen_button_2)} – ${stringResource(R.string.watch_button_hold_label)}",
            currentKey = settings.watchScreen2Hold,
            onSelect = { viewModel.updateWatchScreen2Hold(it) }
        )
        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_haptic_on_action),
            description = stringResource(R.string.watch_haptic_on_action_desc),
            checked = settings.watchHapticOnAction,
            onCheckedChange = { viewModel.updateWatchHapticOnAction(it) }
        )
    }
}

@Composable
private fun WatchActionPicker(
    label: String,
    currentKey: String,
    onSelect: (String) -> Unit
) {
    val options = com.eried.eucplanet.data.model.FlicAction.entries.map { action ->
        action.name to stringResource(action.labelRes)
    }
    SimpleDropdown(
        label = label,
        currentKey = currentKey,
        options = options,
        onSelect = onSelect
    )
}

@Composable
private fun SwitchSettingWithDesc(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTest: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    highlightMatches(label, LocalSettingsSearchQuery.current),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (onTest != null) {
                    Spacer(Modifier.width(4.dp))
                    PlayButton(onClick = onTest)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Cloud Tab ---

@Composable
private fun CloudTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    snackbar: androidx.compose.material3.SnackbarHostState,
    snackbarScope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    val cloudEvent by viewModel.cloudEvent.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val syncRunning by viewModel.syncRunning.collectAsState()
    val syncConflict by viewModel.syncConflictPrompt.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showBackupNameDialog by remember { mutableStateOf(false) }
    var backupNameDraft by remember { mutableStateOf("") }
    var overwritePrompt by remember { mutableStateOf<String?>(null) }
    var showRestorePicker by remember { mutableStateOf(false) }

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
    val sSyncNoFolder = stringResource(R.string.sync_no_folder)
    LaunchedEffect(cloudEvent) {
        val event = cloudEvent ?: return@LaunchedEffect
        val msg = when (event) {
            CloudEvent.FolderSet -> null
            CloudEvent.FolderFailed -> sFolderFailed
            CloudEvent.BackupSuccess -> sBackupOk
            is CloudEvent.BackupNamedSuccess -> context.getString(R.string.cloud_backup_named_success, event.name)
            CloudEvent.BackupFailed -> sBackupFail
            is CloudEvent.BackupExists -> {
                overwritePrompt = event.name
                null
            }
            CloudEvent.RestoreSuccess -> sRestoreOk
            CloudEvent.RestoreFailed -> sRestoreFail
            CloudEvent.UploadEnqueued -> sEnqueued
            CloudEvent.SyncNoFolder -> sSyncNoFolder
            is CloudEvent.SyncFinished -> context.getString(R.string.sync_finished, event.count)
        }
        if (msg != null) snackbarScope.launch { snackbar.showSnackbar(msg) }
        viewModel.consumeCloudEvent()
    }

    if (showBackupNameDialog) {
        NamedBackupDialog(
            initial = backupNameDraft,
            onDismiss = {
                showBackupNameDialog = false
                backupNameDraft = ""
            },
            onSave = { name ->
                showBackupNameDialog = false
                backupNameDraft = name
                viewModel.backupSettingsNamed(name, overwrite = false)
            },
            sanitize = viewModel::sanitizeBackupName
        )
    }

    overwritePrompt?.let { pendingName ->
        AlertDialog(
            onDismissRequest = {
                // Treat tap-outside the same as Cancel, return to the name
                // input with the previous text preserved so the rider can
                // adjust the name instead of losing what they typed.
                overwritePrompt = null
                backupNameDraft = pendingName
                showBackupNameDialog = true
            },
            title = { Text(stringResource(R.string.cloud_backup_overwrite_title, pendingName)) },
            text = { Text(stringResource(R.string.cloud_backup_overwrite_body)) },
            confirmButton = {
                Button(onClick = {
                    overwritePrompt = null
                    backupNameDraft = ""
                    viewModel.backupSettingsNamed(pendingName, overwrite = true)
                }) { Text(stringResource(R.string.action_overwrite)) }
            },
            dismissButton = {
                Button(onClick = {
                    overwritePrompt = null
                    backupNameDraft = pendingName
                    showBackupNameDialog = true
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showRestorePicker) {
        RestorePickerDialog(
            onDismiss = { showRestorePicker = false },
            loadEntries = { viewModel.listBackups() },
            onPicked = { entry ->
                showRestorePicker = false
                viewModel.restoreSettingsFrom(entry.fileName)
            }
        )
    }

    if (syncConflict != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSyncConflict() },
            title = { Text(stringResource(R.string.sync_conflict_title)) },
            text = { Text(stringResource(R.string.sync_conflict_body, syncConflict!!)) },
            confirmButton = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { viewModel.resolveSyncConflict(SyncChoice.FOLDER) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_folder)) }
                    Button(
                        onClick = { viewModel.resolveSyncConflict(SyncChoice.APP) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_app)) }
                    Button(
                        onClick = { viewModel.resolveSyncConflict(SyncChoice.IGNORE) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_ignore)) }
                    Button(
                        onClick = { viewModel.cancelSyncConflict() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        )
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.cloud_restore_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cloud_restore_confirm_body_p1))
                    Text(stringResource(R.string.cloud_restore_confirm_body_p2))
                }
            },
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
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HintText(stringResource(R.string.cloud_caption))

        CloudHelpCard()

        if (hasFolder) {
            val openFolderInBrowser: () -> Unit = openFolder@{
                val uri = settings.syncFolderUri ?: return@openFolder
                val treeUri = android.net.Uri.parse(uri)
                // The raw tree URI lands in the DocumentsUI root for most
                // browsers. Convert to a document URI that points at the
                // tree's own document id so the browser opens *inside* the
                // rider's chosen folder rather than its provider root.
                val docUri = runCatching {
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }.getOrNull() ?: treeUri
                val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        docUri,
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    )
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                runCatching { context.startActivity(openIntent) }
                    .onFailure {
                        // Fallback for ROMs whose Files app doesn't handle the
                        // document URI directly, try the raw tree URI as a
                        // last resort. Some browsers still land at the root,
                        // but at least the click does something visible.
                        val fallback = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setDataAndType(treeUri, "*/*")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(fallback) }
                    }
            }
            Text(
                stringResource(R.string.cloud_folder_label, folderDisplayName!!),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = openFolderInBrowser)
                    .padding(vertical = 4.dp)
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
            // Dropped the "No folder selected" hint, the Choose folder
            // button right below says everything that's needed.
            LeftAlignedScanButton(
                label = stringResource(R.string.cloud_choose_folder),
                onClick = { pickFolder.launch(null) }
            )
        }

        if (hasFolder) {
            SectionHeader(stringResource(R.string.section_cloud_settings))
            val lastBackupText = settings.lastSettingsBackupAt?.let { ts ->
                val fmt = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())
                val date = fmt.format(java.util.Date(ts))
                val named = settings.lastSettingsBackupName
                if (named != null) {
                    stringResource(R.string.cloud_last_backup_named, date, named)
                } else {
                    stringResource(R.string.cloud_last_backup, date)
                }
            } ?: stringResource(R.string.cloud_last_backup_never)
            Text(lastBackupText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LongPressActionButton(
                    text = stringResource(R.string.cloud_backup_now),
                    onClick = { viewModel.backupSettingsNow() },
                    onLongClick = {
                        // Fresh long-press always starts with an empty field;
                        // the only path that pre-fills is bouncing back from
                        // an overwrite-cancel below.
                        backupNameDraft = ""
                        showBackupNameDialog = true
                    },
                    modifier = Modifier.weight(1f)
                )
                LongPressActionButton(
                    text = stringResource(R.string.cloud_restore),
                    onClick = { showRestoreDialog = true },
                    onLongClick = { showRestorePicker = true },
                    modifier = Modifier.weight(1f)
                )
            }
            HintText(
                stringResource(R.string.cloud_backup_long_press_hint),
                small = true
            )

            SectionHeader(stringResource(R.string.section_cloud_trips))
            HintText(stringResource(R.string.cloud_trips_caption))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { viewModel.syncAllTrips() },
                    enabled = !syncRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cloud_retry_now))
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            if (syncRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (syncProgress != null) {
                        val (done, total) = syncProgress!!
                        val fraction = if (total > 0) done.toFloat() / total else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.sync_progress, done, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.sync_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

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
private fun PlayButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(20.dp)
    ) {
        // Greyed out when disabled so the affordance stays visible, the rider can see
        // "preview exists, just not while moving" instead of the button vanishing.
        val baseTint = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.action_test),
            modifier = Modifier.size(14.dp),
            tint = if (enabled) baseTint.copy(alpha = 0.6f) else baseTint.copy(alpha = 0.25f)
        )
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
    dragHandleModifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.action_reorder),
            modifier = dragHandleModifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(6.dp))
            PlayButton(onClick = onTest)
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
            Text(highlightMatches(label, LocalSettingsSearchQuery.current), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(4.dp))
            PlayButton(onClick = onTest)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SectionHeader(title: String) {
    val query = LocalSettingsSearchQuery.current
    Text(
        text = highlightMatches(title, query),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SpeedSliderSetting(
    label: String,
    valueKmh: Float,
    rangeKmh: ClosedFloatingPointRange<Float>,
    speedUnit: String,
    enabled: Boolean = true,
    onValueChangeKmh: (Float) -> Unit
) {
    val displayValue = Units.speed(valueKmh, speedUnit)
    val displayStart = Units.speed(rangeKmh.start, speedUnit)
    val displayEnd = Units.speed(rangeKmh.endInclusive, speedUnit)
    SliderSetting(
        label = label,
        value = displayValue,
        range = displayStart..displayEnd,
        unit = Units.speedUnit(LocalContext.current, speedUnit),
        enabled = enabled,
        onValueChange = { displayed ->
            val kmh = Units.speedToKmh(displayed, speedUnit)
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
    valueText: String? = null,
    enabled: Boolean = true,
    minLabel: String? = null,
    maxLabel: String? = null,
    onValueChange: (Float) -> Unit
) {
    // Endpoint-label mode (minLabel/maxLabel) replaces the label + value header
    // with fixed captions above the slider's two ends; no live readout.
    val endpointMode = minLabel != null || maxLabel != null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!endpointMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(highlightMatches(label, LocalSettingsSearchQuery.current), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        valueText ?: "${format.format(value)} $unit",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (endpointMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        minLabel ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        maxLabel ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        Text(highlightMatches(label, LocalSettingsSearchQuery.current), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GaugeThresholdSlider(
    orangePct: Int,
    redPct: Int,
    safeColor: androidx.compose.ui.graphics.Color,
    onChange: (orange: Int, red: Int) -> Unit
) {
    // Visual range 0..100 so the user SEES the locked green sliver (0..5) and the
    // locked red sliver (95..100) at the ends of the track, they just can't drag
    // a handle into those zones. Drag positions are clamped to [5, 95] inclusive.
    val stepSize = 5f
    var range by remember(orangePct, redPct) {
        mutableStateOf(orangePct.toFloat()..redPct.toFloat())
    }
    val orangeColor = com.eried.eucplanet.ui.theme.AccentOrange
    val redColor = com.eried.eucplanet.ui.theme.AccentRed

    Column(modifier = Modifier.padding(top = 4.dp)) {
        RangeSlider(
            value = range,
            onValueChange = { v ->
                val start = kotlin.math.round(v.start / stepSize) * stepSize
                val end = kotlin.math.round(v.endInclusive / stepSize) * stepSize
                val clampedStart = start.coerceIn(5f, 90f)
                val clampedEnd = end.coerceIn(clampedStart + stepSize, 95f)
                range = clampedStart..clampedEnd
            },
            onValueChangeFinished = {
                onChange(
                    kotlin.math.round(range.start).toInt(),
                    kotlin.math.round(range.endInclusive).toInt()
                )
            },
            valueRange = 0f..100f,
            steps = 19,  // 20 divisions of 5 over the full 0..100 track
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val startFrac = ((state.activeRangeStart - state.valueRange.start) / span)
                    .coerceIn(0f, 1f)
                val endFrac = ((state.activeRangeEnd - state.valueRange.start) / span)
                    .coerceIn(0f, 1f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                ) {
                    val trackH = 4.dp.toPx()
                    val y = size.height / 2f
                    val r = trackH / 2f
                    val w = size.width
                    val gap = 6.dp.toPx()
                    val sx = w * startFrac
                    val ex = w * endFrac
                    // Safe zone (left): 0 .. sx - gap
                    val safeEnd = (sx - gap).coerceAtLeast(0f)
                    if (safeEnd > 0f) {
                        drawRoundRect(
                            color = safeColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, y - r),
                            size = androidx.compose.ui.geometry.Size(safeEnd, trackH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                        )
                    }
                    // Orange zone (middle): sx + gap .. ex - gap
                    val orangeStart = (sx + gap).coerceAtMost(w)
                    val orangeEnd = (ex - gap).coerceAtLeast(orangeStart)
                    if (orangeEnd > orangeStart) {
                        drawRoundRect(
                            color = orangeColor,
                            topLeft = androidx.compose.ui.geometry.Offset(orangeStart, y - r),
                            size = androidx.compose.ui.geometry.Size(orangeEnd - orangeStart, trackH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                        )
                    }
                    // Red zone (right): ex + gap .. w
                    val redStart = (ex + gap).coerceAtMost(w)
                    if (w > redStart) {
                        drawRoundRect(
                            color = redColor,
                            topLeft = androidx.compose.ui.geometry.Offset(redStart, y - r),
                            size = androidx.compose.ui.geometry.Size(w - redStart, trackH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                        )
                    }
                }
            }
        )
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
            label = { Text(highlightMatches(label, LocalSettingsSearchQuery.current)) },
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

@Composable
private fun EngineSoundSection(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    parked: Boolean
) {
    BringIntoViewSection(expanded = settings.engineSoundEnabled) {
    SwitchSetting(
        label = stringResource(R.string.engine_sound_enabled),
        checked = settings.engineSoundEnabled
    ) { enabled ->
        viewModel.updateEngineSoundEnabled(enabled)
    }
    Text(
        stringResource(R.string.engine_sound_enabled_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (settings.engineSoundEnabled) {
        // Previews push synthetic telemetry, which would clash with a live ride.
        // Only allow ▶ while the wheel is disconnected or parked.
        EngineTypePicker(
            currentKey = settings.engineType,
            onSelect = { viewModel.updateEngineType(it) },
            onPreview = { viewModel.previewEngine(it) },
            previewEnabled = parked
        )

        // Resolve the active profile so unsupported rows (e.g. decel pops on a diesel
        // or muffler LPF on any sampled engine, since MediaPlayer has no filter point)
        // are hidden from the UI. EngineProfile carries the per-engine support booleans.
        val currentProfile = remember(settings.engineType) {
            com.eried.eucplanet.audio.EngineProfile.byKey(settings.engineType)
        }

        // Engine volume is now a 4-point speed curve, no fixed slider. Drag a finger on
        // the graph to probe the volume at any speed (same UX as the voice auto-volume
        // curve in Automations).
        Text(
            stringResource(R.string.engine_volume),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var enginePoints by remember(settings.engineVolumeAutoCurve) {
            mutableStateOf(com.eried.eucplanet.service.parseVolumeCurve(settings.engineVolumeAutoCurve))
        }
        EngineSpeedVolumeCurveEditor(
            points = enginePoints,
            speedUnit = Units.effectiveSpeedUnit(settings),
            onPointsChanged = { newPoints ->
                enginePoints = newPoints
                viewModel.updateEngineVolumeAutoCurve(com.eried.eucplanet.service.encodeVolumeCurve(newPoints))
            }
        )

        if (currentProfile.supportsMuffler) {
            SegmentedChoice(
                label = stringResource(R.string.engine_muffler_label),
                options = listOf(
                    "OPEN" to stringResource(R.string.engine_muffler_open),
                    "HALF" to stringResource(R.string.engine_muffler_half),
                    "MUFFLED" to stringResource(R.string.engine_muffler_muffled)
                ),
                current = settings.engineMuffler,
                onChange = { viewModel.updateEngineMuffler(it) },
                onPreview = { viewModel.previewEngineSection("DEFAULT") },
                previewEnabled = parked
            )
        }

        SegmentedChoice(
            label = stringResource(R.string.engine_gearbox_label),
            options = listOf(
                "OFF" to stringResource(R.string.engine_gearbox_off),
                "FOUR" to stringResource(R.string.engine_gearbox_four),
                "SIX" to stringResource(R.string.engine_gearbox_six)
            ),
            current = settings.engineGearbox,
            onChange = { viewModel.updateEngineGearbox(it) },
            onPreview = { viewModel.previewEngineSection("GEARBOX") },
            previewEnabled = parked
        )

        SegmentedChoice(
            label = stringResource(R.string.engine_idle_label),
            options = listOf(
                "ALWAYS" to stringResource(R.string.engine_idle_always),
                "FADE" to stringResource(R.string.engine_idle_fade),
                "MOVING" to stringResource(R.string.engine_idle_moving)
            ),
            current = settings.engineIdleBehavior,
            onChange = { viewModel.updateEngineIdleBehavior(it) }
        )

        if (currentProfile.supportsPops) {
            SegmentedChoice(
                label = stringResource(R.string.engine_decel_label),
                options = listOf(
                    "SMOOTH" to stringResource(R.string.engine_decel_smooth),
                    "STANDARD" to stringResource(R.string.engine_decel_standard),
                    "BACKFIRE" to stringResource(R.string.engine_decel_backfire)
                ),
                current = settings.engineDecelChar,
                onChange = { viewModel.updateEngineDecelChar(it) },
                onPreview = { viewModel.previewEngineSection("DECEL") },
                previewEnabled = parked
            )
        }

        if (currentProfile.supportsBrakeWhine) {
            SegmentedChoice(
                label = stringResource(R.string.engine_brake_label),
                options = listOf(
                    "OFF" to stringResource(R.string.engine_brake_off),
                    "LIGHT" to stringResource(R.string.engine_brake_light),
                    "STRONG" to stringResource(R.string.engine_brake_strong)
                ),
                current = settings.engineBrake,
                onChange = { viewModel.updateEngineBrake(it) },
                onPreview = { viewModel.previewEngineSection("BRAKE") },
                previewEnabled = parked
            )
        }

        SegmentedChoice(
            label = stringResource(R.string.engine_duck_label),
            options = listOf(
                "DUCK" to stringResource(R.string.engine_duck_duck),
                "PAUSE" to stringResource(R.string.engine_duck_pause),
                "MIX" to stringResource(R.string.engine_duck_mix)
            ),
            current = settings.engineDuckOnVoice,
            onChange = { viewModel.updateEngineDuckOnVoice(it) }
        )

        SwitchSetting(
            label = stringResource(R.string.engine_headphones_only),
            checked = settings.engineHeadphonesOnly
        ) { viewModel.updateEngineHeadphonesOnly(it) }
        Text(
            stringResource(R.string.engine_headphones_only_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineTypePicker(
    currentKey: String,
    onSelect: (String) -> Unit,
    onPreview: ((String) -> Unit)? = null,
    previewEnabled: Boolean = true
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = ctx.resources
    val unsortedProfiles = com.eried.eucplanet.audio.EngineProfile.PROFILES
    fun displayFor(key: String): String {
        val resKey = "engine_preset_" + key.lowercase()
        val resId = res.getIdentifier(resKey, "string", ctx.packageName)
        return if (resId != 0) ctx.getString(resId)
            else (unsortedProfiles.firstOrNull { it.key == key }?.displayName ?: key)
    }
    // Alphabetical by localized display name. Recomputed when the configuration locale changes
    // because displayFor reads from the current ctx.resources. Preview engines are hidden
    // unless they're the user's currently-selected one (so we never silently swap their pick).
    val profiles = remember(ctx, currentKey) {
        unsortedProfiles
            .filter { !it.preview || it.key == currentKey }
            .sortedBy { displayFor(it.key).lowercase() }
    }
    var expanded by remember { mutableStateOf(false) }

    val currentProfile = profiles.firstOrNull { it.key == currentKey }
    val sourceLabel = if (currentProfile?.sampleAssetBase != null)
        stringResource(R.string.engine_source_sampled)
    else
        stringResource(R.string.engine_source_synth)

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.engine_type_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onPreview != null) {
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = { onPreview(currentKey) }, enabled = previewEnabled)
            }
        }
        Spacer(Modifier.height(6.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            // Source icon: Album (vinyl) = sampled / analog. Equalizer (bars) = synth / digital.
            // Sits on the LEFT of the engine name so two presets that share a name
            // (e.g. "Tractor" Synth vs "Tractor" Sampled) are visually distinct at a glance.
            val isSampled = currentProfile?.sampleAssetBase != null
            OutlinedTextField(
                value = displayFor(currentKey),
                onValueChange = {},
                readOnly = true,
                leadingIcon = {
                    if (isSampled) {
                        Icon(
                            painter = painterResource(R.drawable.ic_vital_signs),
                            contentDescription = sourceLabel,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = sourceLabel,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                suffix = {
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                profiles.forEach { p ->
                    val itemIsSampled = p.sampleAssetBase != null
                    DropdownMenuItem(
                        leadingIcon = {
                            if (itemIsSampled) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_vital_signs),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(displayFor(p.key), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (itemIsSampled)
                                        stringResource(R.string.engine_source_sampled)
                                    else
                                        stringResource(R.string.engine_source_synth),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSelect(p.key)
                            expanded = false
                        }
                    )
                }
            }
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
    // Tolerant of either tag form: Locale.toString() ("nb_NO") or
    // toLanguageTag() ("nb-NO"). Older stored values may use either.
    val normalized = currentLocale.replace('-', '_')
    val currentVoice = voices.find { it.locale.toString().replace('-', '_') == normalized }
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

@Composable
private fun AudioFocusSelector(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        "DUCK" to stringResource(R.string.voice_audio_focus_duck),
        "PAUSE" to stringResource(R.string.voice_audio_focus_pause),
        "OFF" to stringResource(R.string.voice_audio_focus_off)
    )
    SegmentedChoice(
        label = stringResource(R.string.voice_audio_focus_label),
        options = options,
        current = current,
        onChange = onChange
    )
}

@Composable
private fun OutputChannelSelector(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        "MEDIA" to stringResource(R.string.voice_output_channel_media),
        "NOTIFICATION" to stringResource(R.string.voice_output_channel_notification),
        "ALARM" to stringResource(R.string.voice_output_channel_alarm)
    )
    SegmentedChoice(
        label = stringResource(R.string.voice_output_channel_label),
        options = options,
        current = current,
        onChange = onChange
    )
}

@Composable
private fun SegmentedChoice(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onChange: (String) -> Unit,
    onPreview: (() -> Unit)? = null,
    previewEnabled: Boolean = true
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onPreview != null) {
                Spacer(Modifier.width(4.dp))
                PlayButton(onClick = onPreview, enabled = previewEnabled)
            }
        }
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            options.forEachIndexed { index, (key, optLabel) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = current == key,
                    onClick = { onChange(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {},
                    label = {
                        Text(
                            optLabel,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
            label = { Text(highlightMatches(label, LocalSettingsSearchQuery.current)) },
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

/**
 * 4-point curve editor for the engine speed-based auto-volume feature.
 *
 * Differences vs the voice [SplineCurveEditor]:
 *  - Range is 0..1 (a multiplier, not a 1..2× boost, voice ramps UP to overcome wind noise,
 *    engine ramps DOWN so it's loud for pedestrian awareness at slow speeds).
 *  - No monotonic constraint, the user can freely shape the curve in any direction.
 *  - All 4 control points are draggable, including the 0 km/h anchor.
 */
@Composable
private fun EngineSpeedVolumeCurveEditor(
    points: List<Pair<Float, Float>>,
    speedUnit: String,
    onPointsChanged: (List<Pair<Float, Float>>) -> Unit
) {
    val maxSpeed = 75f
    val speedUnitLabel = Units.speedUnit(LocalContext.current, speedUnit)
    val minMult = 0f
    val maxMult = 1f
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = AccentBlue
    val pointColor = AccentOrange
    val textMeasurer = rememberTextMeasurer()

    // Always render exactly 4 normalized points at the canonical speeds.
    val normalized = remember(points) {
        val p = points.toMutableList()
        listOf(
            0f to (p.getOrNull(0)?.second ?: 1.0f),
            25f to (p.getOrNull(1)?.second ?: 0.7f),
            50f to (p.getOrNull(2)?.second ?: 0.4f),
            75f to (p.getOrNull(3)?.second ?: 0.2f),
        )
    }
    val probeColor = MaterialTheme.colorScheme.onSurface
    var dragIndex by remember { mutableStateOf(-1) }
    var probeSpeed by remember { mutableStateOf<Float?>(null) }
    val pointsRef = remember { mutableStateOf(normalized) }
    pointsRef.value = normalized

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 40.dp, bottom = 28.dp, top = 12.dp, end = 12.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val pts = pointsRef.value
                            val nearest = pts
                                .mapIndexed { idx, (s, v) ->
                                    val px = s / maxSpeed * w
                                    val py = h - (v - minMult) / (maxMult - minMult) * h
                                    idx to (offset - Offset(px, py)).getDistance()
                                }
                                .filter { it.second < 100f }
                                .minByOrNull { it.second }
                            // No monotonic constraint, all 4 points draggable.
                            if (nearest != null) {
                                dragIndex = nearest.first
                                probeSpeed = null
                            } else {
                                dragIndex = -1
                                val speed = (offset.x / w * maxSpeed).coerceIn(0f, maxSpeed)
                                probeSpeed = speed
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (dragIndex in pointsRef.value.indices) {
                                val newM = (minMult + (h - change.position.y) / h * (maxMult - minMult))
                                    .coerceIn(minMult, maxMult)
                                val (oldS, _) = pointsRef.value[dragIndex]
                                val mutable = pointsRef.value.toMutableList()
                                mutable[dragIndex] = oldS to newM
                                onPointsChanged(mutable)
                            } else {
                                val speed = (change.position.x / w * maxSpeed).coerceIn(0f, maxSpeed)
                                probeSpeed = speed
                            }
                        },
                        onDragEnd = { dragIndex = -1; probeSpeed = null },
                        onDragCancel = { dragIndex = -1; probeSpeed = null }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

            // X axis ticks at 0/25/50/75 km/h.
            for (i in 0..3) {
                val x = w * i / 3f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dash)
                val speedKmh = maxSpeed * i / 3
                val displaySpeed = Units.speed(speedKmh, speedUnit)
                val label = "${displaySpeed.roundToInt()}"
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(x - measured.size.width / 2f, h + 4f))
            }
            // Y axis ticks at 0%, 50%, 100%.
            for (i in 0..2) {
                val mult = i * 0.5f
                val y = h - (mult - minMult) / (maxMult - minMult) * h
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                val label = "${(mult * 100).toInt()}%"
                val measured = textMeasurer.measure(label, TextStyle(fontSize = 9.sp, color = labelColor))
                drawText(measured, topLeft = Offset(-measured.size.width - 4f, y - measured.size.height / 2f))
            }
            val unitMeasured = textMeasurer.measure(speedUnitLabel, TextStyle(fontSize = 10.sp, color = labelColor))
            drawText(unitMeasured, topLeft = Offset((w - unitMeasured.size.width) / 2f, h + 4f))

            // PCHIP-smoothed curve through the 4 control points + tinted fill below it.
            if (normalized.size >= 2) {
                val curvePath = Path()
                val steps = 100
                for (i in 0..steps) {
                    val speed = i.toFloat() / steps * maxSpeed
                    val mult = com.eried.eucplanet.service.pchipInterpolate(normalized, speed)
                        .coerceIn(minMult, maxMult)
                    val x = speed / maxSpeed * w
                    val y = h - (mult - minMult) / (maxMult - minMult) * h
                    if (i == 0) curvePath.moveTo(x, y) else curvePath.lineTo(x, y)
                }
                val fillPath = Path()
                fillPath.addPath(curvePath)
                fillPath.lineTo(w, h)
                fillPath.lineTo(0f, h)
                fillPath.close()
                drawPath(fillPath, color = lineColor.copy(alpha = 0.12f))
                drawPath(curvePath, color = lineColor, style = Stroke(width = 3f))
            }

            // Finger probe, dashed vertical line + "30 km/h → 45%" readout above the curve.
            val currentProbe = probeSpeed
            if (currentProbe != null && normalized.size >= 2) {
                val probeMult = com.eried.eucplanet.service.pchipInterpolate(normalized, currentProbe)
                    .coerceIn(minMult, maxMult)
                val px = currentProbe / maxSpeed * w
                val py = h - (probeMult - minMult) / (maxMult - minMult) * h
                drawLine(
                    color = probeColor.copy(alpha = 0.5f),
                    start = Offset(px, 0f),
                    end = Offset(px, h),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                )
                drawCircle(color = lineColor, radius = 6f, center = Offset(px, py))
                val displayProbe = Units.speed(currentProbe, speedUnit).roundToInt()
                val probeLabel = "$displayProbe $speedUnitLabel → ${(probeMult * 100).roundToInt()}%"
                val probeMeasured = textMeasurer.measure(
                    probeLabel,
                    TextStyle(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = probeColor)
                )
                val labelX = (px - probeMeasured.size.width / 2f)
                    .coerceIn(0f, w - probeMeasured.size.width)
                val labelY = (py - probeMeasured.size.height - 12f).coerceAtLeast(0f)
                drawText(probeMeasured, topLeft = Offset(labelX, labelY))
            }

            // Control point handles (all 4 are draggable).
            normalized.forEach { (s, v) ->
                val px = s / maxSpeed * w
                val py = h - (v - minMult) / (maxMult - minMult) * h
                drawCircle(pointColor, radius = 8f, center = Offset(px, py))
            }
        }
    }
}

/**
 * Wrap the gating toggle PLUS its conditional content with this. When
 * [expanded] flips from false to true the wrapper's bounds grow and the
 * BringIntoViewRequester fires, so the parent scroll moves to put the
 * wrapper (toggle + new content) into the viewport. Because the wrapper
 * has real bounds, not the 0-height sentinel the earlier version used , 
 * the system actually has something to scroll to. When the block is taller
 * than the viewport the BringIntoView responder aligns the TOP edge with
 * the viewport top, so the rider keeps eyes on the row they just toggled.
 *
 * Use a single instance per gating toggle. Pick [spacing] to match the
 * surrounding column's `verticalArrangement` so the wrapped block sits
 * visually identical to a flat sequence of composables.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BringIntoViewSection(
    expanded: Boolean,
    spacing: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(120)
            runCatching { requester.bringIntoView() }
        }
    }
    Column(
        modifier = Modifier.bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** Backwards-compat shim. New callers should wrap with [BringIntoViewSection];
 *  this no-op stub keeps existing inline sentinels compiling until they're
 *  refactored. */
@Composable
internal fun BringIntoViewOnFirstShow() { /* no-op */ }

// --- Long-press backup / restore helpers --------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressActionButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .height(40.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = androidx.compose.ui.semantics.Role.Button
            ),
        shape = androidx.compose.material3.ButtonDefaults.shape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NamedBackupDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    sanitize: (String) -> String?
) {
    var raw by remember { mutableStateOf(initial) }
    val sanitized = sanitize(raw)
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        // Tiny delay lets the dialog finish its mount animation; without it
        // the request can land before the field is attached and silently no-op.
        kotlinx.coroutines.delay(60)
        runCatching { focusRequester.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text(stringResource(R.string.cloud_backup_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                enabled = sanitized != null,
                onClick = { sanitized?.let(onSave) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestorePickerDialog(
    onDismiss: () -> Unit,
    loadEntries: suspend () -> List<com.eried.eucplanet.data.sync.BackupEntry>,
    onPicked: (com.eried.eucplanet.data.sync.BackupEntry) -> Unit
) {
    var entries by remember { mutableStateOf<List<com.eried.eucplanet.data.sync.BackupEntry>?>(null) }
    var selected by remember { mutableStateOf<com.eried.eucplanet.data.sync.BackupEntry?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val loaded = loadEntries()
        entries = loaded
        selected = loaded.firstOrNull()
    }
    val defaultLabel = stringResource(R.string.cloud_restore_picker_default)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_restore_confirm_title)) },
        text = {
            val list = entries
            when {
                list == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                list.isEmpty() -> Text(stringResource(R.string.cloud_restore_picker_empty))
                else -> ExposedDropdownMenuBox(
                    expanded = dropdownOpen,
                    onExpandedChange = { dropdownOpen = !dropdownOpen }
                ) {
                    OutlinedTextField(
                        value = selected?.label ?: defaultLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false }
                    ) {
                        list.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.label ?: defaultLabel) },
                                onClick = {
                                    selected = entry
                                    dropdownOpen = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selected != null,
                onClick = { selected?.let(onPicked) }
            ) { Text(stringResource(R.string.action_restore)) }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Small info row shown under the HUD server toggle when the rider has it on
 * but the phone's WiFi hotspot isn't broadcasting.
 *
 * Detecting hotspot state on modern Android is annoying:
 *   - WifiManager.isWifiApEnabled is @hide and gated by UnsupportedAppUsage
 *     on API 30+, so reflection works on some devices and silently fails on
 *     others (and always fails on the emulator).
 *   - TetheringManager is API 30+ but its query is gated behind a system
 *     permission we can't hold.
 *   - The only universal signal is the kernel-level "tether" or "softap"
 *     network interface that comes up when the user enables hotspot. It is
 *     a generally-reliable indicator across vendors.
 *
 * Strategy: try reflection first (cheapest, gives the most accurate answer
 * when it works), fall back to scanning NetworkInterface names for known
 * hotspot prefixes. If both fail we assume hotspot is OFF and surface the
 * hint -- a false positive is harmless (rider sees it, ignores it) while a
 * false negative leaves them staring at "Searching for phone..." with no
 * explanation.
 *
 * Refreshes every 5 s -- enabling a hotspot usually involves leaving the
 * Settings screen, so we don't need a faster poll.
 */
/**
 * Phone-side install hint for the HUD APK. Shown ONLY when no HUD has
 * paired yet: pre-pairing it tells a first-time rider where to get the
 * app; once a HUD pairs, the rider clearly has the app and the hint
 * becomes noise.
 *
 * Same chrome (surface + outlined icon) as [HudHotspotHint] so the two
 * banners read as a visual pair. The URL inside is a clickable link.
 *
 * Version mismatches between phone and HUD are deliberately NOT
 * reported here: the HUD-side UI carries that responsibility because
 * the rider is looking at the HUD while riding, not the phone. The
 * phone is just the config device.
 */
@Composable
private fun HudInstallHint(pairedHudVersion: String?) {
    if (pairedHudVersion != null) return

    val updateUrl = stringResource(R.string.hud_update_url)
    val raw = stringResource(R.string.hud_install_hint)
    // Visible link styling: tinted + underlined so the rider sees at
    // a glance that the URL is interactive. Without this, addLink
    // alone leaves the link visually identical to the surrounding
    // text and only reveals itself on hover (a tap target the rider
    // would not know is there).
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(raw, updateUrl, linkColor) {
        val parsed = androidx.core.text.HtmlCompat.fromHtml(
            raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        androidx.compose.ui.text.buildAnnotatedString {
            append(parsed.toString())
            parsed.getSpans(
                0, parsed.length, android.text.style.StyleSpan::class.java
            ).forEach { sp ->
                if (sp.style == android.graphics.Typeface.BOLD) {
                    val start = parsed.getSpanStart(sp)
                    val end = parsed.getSpanEnd(sp)
                    // Underlined + accent-coloured URL run, plus the
                    // LinkAnnotation that wires the tap to the system
                    // URL handler. Compose's Text composable wires
                    // these up automatically -- no Modifier.clickable
                    // needed on the row.
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = linkColor,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        start, end
                    )
                    addLink(
                        url = androidx.compose.ui.text.LinkAnnotation.Url(
                            "https://$updateUrl"
                        ),
                        start = start,
                        end = end
                    )
                }
            }
        }
    }
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HudHotspotHint() {
    val ctx = LocalContext.current
    var hotspotOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            hotspotOn = detectHotspotEnabled(ctx)
            kotlinx.coroutines.delay(5_000L)
        }
    }
    // Always informational, never alarming. surfaceVariant + the outlined
    // Info icon matches the InfoHint pattern used elsewhere in the app, so
    // this reads as a regular "here's some context" card, not a red error.
    // null = first probe in flight; suppress so the card doesn't flash.
    val on = hotspotOn ?: return
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(
                    if (on) R.string.hud_hotspot_on_hint else R.string.hud_hotspot_off_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Two-stage hotspot detection. Returns true/false when we have a signal, or
 * false when nothing answered (so the UI defaults to surfacing the hint).
 */
private fun detectHotspotEnabled(ctx: android.content.Context): Boolean {
    // Stage 1: reflection into the legacy isWifiApEnabled API.
    val viaReflection: Boolean? = runCatching {
        val wifi = ctx.applicationContext
            .getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        val m = wifi.javaClass.getMethod("isWifiApEnabled")
        m.invoke(wifi) as? Boolean
    }.getOrNull()
    if (viaReflection != null) return viaReflection

    // Stage 2: look for a SoftAP-style network interface. When the rider
    // toggles hotspot on, the kernel brings up an interface named "ap0",
    // "softap0", "wlan1" (Samsung) or "swlan0" (some MIUI). When hotspot
    // is off, none of those exist - only "wlan0" for the regular client.
    return runCatching {
        val ifs = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        ifs.any { iface ->
            val name = iface.name.orEmpty().lowercase()
            iface.isUp && !iface.isLoopback && (
                name.startsWith("ap") || name.startsWith("softap") ||
                name.startsWith("swlan") || name == "wlan1"
            )
        }
    }.getOrDefault(false)
}

// --- HUD section (lives inside the Integration tab) ---

/**
 * HUD companion settings, surfaced as a section at the bottom of the
 * Integration tab next to Flic 2 buttons and Volume keys.
 *
 * Intentionally minimal in v0.1 — one master switch plus the port and a
 * mirror-navigation toggle. Anything else is wired to the same shared
 * settings the phone already exposes (units, accent, gauge thresholds), so
 * a single source of truth governs phone + watch + HUD.
 *
 * The phone-side server is gated on [com.eried.eucplanet.data.model.AppSettings.hudServerEnabled]
 * and on the [com.eried.eucplanet.service.WheelService] being alive; it
 * binds a listening socket only while the rider has the app actively open.
 */
@Composable
private fun HudIntegrationSection(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(stringResource(R.string.section_hud_companion))

        // Phone surface is intentionally limited to the install hint:
        // version mismatches are reported on the HUD side only. The
        // HUD is the device the rider is actually looking at while
        // riding, so duplicating the banner here just made the phone
        // app shout when the rider could already see the answer on
        // the helmet. Hides itself once a HUD has paired.
        val pairedHudVersion by viewModel.hudVersion.collectAsState()
        HudInstallHint(pairedHudVersion = pairedHudVersion)

        // Hotspot hint sits ABOVE the link controls -- it's the usual
        // setup step. Optional: some riders put the HUD on their home
        // wifi or a separate router, in which case the hotspot doesn't
        // matter. The hint copy makes that explicit so a rider whose
        // hotspot is intentionally off doesn't think anything is wrong.
        HudHotspotHint()

        // IP + port live side by side as one logical input: the rider
        // reads the IP off the HUD's screen, port is almost always the
        // default. They're disabled while the link is active so an
        // accidental keystroke can't drop a live connection.
        val fieldsEnabled = !settings.hudServerEnabled
        // Local edit buffers, seeded from settings ONCE at first
        // composition and never re-keyed. The DataStore-backed write
        // back from updateHudIp / updateHudServerPort is asynchronous;
        // re-keying these on settings.* would race the IME and scramble
        // mid-edit keystrokes (testers reported "192" appearing as "921").
        var ipText by remember { mutableStateOf(settings.hudIp) }
        var portText by remember { mutableStateOf(settings.hudServerPort.toString()) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // When the link is on and the field is empty, the client falls
            // back to mDNS auto-discovery. We surface that in the LABEL
            // (which is always visible) instead of the placeholder (which
            // only shows when the field is focused) so the rider sees the
            // autodetect state without having to tap into the field first.
            val ipLabel = if (settings.hudServerEnabled && ipText.isBlank())
                stringResource(R.string.hud_ip_label_autodetect)
            else stringResource(R.string.hud_ip_label)
            OutlinedTextField(
                value = ipText,
                onValueChange = { new ->
                    if (new.length <= 15 && new.all { it.isDigit() || it == '.' }) {
                        ipText = new
                        viewModel.updateHudIp(new)
                    }
                },
                label = { Text(ipLabel) },
                // 192.168.43.42 looks like a typical Android-hotspot
                // client IP (43.0/24 is the legacy AOSP softAP subnet,
                // .42 is obviously placeholder-ish so it doesn't get
                // mistaken for an actual fixed address).
                placeholder = { Text("192.168.43.42") },
                singleLine = true,
                enabled = fieldsEnabled,
                // KeyboardType.Phone for digits + dots.
                //
                // platformImeOptions passes Android IME flags Compose
                // doesn't expose directly:
                //  - flagNoPersonalizedLearning: don't store typed IPs
                //  - flagNoExtractUi: suppress Gboard's candidate strip,
                //    which on dark theme rendered as the "black bar"
                //    below the field testers reported.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                    autoCorrectEnabled = false,
                    platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions(
                        "flagNoPersonalizedLearning|flagNoExtractUi"
                    )
                ),
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { new ->
                    if (new.length <= 5 && new.all { it.isDigit() }) {
                        portText = new
                        // Only commit to the ViewModel if the typed value
                        // is inside the legal port range. Mid-edit values
                        // like "2" would otherwise be coerced to 1024 and
                        // re-render the field, eating the cursor.
                        new.toIntOrNull()?.takeIf { it in 1024..65535 }
                            ?.let { viewModel.updateHudServerPort(it) }
                    }
                },
                label = { Text(stringResource(R.string.hud_server_port)) },
                singleLine = true,
                enabled = fieldsEnabled,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    autoCorrectEnabled = false,
                    platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions(
                        "flagNoPersonalizedLearning|flagNoExtractUi"
                    )
                ),
                modifier = Modifier.weight(1f)
            )
        }
        // Toggle goes UNDER the IP/port -- the rider configures the
        // address first and then flips the switch to dial out. Flipping
        // it also locks the fields above so the live connection can't
        // be edited out from under itself. No description below the
        // label: the rider already knows what they're enabling by this
        // point (they typed in an IP just above).
        SwitchSetting(
            label = stringResource(R.string.hud_server_enabled),
            checked = settings.hudServerEnabled,
            onCheckedChange = { viewModel.updateHudServerEnabled(it) }
        )

        // Personalize card: collapsible block that holds the screen
        // carousel customiser AND the Custom-overlay picker. Collapsed
        // by default so the Integration card stays short on first
        // visit; rider opens it when they want to trim or reorder the
        // HUD's screens. The reorderable list inside ships its order
        // to the HUD on every wire frame, so changes are live within
        // ~200 ms -- no power cycle required.
        HudPersonalizeCard(settings = settings, viewModel = viewModel)
    }
}

@Composable
private fun HudPersonalizeCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
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
                    stringResource(R.string.hud_personalize_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                    contentDescription = null
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudScreenList(settings = settings, viewModel = viewModel)
                    // Custom overlay picker sits at the bottom of the
                    // personalize block: it's another "customise what
                    // shows up on the HUD" knob and belongs next to
                    // the screen list rather than as a separate
                    // section header.
                    HudOverlayPicker(settings = settings, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun HudScreenList(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val ctx = LocalContext.current
    val known = viewModel.knownHudScreens
    val defaultEnabled = viewModel.defaultEnabledHudScreens
    // Row order comes from hudScreensOrder; enabled state from
    // hudScreensEnabled. The two are independent in storage so
    // toggling a Switch off doesn't move the row -- it stays in
    // place and just goes unchecked.
    val orderedAll = remember(settings.hudScreensOrder, known) {
        val parsed = settings.hudScreensOrder.split(",")
            .map { it.trim() }
            .filter { it in known }
            .distinct()
        parsed + known.filter { it !in parsed }
    }
    val enabledSet = remember(settings.hudScreensEnabled, defaultEnabled) {
        val parsed = settings.hudScreensEnabled.split(",")
            .map { it.trim() }
            .filter { it in known }
            .toSet()
        if (parsed.isEmpty() && settings.hudScreensEnabled.isBlank()) {
            defaultEnabled.toSet()
        } else parsed
    }
    val haptic = LocalHapticFeedback.current
    val isOnlyOneEnabled = enabledSet.size <= 1

    sh.calvin.reorderable.ReorderableColumn(
        list = orderedAll,
        onSettle = { from, to -> viewModel.moveHudScreen(from, to) },
        onMove = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        modifier = Modifier.fillMaxWidth()
    ) { _, id, _ ->
        key(id) {
            val checked = id in enabledSet
            // Prevent the rider from unchecking the LAST enabled
            // screen -- they need at least one screen on the carousel
            // or the HUD has no content to show.
            val canUncheck = !(isOnlyOneEnabled && checked)
            // Matches the chrome of [ReportRow] used by the voice
            // periodic-report list at the top of the Voice tab: drag
            // handle on the left, label in weighted middle, Material3
            // Switch on the right. No vertical padding -- the
            // ReorderableColumn handles spacing between rows.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.action_reorder),
                    modifier = Modifier
                        .draggableHandle()
                        .size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hudScreenDisplayName(ctx, id),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = { wantChecked ->
                        if (wantChecked || canUncheck) {
                            viewModel.setHudScreenEnabled(id, wantChecked)
                        }
                    },
                    enabled = wantCheckedCanFlip(checked, canUncheck)
                )
            }
        }
    }
}

/** Disable the Checkbox visually when it's the last enabled screen AND
 *  already checked -- prevents the rider from tapping the only screen
 *  they have off, while letting them tick disabled screens back on. */
private fun wantCheckedCanFlip(currentlyChecked: Boolean, canUncheck: Boolean): Boolean {
    if (!currentlyChecked) return true
    return canUncheck
}

/** Map stable HUD screen id to its localized display name. Falls back
 *  to the id itself for unknown ids so a future-shipped HUD that
 *  reports a brand-new screen still surfaces SOMETHING the rider can
 *  see and reorder. */
private fun hudScreenDisplayName(ctx: android.content.Context, id: String): String = when (id) {
    "Dashboard" -> ctx.getString(R.string.hud_screen_name_dashboard)
    "Camera" -> ctx.getString(R.string.hud_screen_name_camera)
    "Telemetry" -> ctx.getString(R.string.hud_screen_name_telemetry)
    "Custom" -> ctx.getString(R.string.hud_screen_name_custom)
    "CustomCam" -> ctx.getString(R.string.hud_screen_name_custom_cam)
    "Map" -> ctx.getString(R.string.hud_screen_name_map)
    "Nav" -> ctx.getString(R.string.hud_screen_name_nav)
    "Power" -> ctx.getString(R.string.hud_screen_name_power)
    "TripStats" -> ctx.getString(R.string.hud_screen_name_trip_stats)
    "Compass" -> ctx.getString(R.string.hud_screen_name_compass)
    "Safety" -> ctx.getString(R.string.hud_screen_name_safety)
    "BigClock" -> ctx.getString(R.string.hud_screen_name_big_clock)
    else -> id
}

@Composable
private fun HudOverlayPicker(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    var lists by remember {
        mutableStateOf<SettingsViewModel.HudOverlayLists?>(null)
    }
    var sheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(settings.syncFolderUri, settings.hudCustomOverlayName) {
        lists = viewModel.loadHudOverlayLists()
    }

    // Section block: small SUBSECTION label + 2-line description + a
    // button that opens the existing Studio LoadPresetSheet. Matches the
    // style of other settings sub-blocks: label uppercase primary,
    // bodySmall onSurfaceVariant body text, button is filled tonal.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.hud_overlay_subsection).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.hud_overlay_subsection_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.hudCustomOverlayName.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.hud_overlay_current_preset,
                    settings.hudCustomOverlayName
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { sheetOpen = true }
            ) {
                Text(stringResource(R.string.hud_overlay_select_preset))
            }
            if (settings.hudCustomOverlayName.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.pickHudOverlay("") }
                ) {
                    Text(stringResource(R.string.hud_custom_overlay_none))
                }
            }
        }
    }

    if (sheetOpen) {
        val l = lists
        // The Studio's LoadPresetSheet internally uses a verticalScroll
        // inside a side panel; that scroll needs a bounded height to
        // measure. Calling it inline inside the settings Column (itself
        // verticalScroll) gives it an infinite-height parent and
        // crashes. Wrap in a Dialog so it lives in its own window with
        // proper constraints.
        if (l != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { sheetOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                com.eried.eucplanet.ui.studio.LoadPresetSheet(
                    folderAvailable = l.folderAvailable,
                    presets = l.savedPresets,
                    bundledPresets = l.bundledPortrait,
                    bundledLandscapePresets = l.bundledLandscape,
                    onLoad = { name ->
                        viewModel.pickHudOverlay(name)
                        sheetOpen = false
                    },
                    onLoadBundled = { name ->
                        viewModel.pickHudOverlay(name)
                        sheetOpen = false
                    },
                    onDelete = { /* delete handled in the Studio, not here */ },
                    onOpenFolderSettings = { sheetOpen = false },
                    onDismiss = { sheetOpen = false }
                )
            }
        }
    }
}
