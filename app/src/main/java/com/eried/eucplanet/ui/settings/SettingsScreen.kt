package com.eried.eucplanet.ui.settings

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Motorcycle
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Watch as WatchOutlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.eried.eucplanet.ui.theme.AccentOrange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.R
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.ui.settings.eucstats.OnlineUploadOnboardingDialog
import com.eried.eucplanet.ui.settings.eucstats.flagEmoji
import com.eried.eucplanet.service.VoiceOption
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.common.InfoHint
import com.eried.eucplanet.ui.common.LocalSettingsSearchQuery
import com.eried.eucplanet.ui.common.highlightMatches
import com.eried.eucplanet.ui.theme.AccentBlue
import com.eried.eucplanet.ui.theme.AccentGreen
import com.eried.eucplanet.ui.theme.AccentPink
import com.eried.eucplanet.ui.theme.AccentPurple
import com.eried.eucplanet.ui.theme.AccentRed
import com.eried.eucplanet.ui.theme.AccentYellow
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedFieldColors
import com.eried.eucplanet.ui.theme.themedFilterChipColors
import com.eried.eucplanet.ui.theme.themedSegmentedColors
import com.eried.eucplanet.ui.theme.themedSliderColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import com.eried.eucplanet.ui.theme.themedTextButtonColors
import com.eried.eucplanet.ui.theme.themedTonalButtonColors
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
    "ja" to "日本語",
    "ko" to "한국어",
    "nl" to "Nederlands",
    "no" to "Norsk",
    "pl" to "Polski",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "sv" to "Svenska",
    "tr" to "Türkçe",
    "uk" to "Українська",
    "zh" to "简体中文",
    "zh-TW" to "繁體中文"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToFlic: () -> Unit = {},
    onNavigateToTpms: () -> Unit = {},
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

    // tab 9 opens General but scrolls to the "Battery monitor" sub-header (not the
    // General section top), so the monitor's Settings link lands right on it.
    val scrollToBattery = initialTab == 9
    val targetSectionKey = remember(initialTab) { initialTabSectionKey(initialTab) }
    // expandedSections is rememberSaveable above and only seeds on first ever
    // composition; on subsequent visits the user's saved expansion state can hide
    // the section the deep-link is trying to scroll to. Honour the incoming tab
    // by ensuring its section is expanded every time.
    androidx.compose.runtime.LaunchedEffect(targetSectionKey) {
        if (targetSectionKey != null && !expandedSections.contains(targetSectionKey)) {
            expandedSections.add(targetSectionKey)
        }
    }
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
    val titleDashboard = stringResource(R.string.tab_dashboard)

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

    val corpusDashboard = listOf(
        titleDashboard,
        stringResource(R.string.dashboard_section_metrics),
        stringResource(R.string.dashboard_section_actions),
        stringResource(R.string.dashboard_columns),
        stringResource(R.string.dashboard_rolling_window),
        stringResource(R.string.metric_chip_battery),
        stringResource(R.string.metric_chip_temperature),
        stringResource(R.string.metric_chip_voltage),
        stringResource(R.string.metric_chip_current),
        stringResource(R.string.metric_chip_load),
        stringResource(R.string.metric_chip_trip),
        stringResource(R.string.action_chip_horn),
        stringResource(R.string.action_chip_light),
        stringResource(R.string.action_chip_voice),
        stringResource(R.string.action_chip_safety),
        stringResource(R.string.action_chip_lock),
        stringResource(R.string.action_chip_record)
    ).joinToString(" ")

    val corpusDisplay = listOf(
        titleDisplay,
        stringResource(R.string.section_display),
        stringResource(R.string.units_label),
        stringResource(R.string.theme),
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
        stringResource(R.string.section_radar),
        stringResource(R.string.radar_caption),
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
            GeneralTab(settings, viewModel, scrollToBattery) { y ->
                if (targetSectionTop == null) targetSectionTop = y
            }
        },
        SectionDef("dashboard", titleDashboard, Icons.Default.Dashboard, corpusDashboard) {
            DashboardLayoutTab(settings, viewModel)
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
            ExternalGpsSection(onOpenTpms = onNavigateToTpms)
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
                                        is com.eried.eucplanet.cheats.CheatState.Result.ResetTutorial -> {
                                            viewModel.resetWelcomeTutorial()
                                            snackbarScope.launch { snackbar.showSnackbar(result.toast) }
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
                            .padding(end = 10.dp),
                        colors = themedFieldColors(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.appColors.topBar
                )
            )
        },
        snackbarHost = {
            // imePadding lifts the snackbar above the soft keyboard so cheat
            // results / unit-toggle confirms / etc don't appear behind it
            // when triggered from the Settings search field.
            SnackbarHost(snackbar, modifier = Modifier.imePadding()) {
                androidx.compose.material3.Snackbar(
                    it,
                    containerColor = MaterialTheme.appColors.snackbarBackground,
                    contentColor = MaterialTheme.appColors.snackbarText,
                    actionContentColor = MaterialTheme.appColors.snackbarAction
                )
            }
        }
    ) { padding ->
      androidx.compose.runtime.CompositionLocalProvider(
        com.eried.eucplanet.ui.common.LocalSnackbar provides snackbar,
        com.eried.eucplanet.ui.common.LocalSnackbarScope provides snackbarScope
      ) {
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
                        val sectionModifier = if (sec.key == targetSectionKey && !scrollToBattery) {
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
    9 -> "general"
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            // Body text inside a section is normal text (off-white on dark, dark
            // ink on light) — only the leading icon carries the accent. Without
            // this, un-colored labels inherited an accent content color and the
            // whole section read teal.
            contentColor = MaterialTheme.appColors.textPrimary,
        ),
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
                    // Section title is body text, not an accent: keep it the
                    // theme's primary text color (off-white on dark, dark ink on
                    // light) so it doesn't share the leading icon's accent tint.
                    color = MaterialTheme.appColors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.appColors.textSecondary
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
    viewModel: SettingsViewModel,
    scrollToBattery: Boolean = false,
    onBatteryTop: (Float) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(stringResource(R.string.section_recording))
        // 3-way auto-start, presented over the existing autoRecord /
        // autoRecordStartInMotion booleans (no new persisted field):
        //   Never     -> autoRecord off
        //   Connected -> on, runs connect -> disconnect (no idle stop)
        //   When riding-> on + motion-linked, with the idle stop below.
        val recordMode = when {
            !settings.autoRecord -> "NEVER"
            !settings.autoRecordStartInMotion -> "CONNECTED"
            else -> "RIDING"
        }
        BringIntoViewSection(expanded = recordMode == "RIDING") {
            AutoRecordModeSelector(current = recordMode) { viewModel.updateAutoRecordMode(it) }
            // Reuse the existing captions, shown for the mode they describe.
            when (recordMode) {
                "CONNECTED" -> HintText(stringResource(R.string.auto_record_caption), small = true)
                "RIDING" -> HintText(stringResource(R.string.auto_record_start_in_motion_caption), small = true)
            }
            if (recordMode == "RIDING") {
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
        }   // end recording BringIntoViewSection

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
                    shape = SegmentedButtonDefaults.itemShape(index, wheelNameOptions.size),
                    colors = themedSegmentedColors(),
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
                    shape = SegmentedButtonDefaults.itemShape(index, backOptions.size),
                    colors = themedSegmentedColors(),
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

        Box(
            modifier = if (scrollToBattery) {
                Modifier.onGloballyPositioned { onBatteryTop(it.positionInWindow().y) }
            } else Modifier,
        ) {
            SectionHeader(stringResource(R.string.charging_monitor))
        }
        SwitchSetting(
            stringResource(R.string.setting_charging_auto_open),
            settings.chargingAutoOpen,
        ) { viewModel.updateChargingAutoOpen(it) }
        SwitchSetting(
            stringResource(R.string.flic_show_on_dashboard),
            settings.chargingDashboardIcon,
        ) { viewModel.updateChargingDashboardIcon(it) }
    }
}

// --- Dashboard Layout Tab ---
//
// Editor mirrors the live dashboard's look: the active slots render as a mini
// version of the real StatCard / action-button grids, the rest of the catalog
// shows up as draggable pills below. Long-press a tile or pill to drag, drop
// on another tile to swap. Tap a tile to open the per-slot editor sheet (just
// a reset-to-default button in this pass; corner-stat configuration lands
// next). The configured order persists; wiring the live DashboardScreen to
// read from it is the next phase.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardLayoutTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    val metricOrderRaw = viewModel.dashboardMetricOrder(settings)
    val actionOrderRaw = viewModel.dashboardActionOrder(settings)

    // Column count is no longer user-configurable: it mirrors the live
    // dashboard's form-factor rules so the editor always shows the rider's
    // actual layout. Phone keeps the classic 2×3 metrics + 3×2 actions;
    // tablets/foldables (≥600dp width) bump to 3×2 metrics + 4×... but we
    // keep the active count at 6 so the rider picks the same number of
    // tiles regardless of device. The dashboardMetricsColumns /
    // dashboardActionsColumns AppSettings fields are kept for forward
    // compatibility but unused here.
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    // Tablets always render the Wide (3-column) metrics layout, overriding the
    // rider's per-account preference. On phones the View combo picks 2 (Default)
    // or 3 (Wide) columns; `dashboardMetricsColumns` stores the user's choice
    // verbatim so it round-trips when the rider switches devices.
    val metricColumnsEffective = if (isWideScreen) 3 else settings.dashboardMetricsColumns.coerceIn(2, 3)
    val actionColumnsEffective = 3
    val metricActiveCount = 6
    val actionActiveCount = 6

    // Per-slot bottom sheet: holds the metric/action key being edited and the
    // group it belongs to so the reset action targets the right list.
    var editing by remember { mutableStateOf<DashboardEditTarget?>(null) }

    // Tapping a tile in the Available (pool) region is informational only:
    // riders must drag the tile onto a slot above to use it. A short toast
    // explains the gesture instead of opening the edit sheet, which would
    // be misleading (pool-position settings are not what they're editing).
    // Throttled to one toast per 5 taps so it doesn't spam the rider while
    // they explore — the same counter governs +STACK / +TEXT / +group
    // template taps too.
    val poolTapMessage = stringResource(R.string.dashboard_pool_tap_toast)
    var poolTapCount by remember { mutableStateOf(0) }
    val poolSnackbar = com.eried.eucplanet.ui.common.LocalSnackbar.current
    val poolSnackbarScope = com.eried.eucplanet.ui.common.LocalSnackbarScope.current
    val showPoolTapToast: () -> Unit = {
        poolTapCount += 1
        if (poolTapCount % 5 == 1) {
            com.eried.eucplanet.ui.common.showSnackbar(poolSnackbar, poolSnackbarScope, poolTapMessage)
        }
    }

    // Custom drag system: the controller tracks the pointer in window
    // coordinates and the registered drop targets, so the floating preview can
    // animate its size, alpha and position live as the rider drags.
    val dragController = remember { DashboardDragController() }
    val poolAlpha by animateFloatAsState(
        targetValue = if (dragController.isDragging) 0.4f else 1f,
        label = "pool-alpha"
    )

    // Editor root's window-coordinate position is captured here so the
    // floating preview can convert the controller's window-space pointer to
    // a local offset relative to this Box and stay in the editor's own
    // composition tree (rather than a Popup window, which would steal touch
    // events from the drag source).
    var editorRootInWindow by remember { mutableStateOf(Offset.Zero) }

    // Order rendered to the grid stays equal to the persisted order during a
    // drag: changing it mid-gesture caused the dragged tile to be relaid out
    // under the rider's finger, which shifted the pointerInput's local frame
    // and produced spurious drag deltas (the drag would "lurch" or get cut
    // off). The make-space feel is now communicated via a slot highlight in
    // the destination tile + the animateBounds slide on drop.
    val metricOrder = metricOrderRaw
    val actionOrder = actionOrderRaw

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { editorRootInWindow = it.positionInWindow() }
    ) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Metrics ------------------------------------------------------
        SectionHeader(stringResource(R.string.dashboard_section_metrics))

        // Two-column row: layout choice (Default/Wide) on the left, the
        // rolling-stats window on the right. Both affect metric rendering so
        // they share a single visual band above the grid. The grid hint
        // ("Long-press to drag…") goes under this row so the riders see the
        // dropdowns first and the interaction hint right above the grid.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ViewDropdown(
                modifier = Modifier.weight(1f),
                columns = settings.dashboardMetricsColumns.coerceIn(2, 3),
                forcedWide = isWideScreen,
                onSelect = { viewModel.updateDashboardMetricsColumns(it) }
            )
            RollingWindowDropdown(
                modifier = Modifier.weight(1f),
                valueSeconds = settings.dashboardRollingWindowSeconds,
                onSelect = { viewModel.updateDashboardRollingWindowSeconds(it) }
            )
        }
        HintText(stringResource(R.string.dashboard_grid_hint), small = true)

        MetricMiniGrid(
            order = metricOrder,
            activeCount = metricActiveCount,
            columns = metricColumnsEffective,
            valueOf = { key -> metricPlaceholderValue(key, settings) },
            statsOf = { key -> viewModel.dashboardMetricSlotStats(settings, key) },
            compositeOf = { id -> viewModel.getCompositeMetric(settings, id) },
            customTileOf = { id -> viewModel.getCustomTile(settings, id) },
            onSwapInto = { key, index ->
                // Catalog model: grid sources SWAP (preserves both tiles),
                // pool sources COPY (catalog stays full, slot gets the new
                // metric, displaced tile is discarded). Templates always
                // spawn a fresh dynamic instance regardless of source.
                when {
                    key == COMPOSITE_TEMPLATE_KEY -> viewModel.createCompositeMetricAt(index)
                    key == CUSTOM_TILE_TEMPLATE_KEY -> viewModel.createCustomTileAt(index)
                    dragController.sourceFromGrid -> viewModel.moveDashboardMetricToIndex(key, index)
                    else -> viewModel.setDashboardMetricAtIndex(key, index)
                }
            },
            onTapTile = { key, slotIndex ->
                editing = when {
                    isCompositeMetricKey(key) -> DashboardEditTarget.Composite(key, slotIndex)
                    isCustomTileKey(key) -> DashboardEditTarget.CustomTile(key, slotIndex)
                    else -> DashboardEditTarget.Metric(key, slotIndex)
                }
            },
            controller = dragController
        )

        Spacer(Modifier.height(4.dp))
        Column(modifier = Modifier.alpha(poolAlpha)) {
            Text(
                stringResource(R.string.dashboard_pool_metrics),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MetricPool(
                catalogKeys = viewModel.knownDashboardMetrics,
                valueOf = { key -> metricPlaceholderValue(key, settings) },
                statsEnabledOf = { key ->
                    val s = viewModel.dashboardMetricSlotStats(settings, key)
                    s.left != DashboardStat.NONE || s.right != DashboardStat.NONE || s.sparkline
                },
                onTap = { _ -> showPoolTapToast() },
                controller = dragController
            )
        }

        // --- Action buttons ----------------------------------------------
        SectionHeader(stringResource(R.string.dashboard_section_actions))
        HintText(stringResource(R.string.dashboard_grid_hint), small = true)

        ActionMiniGrid(
            order = actionOrder,
            activeCount = actionActiveCount,
            columns = actionColumnsEffective,
            groupOf = { id -> viewModel.getActionGroup(settings, id) },
            customBleOf = { id -> viewModel.getCustomBle(settings, id) },
            onSwapInto = { key, index ->
                // Catalog model — see metric grid for explanation.
                when {
                    key == ACTION_GROUP_TEMPLATE_KEY -> viewModel.createActionGroupAt(index)
                    key == CUSTOM_BLE_TEMPLATE_KEY -> viewModel.createCustomBleAt(index)
                    dragController.sourceFromGrid -> viewModel.moveDashboardActionToIndex(key, index)
                    else -> viewModel.setDashboardActionAtIndex(key, index)
                }
            },
            onTapTile = { key, slotIndex ->
                editing = when {
                    isActionGroupKey(key) -> DashboardEditTarget.Group(key, slotIndex)
                    isCustomBleKey(key) -> DashboardEditTarget.CustomBle(key, slotIndex)
                    else -> DashboardEditTarget.Action(key, slotIndex)
                }
            },
            controller = dragController
        )

        Spacer(Modifier.height(4.dp))
        Column(modifier = Modifier.alpha(poolAlpha)) {
            Text(
                stringResource(R.string.dashboard_pool_actions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionPool(
                catalogKeys = viewModel.knownDashboardActions,
                onTap = { _ -> showPoolTapToast() },
                controller = dragController
            )
        }
    }
        // Floating drag preview rendered as a sibling of the Column inside
        // the editor's root Box. Stays in the same Compose hit-test tree as
        // the source so the drag's pointerInput keeps receiving moves.
        DashboardDragPreviewOverlay(
            controller = dragController,
            rootInWindow = editorRootInWindow,
            renderMetric = { key, value ->
                MetricDragPreview(key = key, value = value, settings = settings, viewModel = viewModel)
            },
            renderAction = { key ->
                ActionDragPreview(key = key, settings = settings, viewModel = viewModel)
            }
        )
    } // end editor root Box

    editing?.let { target ->
        // All five sheet kinds share the same "restore this slot to its
        // shipped default" semantic. The VM reset path handles deleting
        // any dynamic instance (composite / custom tile / group) that
        // happens to occupy the slot AND wiping the natural key's stats
        // so the restored metric shows its fresh out-of-box appearance.
        val onResetSlot: () -> Unit = {
            when {
                // A custom BLE command has no "shipped default" — Reset means
                // delete the command and clear the slot.
                target is DashboardEditTarget.CustomBle ->
                    viewModel.deleteCustomBle(target.key)
                target is DashboardEditTarget.Action || target is DashboardEditTarget.Group ->
                    viewModel.resetDashboardActionAtIndex(target.slotIndex)
                else ->
                    viewModel.resetDashboardMetricAtIndex(target.slotIndex)
            }
            editing = null
        }
        when (target) {
            is DashboardEditTarget.Composite -> CompositeMetricSheet(
                id = target.key,
                settings = settings,
                viewModel = viewModel,
                onDismiss = { editing = null },
                onReset = onResetSlot
            )
            is DashboardEditTarget.Group -> ActionGroupSheet(
                id = target.key,
                settings = settings,
                viewModel = viewModel,
                onDismiss = { editing = null },
                onReset = onResetSlot
            )
            is DashboardEditTarget.CustomBle -> CustomBleSheet(
                id = target.key,
                settings = settings,
                viewModel = viewModel,
                onDismiss = { editing = null },
                onReset = onResetSlot
            )
            is DashboardEditTarget.CustomTile -> CustomTileSheet(
                id = target.key,
                settings = settings,
                viewModel = viewModel,
                onDismiss = { editing = null },
                onReset = onResetSlot
            )
            else -> DashboardSlotSheet(
                target = target,
                settings = settings,
                viewModel = viewModel,
                onDismiss = { editing = null },
                onReset = onResetSlot
            )
        }
    }
}

// Visual-only renderers used by DashboardDragPreviewOverlay. They mirror the
// MetricTile / ActionTile content but skip the drag, drop and click wiring so
// they can be rendered inside the floating Popup without re-entering the drag
// state machine.

@Composable
private fun MetricDragPreview(
    key: String,
    value: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    // Composite + custom-tile instances (and their templates) render through
    // this preview path too so the dragged tile looks like what it will
    // become once the rider drops it. For templates we substitute a default
    // placeholder instance so the rider sees a representative preview.
    if (isCompositeMetricKey(key) || key == COMPOSITE_TEMPLATE_KEY) {
        val composite = if (key == COMPOSITE_TEMPLATE_KEY) MetricComposite()
        else viewModel.getCompositeMetric(settings, key) ?: MetricComposite()
        val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(surfaceColor)
                .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
        ) {
            CompositeMetricBody(
                composite = composite,
                valueOf = { k -> metricPlaceholderValue(k, settings) }
            )
        }
        return
    }
    if (isCustomTileKey(key) || key == CUSTOM_TILE_TEMPLATE_KEY) {
        val tile = if (key == CUSTOM_TILE_TEMPLATE_KEY) CustomTile()
        else viewModel.getCustomTile(settings, key) ?: CustomTile()
        val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(surfaceColor)
                .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
        ) {
            CustomTileBody(tile = tile)
        }
        return
    }
    val accent = metricAccentColor(key)
    val label = metricChipLabel(key, short = true)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val stats = viewModel.dashboardMetricSlotStats(settings, key)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
    ) {
        if (stats.sparkline) {
            WavePatternBackground(accent = accent)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            ThreeZoneRow(
                metricLabel = label,
                stats = stats,
                value = value,
                accent = accent,
                centerBigSp = 14,
                sideValueSp = 10,
                sideLabelSp = 9,
                metricLabelSp = 9
            )
        }
    }
}

@Composable
private fun ActionDragPreview(
    key: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    // Group instance + template render through this preview too, with the
    // accent-tinted folder icon so the floating preview reads as "you're
    // moving a group" rather than a plain action.
    val isGroup = isActionGroupKey(key) || key == ACTION_GROUP_TEMPLATE_KEY
    val isBle = isCustomBleKey(key) || key == CUSTOM_BLE_TEMPLATE_KEY
    val groupIconKey = if (isGroup) {
        if (key == ACTION_GROUP_TEMPLATE_KEY) GROUP_DEFAULT_ICON
        else viewModel.getActionGroup(settings, key)?.icon ?: GROUP_DEFAULT_ICON
    } else null
    val groupLabel = if (isGroup) {
        if (key == ACTION_GROUP_TEMPLATE_KEY) stringResource(R.string.dashboard_group_default_name)
        else viewModel.getActionGroup(settings, key)?.name?.ifBlank { null }
            ?: stringResource(R.string.dashboard_group_default_name)
    } else null
    val bleIconKey = if (isBle) {
        if (key == CUSTOM_BLE_TEMPLATE_KEY) CUSTOM_BLE_DEFAULT_ICON
        else viewModel.getCustomBle(settings, key)?.iconKey?.ifBlank { CUSTOM_BLE_DEFAULT_ICON }
            ?: CUSTOM_BLE_DEFAULT_ICON
    } else null
    val bleLabel = if (isBle) {
        if (key == CUSTOM_BLE_TEMPLATE_KEY) stringResource(R.string.dashboard_custom_ble_template)
        else viewModel.getCustomBle(settings, key)?.label?.ifBlank { null }
            ?: stringResource(R.string.dashboard_custom_ble_default_name)
    } else null
    val icon = when {
        isGroup -> groupIconFor(groupIconKey!!)
        isBle -> groupIconFor(bleIconKey!!)
        else -> dashboardActionIcon(key)
    }
    val label = when {
        isGroup -> groupLabel!!
        isBle -> bleLabel!!
        else -> actionChipLabel(key)
    }
    val tint = when {
        isGroup -> MaterialTheme.colorScheme.primary
        isBle -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = tint
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private sealed interface DashboardEditTarget {
    val key: String
    /** Slot index in the active grid; Reset uses this to restore the shipped occupant. */
    val slotIndex: Int
    data class Metric(override val key: String, override val slotIndex: Int) : DashboardEditTarget
    data class Action(override val key: String, override val slotIndex: Int) : DashboardEditTarget
    /** Composite metric instance — `key` is the composite ID like `M:abc123`. */
    data class Composite(override val key: String, override val slotIndex: Int) : DashboardEditTarget
    /** Action group instance — `key` is the group ID like `G:abc123`. */
    data class Group(override val key: String, override val slotIndex: Int) : DashboardEditTarget
    /** Custom tile instance — `key` is the tile ID like `C:abc123`. */
    data class CustomTile(override val key: String, override val slotIndex: Int) : DashboardEditTarget
    /** Custom BLE command instance — `key` is the command ID like `B:abc123`. */
    data class CustomBle(override val key: String, override val slotIndex: Int) : DashboardEditTarget
}

private const val DASHBOARD_DRAG_METRIC_LABEL = "eucplanet/dashMetric"
private const val DASHBOARD_DRAG_ACTION_LABEL = "eucplanet/dashAction"

// ---- Section helpers ---------------------------------------------------

@Composable
private fun DashboardColumnSelector(current: Int, onSelect: (Int) -> Unit) {
    val options = listOf(2, 3, 4)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.dashboard_columns),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 12.dp)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, n ->
                SegmentedButton(
                    selected = n == current,
                    onClick = { onSelect(n) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = themedSegmentedColors(),
                ) { Text(n.toString()) }
            }
        }
    }
}


// ---- Metric grid -------------------------------------------------------
//
// Rendered as a Column of Rows so the layout stays in one piece for drag
// detection (LazyVerticalGrid swallows the drag-and-drop modifiers).

// Custom bounds transform applied to every grid tile so column-count changes
// (2 ↔ 3 ↔ 4) animate visibly. The default BoundsTransform settles too
// quickly for small motion deltas — tiles that move only a few dp appear to
// snap. This spring is slower (Spring.StiffnessLow) and slightly underdamped
// (0.7) so motion reads as a smooth glide for both big and small repositions.
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
private val dashboardTileBoundsTransform = androidx.compose.animation.BoundsTransform { _, _ ->
    androidx.compose.animation.core.spring(
        stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
        dampingRatio = 0.75f
    )
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun MetricMiniGrid(
    order: List<String>,
    activeCount: Int,
    columns: Int,
    valueOf: (String) -> String,
    statsOf: (String) -> MetricSlotStats,
    compositeOf: (String) -> MetricComposite?,
    customTileOf: (String) -> CustomTile?,
    onSwapInto: (String, Int) -> Unit,
    onTapTile: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val active = order.take(activeCount)
    val rows = (active.size + columns - 1) / columns
    // LookaheadScope + animateBounds: when the rider drops a swap the
    // displaced tile glides to its new slot instead of teleporting.
    androidx.compose.ui.layout.LookaheadScope {
        val lookahead = this
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowIdx in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (colIdx in 0 until columns) {
                        val slotIndex = rowIdx * columns + colIdx
                        val key = active.getOrNull(slotIndex)
                        if (key != null) {
                            androidx.compose.runtime.key(key) {
                                val tileModifier = Modifier
                                    .weight(1f)
                                    .height(74.dp)
                                    .animateBounds(
                                        lookaheadScope = lookahead,
                                        boundsTransform = dashboardTileBoundsTransform
                                    )
                                when {
                                    key == EMPTY_SLOT_KEY -> {
                                        EmptyMetricSlot(
                                            slotIndex = slotIndex,
                                            modifier = tileModifier,
                                            onSwapInto = onSwapInto,
                                            controller = controller
                                        )
                                    }
                                    isCompositeMetricKey(key) -> {
                                        val composite = compositeOf(key) ?: MetricComposite()
                                        CompositeMetricTile(
                                            id = key,
                                            composite = composite,
                                            slotIndex = slotIndex,
                                            valueOf = valueOf,
                                            modifier = tileModifier,
                                            onTap = { onTapTile(key, slotIndex) },
                                            onSwapInto = onSwapInto,
                                            controller = controller
                                        )
                                    }
                                    isCustomTileKey(key) -> {
                                        val tile = customTileOf(key) ?: CustomTile()
                                        CustomTileView(
                                            id = key,
                                            tile = tile,
                                            slotIndex = slotIndex,
                                            modifier = tileModifier,
                                            onTap = { onTapTile(key, slotIndex) },
                                            onSwapInto = onSwapInto,
                                            controller = controller
                                        )
                                    }
                                    else -> {
                                        MetricTile(
                                            key = key,
                                            value = valueOf(key),
                                            stats = statsOf(key),
                                            slotIndex = slotIndex,
                                            modifier = tileModifier,
                                            onTap = { onTapTile(key, slotIndex) },
                                            onSwapInto = onSwapInto,
                                            controller = controller
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Visual placeholder for an EMPTY_SLOT_KEY slot. Renders a dashed-outline
 * box with a centred "no" glyph (a circle with a diagonal slash) so the
 * rider clearly reads "this slot is intentionally empty". Registers as a
 * drop target so dragging a tile into it fills the slot; no click handler
 * — tapping an empty slot does nothing (and never routes to a phantom
 * metric history).
 */
@Composable
private fun EmptyMetricSlot(
    slotIndex: Int,
    modifier: Modifier = Modifier,
    onSwapInto: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val isDropTarget = controller.isDragging &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.METRIC
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary else outline,
        label = "empty-slot-border"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .dashboardDropTarget(
                key = "metric-slot-$slotIndex",
                kind = DropKind.METRIC_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Composite metric tile — one grid slot rendering 2 or 3 sub-metric current
 * values in the chosen layout. Wraps the same drag/drop modifiers as
 * [MetricTile] so the rider can re-position the composite or replace it
 * with another tile.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun CompositeMetricTile(
    id: String,
    composite: MetricComposite,
    slotIndex: Int,
    valueOf: (String) -> String,
    modifier: Modifier,
    onTap: () -> Unit,
    onSwapInto: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val isBeingDragged = controller.draggingKey == id
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != id &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.METRIC
    // Accent for the highlight border picks the first cell's color so the
    // composite reads as "the rider's chosen stack" rather than a generic
    // neutral container.
    val firstCellAccent = composite.cells.firstOrNull()?.let { metricAccentColor(it) }
        ?: MaterialTheme.colorScheme.primary
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) firstCellAccent else outlineColor,
        label = "composite-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "composite-tile-border-w"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "metric-slot-$slotIndex",
                kind = DropKind.METRIC_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().alpha(if (isBeingDragged) 0f else 1f)) {
            CompositeMetricBody(composite = composite, valueOf = valueOf)
        }
    }
}

/**
 * Inner layout switcher for a composite tile. Each layout renders its cells
 * with a per-cell accent + uppercase short label; no stats, no sparkline.
 * Public-internal so the live dashboard renderer can reuse the exact same
 * body — the editor passes placeholder values, the live dashboard passes
 * real telemetry, both go through this composable.
 */
@Composable
fun CompositeMetricBody(
    composite: MetricComposite,
    valueOf: (String) -> String
) {
    val cells = composite.cells.take(composite.layout.cellCount)
    val rawStats = composite.cellStats.take(composite.layout.cellCount)
    // Pair cells with their stats first so filtering keeps them aligned,
    // THEN drop any empty / "–" cells. Empty cells used to render an
    // en-dash placeholder that took up its slot's full share of width;
    // when the rider configured (say) a COL3 tile with only 2 cells
    // populated they got "value | value | –", which read like two
    // off-center reads with a permanent dash hole. Filtering here makes
    // the populated cells share the FULL tile width / height; the
    // rider's layout choice still controls orientation (row vs column)
    // and gets the divider treatment, just over the real cell count.
    val populated = cells.mapIndexedNotNull { i, key ->
        val k = key.trim()
        // COMPOSITE_CELL_EMPTY ("(none)") collapses the slot -- drop it.
        // COMPOSITE_CELL_BLANK ("(empty)") reserves the slot -- keep it
        // so the layout count stays put; CompositeCell renders an empty Box.
        if (k.isEmpty() || k == COMPOSITE_CELL_EMPTY) null
        else k to (rawStats.getOrNull(i) ?: DashboardStat.CURRENT)
    }
    val divider = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    // Layout collapses by populated count: anything with <=1 populated cell
    // renders as a single full-tile read; 2 cells render as the rider's
    // chosen orientation (ROW2 stacks, COL2/COL3 side-by-side); only the
    // full 3 cells render as COL3.
    when {
        populated.isEmpty() -> {
            // Nothing to draw -- keep the tile blank rather than rendering
            // three dashes. This is the deliberate "empty composite" state.
        }
        populated.size == 1 -> {
            val (key, stat) = populated[0]
            // ROW-style layout for a single cell when the rider picked ROW2
            // (matches the visual mass they expected); column for everything
            // else. Either way it's a single cell filling the tile.
            if (composite.layout == CompositeLayout.ROW2) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    CompositeCellRow(key, stat, valueOf, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    CompositeCell(key, stat, valueOf, Modifier.weight(1f))
                }
            }
        }
        populated.size == 2 -> {
            if (composite.layout == CompositeLayout.ROW2) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp)
                ) {
                    CompositeCellRow(populated[0].first, populated[0].second, valueOf, Modifier.fillMaxWidth().weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(divider))
                    CompositeCellRow(populated[1].first, populated[1].second, valueOf, Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    CompositeCell(populated[0].first, populated[0].second, valueOf, Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
                    CompositeCell(populated[1].first, populated[1].second, valueOf, Modifier.weight(1f))
                }
            }
        }
        else -> Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            CompositeCell(populated[0].first, populated[0].second, valueOf, Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
            CompositeCell(populated[1].first, populated[1].second, valueOf, Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(divider))
            CompositeCell(populated[2].first, populated[2].second, valueOf, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompositeCell(
    key: String,
    stat: DashboardStat,
    valueOf: (String) -> String,
    modifier: Modifier = Modifier
) {
    val isNone = key.isEmpty() || key == COMPOSITE_CELL_EMPTY
    val isBlank = key == COMPOSITE_CELL_BLANK
    val isEmpty = isNone || isBlank
    val accent = if (!isEmpty) metricAccentColor(key) else MaterialTheme.colorScheme.onSurfaceVariant
    if (isBlank) {
        // "(empty)" placeholder: reserves the slot but draws nothing,
        // so the surrounding composite layout stays the size the rider
        // designed even though this cell intentionally has no content.
        Box(modifier = modifier)
        return
    }
    if (isNone) {
        // "(none)": the slot ITSELF is meant to read as "deliberately
        // blank, prefer to collapse". The CompositeMetricBody filter
        // drops these from the layout entirely; the dash here is for
        // any code path that still renders the cell (the editor list
        // before the filter, for instance).
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                "–",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
        return
    }
    val label = metricChipLabel(key, short = true).uppercase()
    val value = valueOf(key)
    // Stat indicator on top — only shown when the rider picked a non-
    // default stat (Min / Max / Avg / Median / P75 / etc.). Tinted with
    // the metric's accent so the cell reads as "MAX of SPEED" at a
    // glance rather than the value alone being ambiguous.
    val showStat = stat != DashboardStat.CURRENT && stat != DashboardStat.NONE
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showStat) {
                Text(
                    statShortLabel(stat),
                    fontSize = 7.sp,
                    color = accent.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    maxLines = 1
                )
            }
            Text(
                label,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * ROW2 layout cell — label LEFT, value RIGHT. The 2-row composite is wide
 * and short, so stacking label-above-value (the column form) wastes the
 * horizontal space. Putting them side-by-side reads more like a row in a
 * spec sheet ("SPEED   42 km/h"), which is what the rider asked for.
 */
@Composable
private fun CompositeCellRow(
    key: String,
    stat: DashboardStat,
    valueOf: (String) -> String,
    modifier: Modifier = Modifier
) {
    val isNone = key.isEmpty() || key == COMPOSITE_CELL_EMPTY
    val isBlank = key == COMPOSITE_CELL_BLANK
    if (isBlank) {
        Box(modifier = modifier)
        return
    }
    if (isNone) {
        Box(
            modifier = modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                stringResource(R.string.dashboard_composite_empty_label),
                fontSize = 10.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    val accent = metricAccentColor(key)
    val label = metricChipLabel(key, short = true).uppercase()
    val value = valueOf(key)
    val showStat = stat != DashboardStat.CURRENT && stat != DashboardStat.NONE
    // Label gets the squeezable slot (weight 1f, fill = true) so it expands to
    // hold the leftover space after the value's natural width — that pins the
    // value flush right while still ellipsising the label if it grows too long.
    // Stat indicator (when present) sits as a small label INLINE with the
    // metric label, separated by a · so the row reads as "MAX · SPEED  42".
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showStat) {
            Text(
                statShortLabel(stat),
                fontSize = 8.sp,
                color = accent.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetricTile(
    key: String,
    value: String,
    stats: MetricSlotStats,
    slotIndex: Int,
    modifier: Modifier,
    onSwapInto: (String, Int) -> Unit,
    onTap: () -> Unit,
    controller: DashboardDragController
) {
    val accent = metricAccentColor(key)
    val label = metricChipLabel(key, short = true)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val hasSideReadings = !stats.isDefault
    val isBeingDragged = controller.draggingKey == key
    // Animated border when this slot is the drag's drop target — gives the
    // rider visual feedback for "release here" without the recomposition
    // glitches that mid-drag layout shifts caused.
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != key &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.METRIC
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) accent else outlineColor,
        label = "metric-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "metric-tile-border-w"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = key,
                value = value,
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "metric-slot-$slotIndex",
                kind = DropKind.METRIC_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        // Drag state hides only the content — the border + surface stay so
        // the slot reads as a clearly-marked empty placeholder for the tile
        // the rider is holding.
        Box(modifier = Modifier.fillMaxSize().alpha(if (isBeingDragged) 0f else 1f)) {
            if (stats.sparkline) {
                WavePatternBackground(accent = accent)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                ThreeZoneRow(
                    metricLabel = label,
                    stats = stats,
                    value = value,
                    accent = accent,
                    centerBigSp = if (hasSideReadings) 13 else 16,
                    sideValueSp = 10,
                    sideLabelSp = 9,
                    metricLabelSp = 9
                )
            }
        }
    }
}

/**
 * Pool = catalog. Renders the metric template followed by every key in
 * [catalogKeys] sorted alphabetically by its display label. Composite and
 * custom-tile instances are NOT shown here — they live only in the grid;
 * the catalog is the rider's source-of-truth list of static metrics.
 *
 * Drop behaviour (only fires when the drag started on a big grid tile —
 * pool→pool drags are intentional no-ops):
 *   - Composite tile → delete its definition
 *   - Custom tile   → delete its definition
 *   - Static metric → demote (slot becomes empty custom tile; the static
 *     metric stays in the catalog so the rider can re-drag it)
 *
 * @param catalogKeys static metric keys present in the catalog
 * @see SettingsViewModel.knownDashboardMetrics for the catalog source
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricPool(
    catalogKeys: List<String>,
    valueOf: (String) -> String,
    statsEnabledOf: (String) -> Boolean,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    // metricChipLabel is @Composable (reads string resources), so resolve
    // labels first then sort. Stable .uppercase() avoids locale-flips
    // between "Speed" and "SPEED" when adding a metric. Sort by the same
    // short variant the pool pills render so the order matches what the
    // rider reads — alphabetising by the long label and showing the short
    // one would scramble the apparent ordering.
    val labeled = catalogKeys.map { it to metricChipLabel(it, short = true) }
    val sorted = labeled.sortedBy { it.second.uppercase() }.map { it.first }
    // Pool pill physical size in pixels — needs to live inside the composable
    // since LocalDensity is composition-scoped. Used as the "shrink-back" size
    // when a previously-grown pool pill is dragged back over this region.
    val density = LocalDensity.current
    val poolPillSizePx = androidx.compose.runtime.remember(density) {
        with(density) { androidx.compose.ui.unit.IntSize(102.dp.roundToPx(), 52.dp.roundToPx()) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Compact, scrollable pool: shows ~3.5 rows of pills so the half
            // row peeking at the bottom hints that more content scrolls into
            // view. Remaining pool items reveal via vertical scroll.
            .heightIn(max = 210.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .dashboardHintRegion(
                key = "metric-pool",
                sourceKind = DragSourceKind.METRIC,
                expectedSizePx = poolPillSizePx,
                controller = controller
            )
            // No pool drop target: dragging a big tile out to the pool is a
            // no-op (it snaps back). Dynamic instances are deleted from the
            // slot editor (Reset slot), keeping behaviour consistent with the
            // action grid.
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Composite template sits at the head of the pool as an always-
            // available source. Dragging it onto a grid slot spawns a new
            // composite instance.
            MetricCompositeTemplatePill(controller = controller)
            // Explicit custom-tile creation (text / link / QR). Drag-to-pool
            // demote was removed for consistency, so this template is now the
            // only way to add one — sits next to the + Stack composite source.
            CustomTileTemplatePill(controller = controller)
            sorted.forEach { key ->
                MetricPoolPill(
                    key = key,
                    value = valueOf(key),
                    statsEnabled = statsEnabledOf(key),
                    onTap = onTap,
                    controller = controller
                )
            }
        }
    }
}

@Composable
private fun MetricCompositeTemplatePill(
    controller: DashboardDragController
) {
    val outlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val dashSpec = remember(density) {
        androidx.compose.ui.graphics.drawscope.Stroke(
            width = with(density) { 1.5.dp.toPx() },
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(
                    with(density) { 4.dp.toPx() },
                    with(density) { 3.dp.toPx() }
                )
            )
        )
    }
    val cornerRadiusPx = with(density) { 10.dp.toPx() }
    val isBeingDragged = controller.draggingKey == COMPOSITE_TEMPLATE_KEY
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(52.dp)
            .drawBehind {
                // Dashed rounded-rect outline drawn manually because Compose's
                // `border` modifier doesn't accept a PathEffect for dashes.
                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx),
                    style = dashSpec
                )
            }
            // Templates use the metric drag-source so the controller treats
            // them like a pool pill. The downstream drop callback distinguishes
            // template key from a real key and routes to createCompositeMetricAt
            // instead of moveDashboardMetricToIndex.
            .dashboardDragSource(
                key = COMPOSITE_TEMPLATE_KEY,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .alpha(if (isBeingDragged) 0.4f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.dashboard_composite_template_label),
                fontSize = 9.sp,
                color = labelColor,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Pool source pill for the rider's custom-text/URL/QR tile. Same dashed
 * outline as the + STACK template so the two read as paired "drag me onto
 * a slot to add a special tile" actions.
 */
@Composable
private fun CustomTileTemplatePill(
    controller: DashboardDragController
) {
    val outlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val dashSpec = remember(density) {
        androidx.compose.ui.graphics.drawscope.Stroke(
            width = with(density) { 1.5.dp.toPx() },
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(
                    with(density) { 4.dp.toPx() },
                    with(density) { 3.dp.toPx() }
                )
            )
        )
    }
    val cornerRadiusPx = with(density) { 10.dp.toPx() }
    val isBeingDragged = controller.draggingKey == CUSTOM_TILE_TEMPLATE_KEY
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(52.dp)
            .drawBehind {
                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx),
                    style = dashSpec
                )
            }
            .dashboardDragSource(
                key = CUSTOM_TILE_TEMPLATE_KEY,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .alpha(if (isBeingDragged) 0.4f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.dashboard_custom_tile_template_label),
                fontSize = 9.sp,
                color = labelColor,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Custom tile renderer for both grid slots and drag preview. Shows the
 * rider's chosen icon and text. When the text is blank but a URL is set
 * (OPEN_URL / SHOW_QR action), falls back to the URL's domain so the tile
 * stays informative even before the rider types a label. A small action
 * badge in the bottom-right marks the tap behaviour (link / QR) so a
 * passenger can read the tile type at a glance.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun CustomTileView(
    id: String,
    tile: CustomTile,
    slotIndex: Int,
    modifier: Modifier,
    onTap: () -> Unit,
    onSwapInto: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val isBeingDragged = controller.draggingKey == id
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != id &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.METRIC
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) accent else outlineColor,
        label = "custom-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "custom-tile-border-w"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "metric-slot-$slotIndex",
                kind = DropKind.METRIC_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().alpha(if (isBeingDragged) 0f else 1f)) {
            // CustomTileBody already swaps its main icon to the action's
            // glyph (link / qr) for OPEN_URL / SHOW_QR tiles, so no second
            // corner badge is needed.
            CustomTileBody(tile = tile)
        }
    }
}

/**
 * Inner body of a custom tile (icon + text, with domain fallback for blank
 * text). Public-internal so the live dashboard renders the same content; the
 * editor adds the dashed-border drag-source wrapper, the live dashboard adds
 * the actual tap-action handler.
 */
@Composable
fun CustomTileBody(tile: CustomTile) {
    val labelOrFallback = tile.text.ifBlank { extractDomainHint(tile.url) }
    val labelColor = if (tile.text.isBlank() && tile.url.isBlank())
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = if (tile.text.isBlank() && tile.url.isBlank())
        androidx.compose.ui.text.font.FontStyle.Italic
        else androidx.compose.ui.text.font.FontStyle.Normal
    val displayText = labelOrFallback.ifBlank { stringResource(R.string.dashboard_composite_empty_label) }
    // For OPEN_URL and SHOW_QR tiles, replace the rider's chosen group
    // icon with the action's own glyph (link / qr) so the tile itself
    // tells you what tapping it will do. NONE-action tiles keep the
    // Truly-empty tiles (no text, no url, no action) fall back to the
    // current default-icon constant so the dashboard reads "blank slot,
    // configure me" with the same glyph the rider sees on fresh tiles.
    // Previously this was hardcoded to "EMPTY" (outlined checkbox);
    // tracking CUSTOM_TILE_DEFAULT_ICON keeps the unconfigured visual
    // aligned with whatever default the codebase has chosen.
    val isTrulyEmpty = tile.text.isBlank() && tile.url.isBlank() &&
        tile.action == CustomTileAction.NONE
    val mainIcon = when {
        isTrulyEmpty -> groupIconFor(CUSTOM_TILE_DEFAULT_ICON)
        else -> actionBadgeIcon(tile.action) ?: groupIconFor(tile.icon)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            mainIcon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            displayText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontStyle = labelStyle,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Picks the small bottom-right badge icon based on the rider's tap-action
 *  choice. None = no badge (tile is display-only). */
internal fun actionBadgeIcon(action: CustomTileAction): ImageVector? = when (action) {
    CustomTileAction.NONE -> null
    CustomTileAction.OPEN_URL -> Icons.Filled.Link
    CustomTileAction.SHOW_QR -> Icons.Filled.QrCode2
}

/**
 * Strips a URL down to a short domain hint suitable as a fallback tile
 * label. Drops scheme, "www.", and any path so "https://www.instagram.com/foo"
 * reads as "instagram.com". Returns the input verbatim when it doesn't look
 * like a URL the rider can recognise.
 */
internal fun extractDomainHint(url: String): String {
    if (url.isBlank()) return ""
    val noScheme = url
        .substringAfter("://", missingDelimiterValue = url)
        .substringAfter("@", missingDelimiterValue = url.substringAfter("://", url))
    val host = noScheme.substringBefore("/").substringBefore("?").substringBefore("#")
    val trimmed = host.removePrefix("www.").trim()
    return trimmed.ifBlank { url }
}


/**
 * Pool-sized variant of a composite tile. Same swap-with-grid drag semantics
 * as a regular pool pill, but renders the composite's actual layout + cells
 * inside the 102×52 pill so the rider sees what they have rather than the
 * raw "M:abc12345" ID. Tap routes to the composite edit sheet.
 */
@Composable
private fun CompositePoolPill(
    id: String,
    composite: MetricComposite,
    valueOf: (String) -> String,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val isBeingDragged = controller.draggingKey == id
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
            .clickable { onTap(id) }
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = false
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (isBeingDragged) 0f else 1f)
        ) {
            CompositeMetricBody(composite = composite, valueOf = valueOf)
        }
    }
}

/**
 * Pool-sized variant of a custom tile. Renders the icon + text exactly like
 * a grid tile so a custom tile that ends up in the pool (via swap with a
 * pool metric) shows up as itself rather than the raw "C:abc12345" ID.
 */
@Composable
private fun CustomTilePoolPill(
    id: String,
    tile: CustomTile,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val isBeingDragged = controller.draggingKey == id
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
            .clickable { onTap(id) }
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = false
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().alpha(if (isBeingDragged) 0f else 1f)) {
            // Action glyph already lives in CustomTileBody's centre icon.
            CustomTileBody(tile = tile)
        }
    }
}

@Composable
private fun MetricPoolPill(
    key: String,
    value: String,
    statsEnabled: Boolean,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val accent = metricAccentColor(key)
    val label = metricChipLabel(key, short = true)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val isBeingDragged = controller.draggingKey == key
    // Sized so three pills + two 6dp gaps fit inside the pool container at
    // phone width (~360dp usable after section + container padding).
    val supportsStats = metricSupportsStats(key)
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
            .clickable { onTap(key) }
            .dashboardDragSource(
                key = key,
                value = value,
                sourceKind = DragSourceKind.METRIC,
                controller = controller,
                fromGrid = false
            )
    ) {
        // Sparkline background mirrors the grid tiles' style. Three states:
        // active (full accent), dimmed (gray ghost — disabled but available
        // to re-enable on tap), or absent (metric doesn't support stats —
        // counters / heading). No corner icon: the wave itself is the
        // indicator.
        if (supportsStats) {
            val waveColor = if (statsEnabled) accent
                else MaterialTheme.colorScheme.onSurfaceVariant
            WavePatternBackground(
                accent = waveColor,
                dimmed = !statsEnabled,
                modifier = Modifier.alpha(if (isBeingDragged) 0f else 1f)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                label.uppercase(),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---- Action grid -------------------------------------------------------

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun ActionMiniGrid(
    order: List<String>,
    activeCount: Int,
    columns: Int,
    groupOf: (String) -> ActionGroup?,
    customBleOf: (String) -> com.eried.eucplanet.data.model.CustomBleCommand?,
    onSwapInto: (String, Int) -> Unit,
    onTapTile: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val active = order.take(activeCount)
    val rows = (active.size + columns - 1) / columns
    androidx.compose.ui.layout.LookaheadScope {
        val lookahead = this
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowIdx in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (colIdx in 0 until columns) {
                        val slotIndex = rowIdx * columns + colIdx
                        val key = active.getOrNull(slotIndex)
                        if (key != null) {
                            androidx.compose.runtime.key(key) {
                                val tileMod = Modifier
                                    .weight(1f)
                                    .height(86.dp)
                                    .animateBounds(
                                        lookaheadScope = lookahead,
                                        boundsTransform = dashboardTileBoundsTransform
                                    )
                                when {
                                    key == EMPTY_SLOT_KEY -> {
                                        EmptyActionSlot(
                                            slotIndex = slotIndex,
                                            modifier = tileMod,
                                            onSwapInto = onSwapInto,
                                            controller = controller
                                        )
                                    }
                                    isActionGroupKey(key) -> {
                                        val group = groupOf(key) ?: ActionGroup()
                                        ActionGroupTile(
                                            id = key,
                                            group = group,
                                            slotIndex = slotIndex,
                                            modifier = tileMod,
                                            onSwapInto = onSwapInto,
                                            onTap = { onTapTile(key, slotIndex) },
                                            controller = controller
                                        )
                                    }
                                    isCustomBleKey(key) -> {
                                        CustomBleTile(
                                            id = key,
                                            command = customBleOf(key),
                                            slotIndex = slotIndex,
                                            modifier = tileMod,
                                            onSwapInto = onSwapInto,
                                            onTap = { onTapTile(key, slotIndex) },
                                            controller = controller
                                        )
                                    }
                                    else -> {
                                        ActionTile(
                                            key = key,
                                            slotIndex = slotIndex,
                                            modifier = tileMod,
                                            onSwapInto = onSwapInto,
                                            onTap = { onTapTile(key, slotIndex) },
                                            controller = controller
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Action-grid mirror of [EmptyMetricSlot]. */
@Composable
private fun EmptyActionSlot(
    slotIndex: Int,
    modifier: Modifier = Modifier,
    onSwapInto: (String, Int) -> Unit,
    controller: DashboardDragController
) {
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val isDropTarget = controller.isDragging &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.ACTION
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) MaterialTheme.colorScheme.primary else outline,
        label = "empty-action-slot-border"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .dashboardDropTarget(
                key = "action-slot-$slotIndex",
                kind = DropKind.ACTION_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ActionTile(
    key: String,
    slotIndex: Int,
    modifier: Modifier,
    onSwapInto: (String, Int) -> Unit,
    onTap: () -> Unit,
    controller: DashboardDragController
) {
    val icon = dashboardActionIcon(key)
    val label = actionChipLabel(key)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val isBeingDragged = controller.draggingKey == key
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != key &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.ACTION
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) accent else outlineColor,
        label = "action-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "action-tile-border-w"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = key,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "action-slot-$slotIndex",
                kind = DropKind.ACTION_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        // Icon anchored at a fixed top offset so the cluster reads as
        // visually centred for a 1-line label, and a 2-line label simply
        // extends below the icon without pushing the icon up. Math: 86dp
        // tile minus (26dp icon + 4dp gap + 14dp 1-line text) = 42dp of
        // free space, halved = 21dp top padding for true centre.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 21.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Resolves a curated [GROUP_ICON_CHOICES] key to a Material icon. Used by
 * both [ActionGroupTile] and the icon picker in [ActionGroupSheet] so the
 * tile preview and the picker stay in sync — change one key here, both
 * surfaces update.
 */
internal fun groupIconFor(key: String): ImageVector = when (key) {
    "FOLDER" -> Icons.Filled.Folder
    "STAR" -> Icons.Filled.Star
    "BOLT" -> Icons.Filled.Bolt
    "FAVORITE" -> Icons.Filled.Favorite
    "DASHBOARD" -> Icons.Filled.Dashboard
    "EXTENSION" -> Icons.Filled.Extension
    "TUNE" -> Icons.Filled.Tune
    "WIDGETS" -> Icons.Filled.Widgets
    "APPS" -> Icons.Filled.Apps
    "BUILD" -> Icons.Filled.Build
    "HOME" -> Icons.Filled.Home
    "PERSON" -> Icons.Filled.Person
    "SETTINGS" -> Icons.Filled.Settings
    "SHIELD" -> Icons.Filled.Shield
    "MAP" -> Icons.Filled.Map
    "PHOTO_CAMERA" -> Icons.Filled.PhotoCamera
    "MUSIC_NOTE" -> Icons.Filled.MusicNote
    "PHONE" -> Icons.Filled.Phone
    "WIFI" -> Icons.Filled.Wifi
    "SEARCH" -> Icons.Filled.Search
    "SAVE" -> Icons.Filled.Save
    "SEND" -> Icons.Filled.Send
    "SHARE" -> Icons.Filled.Share
    "EDIT" -> Icons.Filled.Edit
    "REFRESH" -> Icons.Filled.Refresh
    "DONE" -> Icons.Filled.Done
    "INFO" -> Icons.Filled.Info
    "WARNING" -> Icons.Filled.Warning
    "NOTIFICATIONS" -> Icons.Filled.Notifications
    "LINK" -> Icons.Filled.Link
    // Outlined square placeholder for freshly-spawned / never-configured
    // custom tiles, so the dashboard doesn't pretend an unconfigured tile
    // is a link/qr. The rider can still pick this from the icon grid as
    // an explicit "blank slot" visual.
    "EMPTY" -> Icons.Filled.CheckBoxOutlineBlank
    else -> Icons.Filled.Folder
}

/**
 * Action-group tile. Renders the rider's chosen icon + name. Tap opens the
 * group edit sheet; long-press drags the whole group as a single unit (the
 * sub-actions move with it). Same drop-target semantics as a regular
 * [ActionTile] so the rider can swap a group with a plain action.
 */
@Composable
private fun ActionGroupTile(
    id: String,
    group: ActionGroup,
    slotIndex: Int,
    modifier: Modifier,
    onSwapInto: (String, Int) -> Unit,
    onTap: () -> Unit,
    controller: DashboardDragController
) {
    val icon = groupIconFor(group.icon)
    val label = group.name.ifBlank { stringResource(R.string.dashboard_group_default_name) }
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val isBeingDragged = controller.draggingKey == id
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != id &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.ACTION
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) accent else outlineColor,
        label = "group-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "group-tile-border-w"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "action-slot-$slotIndex",
                kind = DropKind.ACTION_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 21.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                // Tinted with the accent so the group reads as "different
                // from a plain action" without needing a separate badge.
                tint = accent
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Grid tile for a custom BLE command (`B:<uuid>`). Renders the rider's chosen
 * icon + label, tinted tertiary so it reads as "a custom command" distinct from
 * plain actions and groups. Tap opens [CustomBleSheet]; drag/drop mirror
 * [ActionGroupTile].
 */
@Composable
private fun CustomBleTile(
    id: String,
    command: com.eried.eucplanet.data.model.CustomBleCommand?,
    slotIndex: Int,
    modifier: Modifier,
    onSwapInto: (String, Int) -> Unit,
    onTap: () -> Unit,
    controller: DashboardDragController
) {
    val icon = groupIconFor(command?.iconKey?.ifBlank { CUSTOM_BLE_DEFAULT_ICON } ?: CUSTOM_BLE_DEFAULT_ICON)
    val label = command?.label?.ifBlank { null }
        ?: stringResource(R.string.dashboard_custom_ble_default_name)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.tertiary
    val isBeingDragged = controller.draggingKey == id
    val isDropTarget = controller.isDragging &&
        controller.draggingKey != id &&
        controller.hoveredTarget?.slotIndex == slotIndex &&
        controller.sourceKind == DragSourceKind.ACTION
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isDropTarget) accent else outlineColor,
        label = "ble-tile-border"
    )
    val borderWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDropTarget) 2.dp else 1.dp,
        label = "ble-tile-border-w"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = true
            )
            .dashboardDropTarget(
                key = "action-slot-$slotIndex",
                kind = DropKind.ACTION_GRID_SLOT,
                slotIndex = slotIndex,
                controller = controller,
                onDrop = { sourceKey -> onSwapInto(sourceKey, slotIndex) }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 21.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = accent)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Action pool = catalog. Renders the group template followed by every key
 * in [catalogKeys] sorted alphabetically by display label. Action groups
 * are dynamic instances and only live in the grid — pool drops only handle
 * group deletion. Pool→pool drags of static actions land as no-ops because
 * a static action can't be "removed" from the catalog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPool(
    catalogKeys: List<String>,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val labeled = catalogKeys.map { it to actionChipLabel(it) }
    val sorted = labeled.sortedBy { it.second.uppercase() }.map { it.first }
    val density = LocalDensity.current
    val poolPillSizePx = androidx.compose.runtime.remember(density) {
        with(density) { androidx.compose.ui.unit.IntSize(102.dp.roundToPx(), 66.dp.roundToPx()) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 254.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .dashboardHintRegion(
                key = "action-pool",
                sourceKind = DragSourceKind.ACTION,
                expectedSizePx = poolPillSizePx,
                controller = controller
            )
            // No pool drop target: dragging a big tile out to the pool is a
            // no-op (it snaps back). Group / custom-BLE instances are deleted
            // from the slot editor (Reset slot).
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Group template lives at the head of the pool as an always-
            // available source. Dragging it onto a grid slot spawns a new
            // group instance; the template stays here because we render it
            // unconditionally rather than from the order list.
            ActionGroupTemplatePill(controller = controller)
            CustomBleTemplatePill(controller = controller)
            sorted.forEach { key ->
                ActionPoolPill(key = key, onTap = onTap, controller = controller)
            }
        }
    }
}

@Composable
private fun ActionGroupTemplatePill(
    controller: DashboardDragController
) {
    val outlineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val dashSpec = remember(density) {
        androidx.compose.ui.graphics.drawscope.Stroke(
            width = with(density) { 1.5.dp.toPx() },
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(
                    with(density) { 4.dp.toPx() },
                    with(density) { 3.dp.toPx() }
                )
            )
        )
    }
    val cornerRadiusPx = with(density) { 10.dp.toPx() }
    val isBeingDragged = controller.draggingKey == ACTION_GROUP_TEMPLATE_KEY
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(66.dp)
            .drawBehind {
                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx),
                    style = dashSpec
                )
            }
            // Same action-source semantics as a pool pill — the controller
            // treats this drag like a regular action move and the downstream
            // drop callback routes the template key to createActionGroupAt.
            .dashboardDragSource(
                key = ACTION_GROUP_TEMPLATE_KEY,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .alpha(if (isBeingDragged) 0.4f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.dashboard_group_template_label),
                fontSize = 10.sp,
                color = labelColor,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Pool template for a custom BLE command. Mirrors [ActionGroupTemplatePill]:
 * a dashed "+ BLE" pill the rider drags onto a grid slot to spawn a new
 * `B:<uuid>` command (routed to createCustomBleAt) and open its editor.
 */
@Composable
private fun CustomBleTemplatePill(
    controller: DashboardDragController
) {
    val outlineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current
    val dashSpec = remember(density) {
        androidx.compose.ui.graphics.drawscope.Stroke(
            width = with(density) { 1.5.dp.toPx() },
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(with(density) { 4.dp.toPx() }, with(density) { 3.dp.toPx() })
            )
        )
    }
    val cornerRadiusPx = with(density) { 10.dp.toPx() }
    val isBeingDragged = controller.draggingKey == CUSTOM_BLE_TEMPLATE_KEY
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(66.dp)
            .drawBehind {
                drawRoundRect(
                    color = outlineColor,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx),
                    style = dashSpec
                )
            }
            .dashboardDragSource(
                key = CUSTOM_BLE_TEMPLATE_KEY,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .alpha(if (isBeingDragged) 0.4f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.dashboard_custom_ble_template),
                fontSize = 10.sp,
                color = labelColor,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * Pool-sized variant of an action-group tile. Same drag-to-grid semantics
 * as [ActionPoolPill] but renders the group's chosen icon + name inside
 * the 102×66 pool pill so a group dragged into the pool (via swap) shows
 * up as itself rather than the raw "G:abc12345" key.
 */
@Composable
private fun ActionGroupPoolPill(
    id: String,
    group: ActionGroup,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val icon = groupIconFor(group.icon)
    val label = group.name.ifBlank { stringResource(R.string.dashboard_group_default_name) }
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val isBeingDragged = controller.draggingKey == id
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
            .clickable { onTap(id) }
            .dashboardDragSource(
                key = id,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = accent
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionPoolPill(
    key: String,
    onTap: (String) -> Unit,
    controller: DashboardDragController
) {
    val icon = dashboardActionIcon(key)
    val label = actionChipLabel(key)
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val isBeingDragged = controller.draggingKey == key
    Box(
        modifier = Modifier
            .width(102.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surfaceColor)
            .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
            .clickable { onTap(key) }
            .dashboardDragSource(
                key = key,
                value = "",
                sourceKind = DragSourceKind.ACTION,
                controller = controller,
                fromGrid = false
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 16.dp)
                .alpha(if (isBeingDragged) 0f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---- Per-slot bottom sheet --------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardSlotSheet(
    target: DashboardEditTarget,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.sheetBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (target) {
                is DashboardEditTarget.Metric -> {
                    val stats = viewModel.dashboardMetricSlotStats(settings, target.key)
                    // Sheet header — the FULL descriptive metric name with
                    // plenty of width to breathe. The preview tile below
                    // shows the SHORT tile label (matching the dashboard
                    // face), so this header is the rider's single place to
                    // read the unabbreviated name they just picked. Allowed
                    // to wrap onto 2 lines so e.g. "Dynamic current limit"
                    // or "Temperatura del controlador" never gets clipped.
                    Text(
                        text = metricChipLabel(target.key, short = false),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SlotSheetMetricPreview(
                        key = target.key,
                        stats = stats,
                        valueOf = { metricPlaceholderValue(target.key, settings) },
                        onSparklineChange = { enabled ->
                            viewModel.updateDashboardMetricSparkline(target.key, enabled)
                        }
                    )
                    metricDescription(target.key)?.let { MetricInfoBox(it) }
                    SlotStatsEditor(
                        key = target.key,
                        stats = stats,
                        onCornerChange = { corner, stat ->
                            viewModel.updateDashboardMetricSlotStat(target.key, corner, stat)
                        }
                    )
                }
                is DashboardEditTarget.Action -> SlotSheetActionPreview(target.key)
                // Composite, group + custom-tile instances route to their
                // dedicated sheets upstream so these branches are unreachable;
                // keeping them for `when` exhaustiveness.
                is DashboardEditTarget.Composite,
                is DashboardEditTarget.Group,
                is DashboardEditTarget.CustomTile,
                is DashboardEditTarget.CustomBle -> Unit
            }
            SlotSheetFooter(onReset, onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Edit sheet for a composite-metric instance. Lets the rider pick the layout
 * (2-row, 2-col, 3-col) and which sub-metrics fill each cell. Deliberately
 * stats-free; the rider deletes the instance by dragging the tile off the
 * grid OR by hitting "Default metric" — which restores the slot to its
 * shipped occupant and removes the composite definition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositeMetricSheet(
    id: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val composite = viewModel.getCompositeMetric(settings, id) ?: MetricComposite()
    // Composite sheets now carry two dropdown rows per cell (metric +
    // stat) plus the preview + layout switcher, so the default half-
    // height bottom sheet feels cramped. Skip the partial state so
    // the sheet opens at full height by default.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var layout by remember(id, composite.layout) { mutableStateOf(composite.layout) }
    val baseCells = remember(id, composite.cells) {
        // Pad/truncate to 3 slots (the maximum any layout uses) so the
        // dropdowns never reach for a missing index. EMPTY is the safe
        // default for unused cells — switching to a wider layout reveals
        // a blank slot rather than auto-injecting a duplicate metric.
        val seeded = composite.cells + List(3) { COMPOSITE_CELL_EMPTY }
        androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(seeded.take(3)) }
    }
    val baseStats = remember(id, composite.cellStats) {
        val seeded = composite.cellStats + List(3) { DashboardStat.CURRENT }
        androidx.compose.runtime.mutableStateListOf<DashboardStat>().apply { addAll(seeded.take(3)) }
    }
    val padded: List<String> = baseCells.take(layout.cellCount) +
        List((layout.cellCount - baseCells.size).coerceAtLeast(0)) { COMPOSITE_CELL_EMPTY }
    val paddedStats: List<DashboardStat> = baseStats.take(layout.cellCount) +
        List((layout.cellCount - baseStats.size).coerceAtLeast(0)) { DashboardStat.CURRENT }

    fun persist(newLayout: CompositeLayout, newCells: List<String>, newStats: List<DashboardStat>) {
        viewModel.updateCompositeMetric(
            id,
            newLayout,
            newCells.take(newLayout.cellCount),
            newStats.take(newLayout.cellCount)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.sheetBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live preview of the composite using the current layout + cells.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            ) {
                CompositeMetricBody(
                    composite = MetricComposite(layout, padded, paddedStats),
                    valueOf = { k -> metricPlaceholderValue(k, settings) }
                )
            }

            // Layout segmented control — three mutually-exclusive options
            // (2 rows / 2 cols / 3 cols) feel more like a single switch
            // when bound together as a segmented row rather than three
            // independent chips. No title; the labels speak for themselves.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = CompositeLayout.values()
                options.forEachIndexed { index, lay ->
                    SegmentedButton(
                        selected = layout == lay,
                        onClick = {
                            layout = lay
                            persist(lay, baseCells, baseStats)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        colors = themedSegmentedColors(),
                    ) {
                        Text(compositeLayoutLabel(lay))
                    }
                }
            }

            // Two-row picker per cell:
            //   row 1 = which metric
            //   row 2 = which stat (Now / Min / Max / Avg / percentiles…)
            // 3 columns regardless of layout — cells past the active layout's
            // cell count grey out instead of disappearing, so the row's
            // overall height never jumps when the rider switches between
            // ROW2/COL2 (2 cells) and COL3 (3 cells).
            val cellCatalog = remember(viewModel) {
                // Placeholders first, then a visual divider, then all the
                // real metrics. The dropdown renders PICKER_DIVIDER_SENTINEL
                // as a non-clickable HorizontalDivider.
                listOf(
                    COMPOSITE_CELL_BLANK,
                    COMPOSITE_CELL_EMPTY,
                    PICKER_DIVIDER_SENTINEL
                ) + viewModel.knownDashboardMetrics
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (cellIndex in 0 until 3) {
                    val enabled = cellIndex < layout.cellCount
                    CompositeCellDropdown(
                        label = stringResource(R.string.dashboard_composite_cell_label, cellIndex + 1),
                        currentKey = baseCells.getOrNull(cellIndex) ?: "SPEED",
                        options = cellCatalog,
                        enabled = enabled,
                        onSelect = { newKey ->
                            baseCells[cellIndex] = newKey
                            persist(layout, baseCells, baseStats)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (cellIndex in 0 until 3) {
                    val enabled = cellIndex < layout.cellCount
                    CompositeCellStatDropdown(
                        currentStat = baseStats.getOrNull(cellIndex) ?: DashboardStat.CURRENT,
                        enabled = enabled,
                        onSelect = { newStat ->
                            baseStats[cellIndex] = newStat
                            persist(layout, baseCells, baseStats)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SlotSheetFooter(onReset, onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Shared footer for every per-slot bottom sheet: Restore on the left,
 * Close on the right. The "Restore slot" label is uniform across metric
 * and action sheets so it translates as a single string.
 */
@Composable
private fun SlotSheetFooter(
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onReset, colors = themedTextButtonColors()) {
            Icon(
                Icons.Filled.Restore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dashboard_restore_slot))
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onClose,
            colors = themedTonalButtonColors(),) {
            Text(stringResource(R.string.dashboard_close))
        }
    }
}

@Composable
internal fun compositeLayoutLabel(layout: CompositeLayout): String = when (layout) {
    CompositeLayout.ROW2 -> stringResource(R.string.dashboard_composite_layout_row2)
    CompositeLayout.COL2 -> stringResource(R.string.dashboard_composite_layout_col2)
    CompositeLayout.COL3 -> stringResource(R.string.dashboard_composite_layout_col3)
}

/**
 * Dropdown letting the rider choose which catalog metric fills a composite
 * cell. Reuses the same `metricChipLabel` mapping the rest of the editor
 * uses so labels stay in sync. [enabled] is false for cells past the
 * active layout's cell count — the field greys out instead of disappearing
 * so the row's footprint stays stable as the rider switches layouts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositeCellDropdown(
    label: String,
    currentKey: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        // Closed field shows the rendered glyph for the placeholders so it
        // matches how the cell will look on the dashboard ("-" for (none),
        // blank space for (empty)). Open menu items show the text labels
        // "(none)" and "(empty)" so the rider can tell the picks apart.
        val closedFieldValue = when (currentKey) {
            COMPOSITE_CELL_EMPTY -> "–"
            COMPOSITE_CELL_BLANK -> " "
            else -> metricChipLabel(currentKey)
        }
        OutlinedTextField(
            value = closedFieldValue,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { key ->
                if (key == PICKER_DIVIDER_SENTINEL) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.appColors.divider)
                } else {
                    DropdownMenuItem(
                        text = { Text(metricChipLabel(key)) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Stat picker dropdown used under each composite cell. Lets the rider
 * choose what the cell shows for its metric: Now (live value), Min, Max,
 * Avg, Sustained peak, Median, P75, P95, P99 — all computed from the
 * rolling history buffer for that metric. Greys out when the parent cell
 * is past the active layout's cell count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositeCellStatDropdown(
    currentStat: DashboardStat,
    enabled: Boolean,
    onSelect: (DashboardStat) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember {
        // Same option list the History screen used to expose. NONE is
        // omitted because "show nothing in this cell" already has its
        // own mechanism (the EMPTY metric key on the row above).
        listOf(
            DashboardStat.CURRENT,
            DashboardStat.MIN,
            DashboardStat.MAX,
            DashboardStat.AVG,
            DashboardStat.SUSTAINED_PEAK,
            DashboardStat.MEDIAN,
            DashboardStat.P75,
            DashboardStat.P95,
            DashboardStat.P99
        )
    }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = compositeCellStatLabel(currentStat),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.metric_detail_show_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(compositeCellStatLabel(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun compositeCellStatLabel(stat: DashboardStat): String = when (stat) {
    DashboardStat.NONE -> "—"
    DashboardStat.CURRENT -> stringResource(R.string.dashboard_stat_current)
    DashboardStat.MIN -> stringResource(R.string.dashboard_stat_min)
    DashboardStat.MAX -> stringResource(R.string.dashboard_stat_max)
    DashboardStat.SUSTAINED_PEAK -> stringResource(R.string.dashboard_stat_sustained_peak)
    DashboardStat.AVG -> stringResource(R.string.dashboard_stat_avg)
    DashboardStat.MEDIAN -> stringResource(R.string.dashboard_stat_median)
    DashboardStat.P75 -> stringResource(R.string.dashboard_stat_p75)
    DashboardStat.P95 -> stringResource(R.string.dashboard_stat_p95)
    DashboardStat.P99 -> stringResource(R.string.dashboard_stat_p99)
    DashboardStat.EMPTY -> stringResource(R.string.dashboard_stat_empty)
}

/**
 * Edit sheet for an action-group instance. Lets the rider rename the group,
 * pick its tile icon from a curated 10-icon set, and configure up to four
 * sub-actions in chosen order. Duplicate sub-actions are allowed (the rider
 * may want the same action twice in a popover — e.g. two RECORD_TOGGLE
 * entries). No reset / delete buttons — deletion happens by dragging the
 * tile back to the pool.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionGroupSheet(
    id: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val group = viewModel.getActionGroup(settings, id) ?: ActionGroup()
    val sheetState = rememberModalBottomSheetState()
    val defaultName = stringResource(R.string.dashboard_group_default_name)
    var name by remember(id, group.name) { mutableStateOf(group.name) }
    var icon by remember(id, group.icon) { mutableStateOf(group.icon) }
    val actionSlots = remember(id, group.actions) {
        val seeded = group.actions + List(4) { "" }
        androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(seeded.take(4)) }
    }

    fun persist() {
        viewModel.updateActionGroup(
            id = id,
            name = name,
            icon = icon,
            actions = actionSlots.filter { it.isNotEmpty() }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.sheetBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Preview tile intentionally omitted — the icon-picker button
            // already shows what the group's icon looks like, and the
            // name field shows the label. A full-width preview here would
            // duplicate both for no extra information.

            // Compact name + icon row. The icon lives as a leading button
            // inside the OutlinedTextField's row so the picker takes zero
            // extra vertical space — the rider can scan through icons from
            // a popup grid without scrolling the sheet.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Wrap in a top-padded Box so the icon-button centers
                // against the labelled OutlinedTextField (the floating
                // label adds ~8dp at the top). Mirrors the CustomTileSheet
                // icon-text band.
                Box(
                    modifier = Modifier.padding(top = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GroupIconButton(
                        currentKey = icon,
                        onSelect = {
                            icon = it
                            persist()
                        }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        persist()
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dashboard_group_name_label)) },
                    placeholder = { Text(defaultName) },
                    modifier = Modifier.weight(1f),
                    colors = themedFieldColors(),
                )
            }

            // Four sub-action dropdowns in a 2x2 grid. (none) is the
            // first option so the rider can leave a slot empty — useful
            // for groups with fewer than four actions. Packing two per
            // row keeps the sheet from getting tall, and the labels on
            // each field communicate which slot they map to.
            val actionOptions = remember(viewModel) {
                listOf("") + viewModel.knownDashboardActions
            }
            for (rowIdx in 0 until 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (col in 0 until 2) {
                        val slotIdx = rowIdx * 2 + col
                        ActionGroupSlotDropdown(
                            label = stringResource(
                                R.string.dashboard_group_action_slot,
                                slotIdx + 1
                            ),
                            currentKey = actionSlots.getOrNull(slotIdx) ?: "",
                            options = actionOptions,
                            onSelect = { newKey ->
                                actionSlots[slotIdx] = newKey
                                persist()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            SlotSheetFooter(onReset, onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Editor for a custom BLE command (`B:<uuid>`). Label + icon + target family +
 * the raw frames (one hex frame per line). Closing commits; Reset deletes the
 * command. Mirrors [ActionGroupSheet]; frames are validated live and only
 * persisted when they parse.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CustomBleSheet(
    id: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val cmd = viewModel.getCustomBle(settings, id)
        ?: com.eried.eucplanet.data.model.CustomBleCommand(
            id, "", CUSTOM_BLE_DEFAULT_ICON, "veteran", emptyList()
        )
    val sheetState = rememberModalBottomSheetState()
    var label by remember(id) { mutableStateOf(cmd.label) }
    var icon by remember(id) { mutableStateOf(cmd.iconKey) }
    var family by remember(id) { mutableStateOf(cmd.family.ifBlank { "veteran" }) }
    var framesText by remember(id) {
        mutableStateOf(com.eried.eucplanet.data.model.CustomBleCommand.framesToText(cmd.frames))
    }
    val lastValid = remember(id) { androidx.compose.runtime.mutableStateOf(cmd.frames) }
    val parsedFrames = com.eried.eucplanet.data.model.CustomBleCommand.parseFrames(framesText)
    val framesError = parsedFrames == null && framesText.isNotBlank()
    val families = listOf("veteran", "kingsong", "begode", "inmotion_v2", "inmotion_v1", "ninebot")

    fun persist() {
        // Re-parse the CURRENT text here. `parsedFrames` is a composition-scoped
        // val captured from the previous composition, so using it persisted the
        // frames a keystroke behind — a hex that only becomes valid on the last
        // character never saved, so the field looked empty on reopen (you were
        // seeing the placeholder, which happens to be the low-beam hex).
        val parsed = com.eried.eucplanet.data.model.CustomBleCommand.parseFrames(framesText)
        if (parsed != null) lastValid.value = parsed
        viewModel.updateCustomBle(id, label, icon, family, parsed ?: lastValid.value)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.appColors.sheetBackground) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    GroupIconButton(currentKey = icon, onSelect = { icon = it; persist() })
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it; persist() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dashboard_custom_ble_label)) },
                    modifier = Modifier.weight(1f),
                    colors = themedFieldColors(),
                )
            }

            var familyExpanded by remember { mutableStateOf(false) }
            androidx.compose.material3.ExposedDropdownMenuBox(
                expanded = familyExpanded,
                onExpandedChange = { familyExpanded = it }
            ) {
                OutlinedTextField(
                    value = family,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dashboard_custom_ble_family)) },
                    trailingIcon = {
                        androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = familyExpanded
                        )
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = themedFieldColors(),
                )
                ExposedDropdownMenu(
                    expanded = familyExpanded,
                    onDismissRequest = { familyExpanded = false },
                    containerColor = MaterialTheme.appColors.menuBackground
                ) {
                    families.forEach { f ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(f) },
                            onClick = {
                                family = f
                                familyExpanded = false
                                persist()
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = framesText,
                onValueChange = { framesText = it; persist() },
                label = { Text(stringResource(R.string.dashboard_custom_ble_frames)) },
                placeholder = { Text("53 65 74 4c 69 67 68 74 4f 4e") },
                isError = framesError,
                supportingText = if (framesError) {
                    { Text(stringResource(R.string.dashboard_custom_ble_frames_error)) }
                } else null,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
                colors = themedFieldColors(),
            )

            SlotSheetFooter(onReset, onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Square icon-button that shows the currently-chosen group/custom-tile
 * icon. Tapping it pops a grid of curated icons; selecting one closes
 * the popup. Lives next to the name OutlinedTextField in the edit sheet
 * so the picker contributes zero extra vertical space — and scales to
 * arbitrarily many icons via the popup's internal scroll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupIconButton(
    currentKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.outline
    Box {
        // Anchor — matches OutlinedTextField height so the row aligns
        // visually with the name field next to it.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, outline, RoundedCornerShape(8.dp))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                groupIconFor(currentKey),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(264.dp),
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            // 6-wide grid inside a fixed-width popup keeps icons square
            // and visible without scrolling for the first ~30 icons.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(8.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                GROUP_ICON_CHOICES.forEach { key ->
                    val selected = key == currentKey
                    val tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                onSelect(key)
                                expanded = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            groupIconFor(key),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = tint
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dropdown for picking a sub-action in the group edit sheet. Empty string
 * means "no action assigned" and renders as a faint placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionGroupSlotDropdown(
    label: String,
    currentKey: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.dashboard_group_action_none)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (currentKey.isEmpty()) noneLabel else actionChipLabel(currentKey),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { key ->
                DropdownMenuItem(
                    text = { Text(if (key.isEmpty()) noneLabel else actionChipLabel(key)) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Edit sheet for a custom tile (rider's text + icon + optional tap action).
 * Icon picker shares [GroupIconButton] with the action-group sheet so both
 * sheets feel like one editor family. Action picker is a 3-item segmented
 * control (None / URL / QR); the URL field only renders for the URL/QR
 * choices so the sheet stays compact for plain text labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomTileSheet(
    id: String,
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onReset: () -> Unit
) {
    val tile = viewModel.getCustomTile(settings, id) ?: CustomTile()
    val sheetState = rememberModalBottomSheetState()
    var text by remember(id, tile.text) { mutableStateOf(tile.text) }
    var icon by remember(id, tile.icon) { mutableStateOf(tile.icon) }
    var action by remember(id, tile.action) { mutableStateOf(tile.action) }
    var url by remember(id, tile.url) { mutableStateOf(tile.url) }

    fun persist() = viewModel.updateCustomTile(id, text, icon, action, url)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.appColors.sheetBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live tile preview using the rider's current draft values.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            ) {
                CustomTileBody(
                    tile = CustomTile(text = text, icon = icon, action = action, url = url)
                )
                actionBadgeIcon(action)?.let { badge ->
                    Icon(
                        badge,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier
                            .size(18.dp)
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 6.dp)
                    )
                }
            }

            // Compact icon + text row. The icon button is sized to match the
            // OutlinedTextField (56dp tall) and centred so the two controls
            // read as one band — no top-edge drift.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.padding(top = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GroupIconButton(
                        currentKey = icon,
                        onSelect = {
                            icon = it
                            persist()
                        }
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        persist()
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dashboard_custom_tile_text_label)) },
                    placeholder = {
                        Text(stringResource(R.string.dashboard_custom_tile_text_placeholder))
                    },
                    modifier = Modifier.weight(1f),
                    colors = themedFieldColors(),
                )
            }

            // Three-way segmented control: Text (no action), Open URL,
            // Show QR. NONE reads as "Text" because a custom tile with no
            // action is just a label — the rider already named it via the
            // text field above.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CustomTileAction.values().forEachIndexed { index, opt ->
                    SegmentedButton(
                        selected = action == opt,
                        onClick = {
                            action = opt
                            persist()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, CustomTileAction.values().size),
                        icon = {
                            actionBadgeIcon(opt)?.let { v ->
                                Icon(v, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        },
                        colors = themedSegmentedColors(),
                    ) {
                        Text(customTileActionLabel(opt))
                    }
                }
            }

            if (action != CustomTileAction.NONE) {
                // Rotating placeholder so the rider sees a different social /
                // donation / portfolio URL each time the sheet opens — keeps
                // the field from feeling Instagram-only. MySpace shows up
                // roughly 1-in-25 times as a nod to early-web nostalgia.
                val placeholder = remember(id) { urlPlaceholderSample() }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        persist()
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dashboard_custom_tile_url_label)) },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedFieldColors(),
                )
            }

            SlotSheetFooter(onReset, onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun customTileActionLabel(action: CustomTileAction): String = when (action) {
    CustomTileAction.NONE -> stringResource(R.string.dashboard_custom_tile_action_none)
    CustomTileAction.OPEN_URL -> stringResource(R.string.dashboard_custom_tile_action_url)
    CustomTileAction.SHOW_QR -> stringResource(R.string.dashboard_custom_tile_action_qr)
}

/**
 * Picks a random URL sample for the custom-tile URL field placeholder so the
 * rider sees a variety of services (social, donation, portfolio) instead of
 * always-Instagram. MySpace is intentionally rare (~4%) as a nostalgic
 * easter-egg for riders who'll catch the reference.
 */
private fun urlPlaceholderSample(): String {
    val common = listOf(
        "https://instagram.com/your-handle",
        "https://x.com/your-handle",
        "https://tiktok.com/@your-handle",
        "https://youtube.com/@your-channel",
        "https://patreon.com/your-handle",
        "https://buymeacoffee.com/your-handle",
        "https://ko-fi.com/your-handle",
        "https://paypal.me/your-handle",
        "https://github.com/your-handle",
        "https://threads.net/@your-handle",
        "https://reddit.com/u/your-handle",
        "https://linkedin.com/in/your-handle",
        "https://discord.gg/your-server",
        "https://twitch.tv/your-channel"
    )
    // 4% chance to surface the MySpace easter egg.
    return if (kotlin.random.Random.nextInt(25) == 0) {
        "https://myspace.com/your-handle"
    } else {
        common.random()
    }
}

@Composable
private fun SlotSheetMetricPreview(
    key: String,
    stats: MetricSlotStats,
    valueOf: () -> String,
    onSparklineChange: (Boolean) -> Unit
) {
    val accent = metricAccentColor(key)
    // Slot-sheet preview MUST mirror the dashboard tile face — same short
    // label as actually appears on the dashboard, so the preview is an
    // honest representation of what the rider will see. The FULL name is
    // shown separately as the sheet header above this preview.
    val label = metricChipLabel(key, short = true)
    val value = valueOf()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (stats.sparkline) {
            WavePatternBackground(accent = accent, animated = true)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            ThreeZoneRow(
                metricLabel = label,
                stats = stats,
                value = value,
                accent = accent,
                centerBigSp = 30,
                sideValueSp = 18,
                sideLabelSp = 11,
                metricLabelSp = 11
            )
        }
        // The trend-background toggle lives on the artifact it controls: a
        // small FilterChip in the top-right corner of the preview. When
        // selected the chip fills with secondaryContainer and shows a check
        // alongside the line-chart icon; when unselected it's outlined.
        // Tapping toggles the wave background instantly.
        FilterChip(
            selected = stats.sparkline,
            onClick = { onSparklineChange(!stats.sparkline) },
            // Default Material typography so the label baseline aligns
            // naturally with the leading icon — overriding fontSize here
            // throws off the chip's internal centering.
            label = { Text(stringResource(R.string.dashboard_slot_sparkline_chip)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.BarChart,
                    contentDescription = stringResource(R.string.dashboard_slot_sparkline),
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Asymmetric inset: 2dp from the top so the chip hugs the
                // upper edge, full 8dp on the right so it clears the
                // rounded corner.
                .padding(top = 2.dp, end = 8.dp),
            colors = themedFilterChipColors(),
        )
    }
}

/**
 * Renders the three columns (left / center / right) for a metric tile.
 *
 * The metric name lives ONLY in the centre column's label area, so the rider
 * always reads the tile's identity in the middle of the row. The centre stat
 * itself shows its own label (NOW / MIN / MAX / AVG) above the metric name
 * only when it's something other than the default CURRENT — that way the
 * common case looks clean ("BATTERY" above the big value, no "NOW" clutter).
 *
 * Arrangement: when BOTH left and right are populated the row spreads with
 * SpaceBetween so the values hug the edges; otherwise the row clusters in
 * the centre and a single side reading still looks balanced.
 */
@Composable
private fun ThreeZoneRow(
    metricLabel: String,
    stats: MetricSlotStats,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    centerBigSp: Int,
    sideValueSp: Int,
    sideLabelSp: Int,
    metricLabelSp: Int
) {
    // Match LiveMetricTile's collapse logic so the editor preview reflects
    // what the rider will actually see on the dashboard:
    //   - both sides set OR EITHER side is the (empty) placeholder ->
    //     anchored 3-zone layout (centre on tile midpoint, badges at corners).
    //   - exactly one side has a real badge -> 2-column split (badge | centre
    //     or centre | badge).
    //   - neither side set -> centred in the full width.
    val hasLeftBadge = stats.left != DashboardStat.NONE && stats.left != DashboardStat.EMPTY
    val hasRightBadge = stats.right != DashboardStat.NONE && stats.right != DashboardStat.EMPTY
    val leftReserves = stats.left == DashboardStat.EMPTY
    val rightReserves = stats.right == DashboardStat.EMPTY
    val anchorCentre = leftReserves || rightReserves || (hasLeftBadge && hasRightBadge)

    val centre: @Composable () -> Unit = {
        CenterStatBadge(
            stat = stats.center,
            metricLabel = metricLabel,
            value = value,
            accent = accent,
            centerBigSp = centerBigSp,
            statLabelSp = sideLabelSp,
            metricLabelSp = metricLabelSp
        )
    }
    val leftBadge: @Composable () -> Unit = {
        SideStatBadge(
            stat = stats.left, value = value, accent = accent,
            valueSp = sideValueSp, labelSp = sideLabelSp, align = Alignment.Start
        )
    }
    val rightBadge: @Composable () -> Unit = {
        SideStatBadge(
            stat = stats.right, value = value, accent = accent,
            valueSp = sideValueSp, labelSp = sideLabelSp, align = Alignment.End
        )
    }

    when {
        anchorCentre -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                if (hasLeftBadge) leftBadge()
            }
            Box(modifier = Modifier.weight(1.2f), contentAlignment = Alignment.TopCenter) { centre() }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
                if (hasRightBadge) rightBadge()
            }
        }
        hasLeftBadge && !hasRightBadge -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopStart) { leftBadge() }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { centre() }
        }
        hasRightBadge && !hasLeftBadge -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { centre() }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopEnd) { rightBadge() }
        }
        else -> Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) { centre() }
    }
}

@Composable
private fun SideStatBadge(
    stat: DashboardStat,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    valueSp: Int,
    labelSp: Int,
    align: Alignment.Horizontal
) {
    if (stat == DashboardStat.NONE) return
    Column(horizontalAlignment = align) {
        // Side labels share the centre label's typography (same font size +
        // weight) so the row reads as one header line, with only the value
        // below sized smaller than the centre one.
        Text(
            statShortLabel(stat),
            fontSize = labelSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
        Text(
            value,
            fontSize = valueSp.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CenterStatBadge(
    stat: DashboardStat,
    metricLabel: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    centerBigSp: Int,
    statLabelSp: Int,
    metricLabelSp: Int
) {
    val showStatLabel = stat != DashboardStat.NONE && stat != DashboardStat.CURRENT
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Stat name appears only when the center is set to a non-default
        // aggregation (MIN / MAX / AVG / MEDIAN / P95). It sits on the SAME
        // row as the metric name so the header reads as one line ("MIN
        // BATTERY") instead of stacked labels. The default "NOW" stays hidden
        // since it'd be redundant alongside the big value.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showStatLabel) {
                Text(
                    statShortLabel(stat),
                    fontSize = metricLabelSp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                metricLabel.uppercase(),
                fontSize = metricLabelSp.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (stat != DashboardStat.NONE) {
            Text(
                value,
                fontSize = centerBigSp.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun statShortLabel(stat: DashboardStat): String = when (stat) {
    DashboardStat.NONE, DashboardStat.EMPTY -> ""
    DashboardStat.CURRENT -> "NOW"
    DashboardStat.MIN -> "MIN"
    DashboardStat.MAX -> "MAX"
    DashboardStat.SUSTAINED_PEAK -> "S.PK"
    DashboardStat.AVG -> "AVG"
    DashboardStat.MEDIAN -> "MED"
    DashboardStat.P75 -> "P75"
    DashboardStat.P95 -> "P95"
    DashboardStat.P99 -> "P99"
}

/**
 * One-or-two-line description of what a metric means. Rendered as a subtle
 * info row above the side-readings dropdowns, only for metrics whose meaning
 * isn't obvious from the chip label alone (the rest get null from
 * [metricDescription]).
 */
@Composable
private fun MetricInfoBox(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotStatsEditor(
    key: String,
    stats: MetricSlotStats,
    onCornerChange: (SlotCorner, DashboardStat) -> Unit
) {
    // Picker option lists per corner.
    //   Sides (LEFT / RIGHT): placeholders first (EMPTY → "(empty)" reserves
    //     the slot, NONE → "(none)" collapses the layout), divider rendered
    //     by the dropdown, then real stats.
    //   CENTER: never a placeholder -- the centre always shows the metric's
    //     primary value. Stats are CURRENT plus the aggregates.
    val sideOptions = remember {
        listOf(
            DashboardStat.EMPTY,
            DashboardStat.NONE,
            DashboardStat.CURRENT,
            DashboardStat.MIN, DashboardStat.MAX, DashboardStat.SUSTAINED_PEAK,
            DashboardStat.AVG, DashboardStat.MEDIAN,
            DashboardStat.P75, DashboardStat.P95, DashboardStat.P99
        )
    }
    val centerOptions = remember {
        listOf(
            DashboardStat.CURRENT,
            DashboardStat.MIN, DashboardStat.MAX, DashboardStat.SUSTAINED_PEAK,
            DashboardStat.AVG, DashboardStat.MEDIAN,
            DashboardStat.P75, DashboardStat.P95, DashboardStat.P99
        )
    }
    // Self-documenting metrics (e.g. Forward G is speed-derived) explain themselves
    // right here in the editor — the descriptionRes shows as an italic subtitle.
    val descRes = remember(key) {
        com.eried.eucplanet.data.model.MetricCatalog.all.firstOrNull { it.key == key }?.descriptionRes
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        descRes?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.appColors.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CornerStatDropdown(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_corner_left),
                value = stats.left,
                options = sideOptions,
                onSelect = { onCornerChange(SlotCorner.LEFT, it) }
            )
            CornerStatDropdown(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_corner_center),
                value = stats.center,
                options = centerOptions,
                onSelect = { onCornerChange(SlotCorner.CENTER, it) }
            )
            CornerStatDropdown(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_corner_right),
                value = stats.right,
                options = sideOptions,
                onSelect = { onCornerChange(SlotCorner.RIGHT, it) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RollingWindowDropdown(
    valueSeconds: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = ROLLING_WINDOW_PRESETS_SECONDS
    val current = options.firstOrNull { it == valueSeconds }
        ?: options.minByOrNull { kotlin.math.abs(it - valueSeconds) }
        ?: ROLLING_WINDOW_DEFAULT_SECONDS
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = rollingWindowLabel(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.dashboard_stats_length)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { seconds ->
                DropdownMenuItem(
                    text = { Text(rollingWindowLabel(seconds)) },
                    onClick = {
                        onSelect(seconds)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * View selector: Default (2-column metric grid) vs Wide (3-column).
 * On tablets/foldables the layout is force-Wide regardless of the rider's
 * stored choice, so the field is shown locked to "Wide" and disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewDropdown(
    columns: Int,
    forcedWide: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val effectiveColumns = if (forcedWide) 3 else columns
    val options = listOf(2, 3)
    ExposedDropdownMenuBox(
        expanded = expanded && !forcedWide,
        onExpandedChange = { if (!forcedWide) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = viewLabel(effectiveColumns),
            onValueChange = {},
            readOnly = true,
            enabled = !forcedWide,
            singleLine = true,
            label = { Text(stringResource(R.string.dashboard_view)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && !forcedWide) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !forcedWide)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded && !forcedWide,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            options.forEach { cols ->
                DropdownMenuItem(
                    // Expanded list spells out that Wide is the tablet layout; the
                    // collapsed field keeps the short label so it doesn't overflow.
                    text = { Text(viewMenuLabel(cols)) },
                    onClick = {
                        onSelect(cols)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun viewLabel(columns: Int): String = when (columns) {
    3 -> stringResource(R.string.dashboard_view_wide)
    else -> stringResource(R.string.dashboard_view_default)
}

/** Label for the expanded dropdown only — qualifies Wide as the tablet layout so
 *  riders understand they won't normally see it; the collapsed field uses
 *  [viewLabel] so the short text fits. */
@Composable
private fun viewMenuLabel(columns: Int): String = when (columns) {
    3 -> stringResource(R.string.dashboard_view_wide_menu)
    else -> stringResource(R.string.dashboard_view_default)
}

@Composable
private fun rollingWindowLabel(seconds: Int): String = when (seconds) {
    30 -> stringResource(R.string.dashboard_rolling_window_30s)
    60 -> stringResource(R.string.dashboard_rolling_window_1m)
    120 -> stringResource(R.string.dashboard_rolling_window_2m)
    180 -> stringResource(R.string.dashboard_rolling_window_3m)
    300 -> stringResource(R.string.dashboard_rolling_window_5m)
    600 -> stringResource(R.string.dashboard_rolling_window_10m)
    900 -> stringResource(R.string.dashboard_rolling_window_15m)
    1800 -> stringResource(R.string.dashboard_rolling_window_30m)
    3600 -> stringResource(R.string.dashboard_rolling_window_1h)
    else -> "$seconds s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CornerStatDropdown(
    modifier: Modifier,
    label: String,
    value: DashboardStat,
    options: List<DashboardStat>,
    onSelect: (DashboardStat) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = statSelectedLabel(value),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            // Placeholder group (EMPTY / NONE) sits at the top, then a
            // visual divider, then real stats. Divider only renders when
            // both groups are present.
            val isPlaceholder = { s: DashboardStat ->
                s == DashboardStat.NONE || s == DashboardStat.EMPTY
            }
            options.forEachIndexed { idx, option ->
                DropdownMenuItem(
                    text = { Text(statDisplayLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
                val nextIsReal = options.getOrNull(idx + 1)?.let { !isPlaceholder(it) } == true
                if (isPlaceholder(option) && nextIsReal) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.appColors.divider)
                }
            }
        }
    }
}

/**
 * Long, descriptive label shown for an option *inside* the open dropdown list
 * (e.g. "99th percentile"). The user is browsing options here so verbose is
 * fine and helps disambiguate.
 */
@Composable
private fun statDisplayLabel(stat: DashboardStat): String = when (stat) {
    DashboardStat.NONE -> stringResource(R.string.dashboard_stat_none)
    DashboardStat.CURRENT -> stringResource(R.string.dashboard_stat_current)
    DashboardStat.MIN -> stringResource(R.string.dashboard_stat_min)
    DashboardStat.MAX -> stringResource(R.string.dashboard_stat_max)
    DashboardStat.SUSTAINED_PEAK -> stringResource(R.string.dashboard_stat_sustained_peak)
    DashboardStat.AVG -> stringResource(R.string.dashboard_stat_avg)
    DashboardStat.MEDIAN -> stringResource(R.string.dashboard_stat_median)
    DashboardStat.P75 -> stringResource(R.string.dashboard_stat_p75)
    DashboardStat.P95 -> stringResource(R.string.dashboard_stat_p95)
    DashboardStat.P99 -> stringResource(R.string.dashboard_stat_p99)
    DashboardStat.EMPTY -> stringResource(R.string.dashboard_stat_empty)
}

/**
 * Compact label shown inside the dropdown field once an option is selected
 * (e.g. "P99"). Stats whose long label is already short re-use it; percentiles
 * collapse to their short form so the field doesn't wrap to two lines.
 */
@Composable
private fun statSelectedLabel(stat: DashboardStat): String = when (stat) {
    // Closed-field glyphs for the two placeholders -- dash for the
    // collapsing "(blank)" pick, literal space for the reserving
    // "(empty)" pick. Matches the live tile visual so the rider sees
    // what they're getting without re-opening the picker.
    DashboardStat.NONE -> "–"
    DashboardStat.CURRENT -> stringResource(R.string.dashboard_stat_current)
    DashboardStat.MIN -> stringResource(R.string.dashboard_stat_min)
    // MAX's long label is "Max / Peak"; in the closed dropdown field we use
    // the bare "Max" to keep the row compact.
    DashboardStat.MAX -> "Max"
    DashboardStat.SUSTAINED_PEAK -> stringResource(R.string.dashboard_stat_sustained_peak_short)
    DashboardStat.AVG -> stringResource(R.string.dashboard_stat_avg)
    DashboardStat.MEDIAN -> stringResource(R.string.dashboard_stat_median_short)
    DashboardStat.P75 -> stringResource(R.string.dashboard_stat_p75_short)
    DashboardStat.P95 -> stringResource(R.string.dashboard_stat_p95_short)
    DashboardStat.P99 -> stringResource(R.string.dashboard_stat_p99_short)
    DashboardStat.EMPTY -> " "
}

/** True when the stat is a deliberate "reserve this slot" placeholder. The
 *  tile renderer uses this to keep the 3-zone layout intact instead of
 *  collapsing into 2 equal halves when only one real side reading is set. */
internal fun isReservedSlot(stat: DashboardStat): Boolean = stat == DashboardStat.EMPTY

@Composable
private fun SlotSheetActionPreview(key: String) {
    val icon = dashboardActionIcon(key)
    val label = actionChipLabel(key)
    // Center a button-proportioned preview tile. Height matches the
    // metric preview (140dp) for visual consistency between metric and
    // action sheets; width preserves the live ActionTile aspect ratio
    // (~120:86) so the rider still recognises this as a button rather
    // than a metric card. Icon + label scale up proportionally.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(140.dp)
                .aspectRatio(120f / 86f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    label,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---- Catalog look-up helpers (shared with the live dashboard) ----------
//
// All of these are `internal` so the eventual `com.eried.eucplanet.ui.dashboard`
// renderer can call them directly — keeps a single source of truth for chip
// labels, accent palette, action icons, etc. between the editor preview and
// the live dashboard. Adding a new metric / action means updating one of
// these tables, not two.

/**
 * Display label for a metric key. Reads from [com.eried.eucplanet.data.model.MetricCatalog]
 * so adding a new metric is one entry in `MetricCatalog.all` — this
 * function never needs to grow another branch. The composite-empty
 * sentinel and unknown keys fall back to bespoke strings.
 *
 * When [short] is true, prefers the `metric_chip_<key>_tile` resource
 * if it exists, falling back to the full label. Tile / pool / grid-
 * preview render sites pass true so what the rider sees in the editor
 * matches what they'll see on the actual dashboard tile face; picker
 * dropdowns and the slot-edit sheet header keep `short = false` so
 * the rider always sees the FULL descriptive name when choosing.
 */
@Composable
internal fun metricChipLabel(key: String, short: Boolean = false): String {
    if (key == COMPOSITE_CELL_EMPTY) return stringResource(R.string.dashboard_composite_empty_label)
    if (key == COMPOSITE_CELL_BLANK) return stringResource(R.string.dashboard_composite_blank_label)
    val spec = com.eried.eucplanet.data.model.MetricCatalog.byKey(key) ?: return key
    if (short) {
        val ctx = LocalContext.current
        val tileRes = remember(key) {
            ctx.resources.getIdentifier(
                "metric_chip_${key.lowercase()}_tile", "string", ctx.packageName
            )
        }
        if (tileRes != 0) return stringResource(tileRes)
    }
    return stringResource(spec.labelRes)
}

/**
 * 1-2 sentence explanation surfaced in the slot-sheet info box for
 * metrics whose meaning isn't obvious from the chip label. Pulls
 * [com.eried.eucplanet.data.model.MetricSpec.descriptionRes] from the
 * catalog — null when the metric has no description.
 */
@Composable
internal fun metricDescription(key: String): String? {
    val spec = com.eried.eucplanet.data.model.MetricCatalog.byKey(key) ?: return null
    val resId = spec.descriptionRes ?: return null
    return stringResource(resId)
}

/**
 * Display label for an action key. Reads from [ActionCatalog] so adding
 * a new action only needs a catalog entry — this function never needs to
 * grow another branch.
 */
@Composable
internal fun actionChipLabel(key: String): String {
    val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key)
    return if (spec != null) stringResource(spec.labelRes) else key
}

/**
 * True when min/max/avg/percentile stats are meaningful for this metric.
 * Monotonic counters (`TRIP`, `ODOMETER`, accumulating trip-time/energy) and
 * circular values (`GPS_HEADING`) return false — for those the pool pill
 * shows no sparkline background at all, since "stats off" isn't a state the
 * rider can toggle out of.
 */
/**
 * True when min/max/avg/percentile stats are meaningful for this metric.
 * Monotonic counters and circular values (GPS_HEADING) return false.
 * Reads from [com.eried.eucplanet.data.model.MetricCatalog].
 */
internal fun metricSupportsStats(key: String): Boolean =
    com.eried.eucplanet.data.model.MetricCatalog.byKey(key)?.supportsStats ?: true

/**
 * Accent palette for a metric — drives the value text + sparkline tint.
 * Reads from [com.eried.eucplanet.data.model.MetricCatalog]; unknown keys
 * (including the COMPOSITE_CELL_EMPTY sentinel) fall through to AccentBlue.
 */
internal fun metricAccentColor(key: String): androidx.compose.ui.graphics.Color =
    com.eried.eucplanet.data.model.MetricCatalog.byKey(key)?.accent ?: AccentBlue

/**
 * Vector icon for an action key. Reads from [ActionCatalog] so adding a
 * new action only needs a catalog entry. Returns null for unknown keys.
 */
internal fun dashboardActionIcon(key: String): ImageVector? =
    com.eried.eucplanet.data.model.ActionCatalog.byKey(key)?.icon

// Drag decoration shared by every draggable tile + pool pill in the dashboard
// editor: a translucent rounded card the size of the source with an accent-
// colored border, and the metric/action's own label + value (or icon + label
// for actions) rendered on top so the rider can clearly see what's in their
// hand mid-drag.
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashboardTileDecoration(
    fill: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    labelText: String,
    valueText: String,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer
) {
    val cr = CornerRadius(10.dp.toPx())
    drawRoundRect(color = fill.copy(alpha = 0.92f), cornerRadius = cr)
    drawRoundRect(
        color = accent,
        cornerRadius = cr,
        style = Stroke(width = 3.dp.toPx())
    )

    val labelResult = measurer.measure(labelText, labelStyle)
    val gap = 2.dp.toPx()
    if (valueText.isNotEmpty()) {
        val valueResult = measurer.measure(valueText, valueStyle)
        val totalH = labelResult.size.height + gap + valueResult.size.height
        val startY = (size.height - totalH) / 2f
        drawText(
            labelResult,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = (size.width - labelResult.size.width) / 2f,
                y = startY
            )
        )
        drawText(
            valueResult,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = (size.width - valueResult.size.width) / 2f,
                y = startY + labelResult.size.height + gap
            )
        )
    } else {
        drawText(
            labelResult,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = (size.width - labelResult.size.width) / 2f,
                y = (size.height - labelResult.size.height) / 2f
            )
        )
    }
}

// Stylized "histogram" wave drawn behind the tile when the rider has the
// trend background toggled on. Rendered as 30 horizontal step segments at
// quantised sine heights so it reads as a stepped sparkline rather than a
// smooth curve — matches the rhythm of real 1Hz dashboard samples connected
// by straight segments. When [animated] is true the wave's phase shifts over
// time so the staircase visibly scrolls; used in the bottom-sheet preview to
// hint that this is a *live* trend feature, while the small grid tiles stay
// static so the grid itself doesn't feel restless.
@Composable
private fun WavePatternBackground(
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    steps: Int = 30,
    /**
     * When `true`, renders the wave at a much fainter alpha so it reads as
     * "stats are available but disabled — tap to re-enable" instead of an
     * active sparkline. Used by the pool pills to distinguish disabled
     * metrics from those that don't support stats at all (the latter omit
     * the background entirely).
     */
    dimmed: Boolean = false
) {
    val phase: Float = if (animated) {
        val transition = rememberInfiniteTransition(label = "wave")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * kotlin.math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4500, easing = LinearEasing)
            ),
            label = "wave-phase"
        )
        v
    } else {
        0f
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val cycles = 2.0
        val amplitude = size.height * 0.22f
        val centerY = size.height / 2f
        val stepW = size.width / steps.toFloat()
        val path = Path()
        // Build the staircase: each step is a horizontal segment whose Y is
        // sampled from a sine at the step's midpoint. Connecting segments are
        // straight vertical drops so the line reads as discrete columns.
        for (i in 0 until steps) {
            val midT = (i.toFloat() + 0.5f) / steps
            val sineArg = midT * cycles * 2.0 * kotlin.math.PI - phase
            val sineVal = kotlin.math.sin(sineArg).toFloat()
            val y = centerY - sineVal * amplitude
            val x0 = i * stepW
            val x1 = (i + 1) * stepW
            if (i == 0) path.moveTo(x0, y) else path.lineTo(x0, y)
            path.lineTo(x1, y)
        }
        // Soft filled area under the staircase for the StatCard-style gradient.
        val fillPath = Path()
        fillPath.addPath(path)
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()
        // Kept deliberately faint so the readings stay the focus — the wave
        // is a hint, not a chart. The dimmed variant fades further so a
        // "disabled but available" wave reads as a ghost behind the pill.
        val fillAlpha = if (dimmed) 0.015f else 0.04f
        val strokeAlpha = if (dimmed) 0.06f else 0.18f
        drawPath(fillPath, color = accent.copy(alpha = fillAlpha))
        drawPath(path, color = accent.copy(alpha = strokeAlpha), style = Stroke(width = 1.5.dp.toPx()))
    }
}

// Deterministic per-metric sparkline silhouette so the preview tiles aren't
// empty rectangles. Each metric gets a stable shape derived from its key.
private fun syntheticSpark(key: String): List<Float> {
    val seed = key.hashCode().toLong()
    val rng = java.util.Random(seed)
    return List(24) { rng.nextFloat() }
}

/** Swaps `draggedKey` with whatever currently sits at `targetSlotIndex` so the
 *  grid can render a mid-drag tentative order. Real persistence happens on
 *  drop via the ViewModel — this is purely visual. */
private fun previewSwap(order: List<String>, draggedKey: String, targetSlotIndex: Int): List<String> {
    val items = order.toMutableList()
    val from = items.indexOf(draggedKey)
    if (from < 0 || targetSlotIndex !in items.indices || from == targetSlotIndex) return order
    val a = items[from]
    items[from] = items[targetSlotIndex]
    items[targetSlotIndex] = a
    return items
}

/**
 * Placeholder reading for a not-yet-connected metric in the editor preview.
 * Always uses zero as the value but appends the correct unit so the rider
 * sees how the tile will read on a live ride. Respects the rider's selected
 * speed / distance / temperature units from [AppSettings].
 */
private fun metricPlaceholderValue(
    key: String,
    s: com.eried.eucplanet.data.model.AppSettings
): String = when (key) {
    "BATTERY", "BATTERY_1", "BATTERY_2", "LOAD" -> "0%"
    "TEMPERATURE" -> if (s.unitTemp == "F") "0°F" else "0°C"
    "VOLTAGE" -> "0 V"
    "CURRENT", "DYN_CURRENT_LIMIT" -> "0 A"
    "POWER", "MOTOR_POWER", "BATTERY_POWER" -> "0 W"
    "TRIP", "ODOMETER" -> when (s.unitDistance) {
        "mi" -> "0 mi"
        "mil" -> "0 mil"
        else -> "0 km"
    }
    "SPEED", "DYN_SPEED_LIMIT" -> when (s.unitSpeed) {
        "mph" -> "0 mph"
        "kn" -> "0 kn"
        else -> "0 km/h"
    }
    "PITCH", "ROLL" -> "0°"
    "G_FORCE", "LATERAL_G", "FORWARD_G" -> "0.0 g"
    "TORQUE" -> "0 Nm"
    "MOTOR_TEMP", "CONTROLLER_TEMP", "BATTERY_TEMP" ->
        if (s.unitTemp == "F") "0°F" else "0°C"
    "HEADROOM", "TRIP_MAX_SPEED", "AVG_TRIP_SPEED", "GPS_SPEED" -> when (s.unitSpeed) {
        "mph" -> "0 mph"
        "kn" -> "0 kn"
        else -> "0 km/h"
    }
    "TRIP_TIME" -> "0:00"
    "WH_CONSUMED" -> "0 Wh"
    "RANGE_ESTIMATE" -> when (s.unitDistance) {
        "mi" -> "0 mi"
        "mil" -> "0 mil"
        else -> "0 km"
    }
    "WH_PER_KM" -> when (s.unitDistance) {
        "mi" -> "0 Wh/mi"
        "mil" -> "0 Wh/mil"
        else -> "0 Wh/km"
    }
    "PHONE_BATTERY" -> "0%"
    "GPS_ALTITUDE" -> if (s.unitDistance == "mi") "0 ft" else "0 m"
    "GPS_HEADING" -> "0°"
    "GPS_ACCURACY" -> if (s.unitDistance == "mi") "0 ft" else "0 m"
    "SLOPE" -> "0 %"
    "ASCENT", "DESCENT" -> if (s.unitDistance == "mi") "0 ft" else "0 m"
    "MOTOR_RPM" -> "0 rpm"
    "REGEN_WH" -> "0 Wh"
    "BT_RSSI" -> "0 dBm"
    // EMPTY cell renders no value — the cell composable already shows the
    // "(empty)" placeholder text via the chip-label path instead.
    COMPOSITE_CELL_EMPTY -> ""
    else -> "--"
}

// --- Display Tab ---

@Composable
private fun DisplayTab(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    // Theme combo: built-in themes (Light, Dark, Pure Black) + saved customs
    // (visible once a backup folder is set). Replaces the legacy theme-mode +
    // accent pickers — the accent is now the active theme's `primary` token.
    val themeChoices = viewModel.themeChoices.collectAsState().value
    val themeDirty = viewModel.themeDirty.collectAsState().value
    LaunchedEffect(settings.activeThemeName, settings.syncFolderUri, themeDirty) {
        viewModel.refreshThemeChoices()
    }
    val currentTheme = settings.activeThemeName.ifEmpty {
        themeChoices.builtIns.firstOrNull() ?: "Pure Black"
    }

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

        ThemeDropdown(
            label = stringResource(R.string.theme),
            current = if (themeDirty) "$currentTheme (unsaved)" else currentTheme,
            builtIns = themeChoices.builtIns,
            saved = themeChoices.saved,
            unsaved = themeChoices.unsaved,
            onSelect = { viewModel.selectTheme(it) },
            onSelectUnsaved = { viewModel.selectUnsavedTheme(it) }
        )

        SwitchSetting("Theme customization widget", settings.themeEditorEnabled) {
            viewModel.setThemeEditorEnabled(it)
        }

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
                safeColor = MaterialTheme.appColors.gaugeFill,
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
                    shape = SegmentedButtonDefaults.itemShape(index, systemOptions.size),
                    colors = themedSegmentedColors(),
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
        // Lower bound is 0 km/h: some Begode / Veteran wheels report
        // tiltback at 0 (= disabled) or a very low value the rider set
        // on the wheel itself, and clamping the slider's floor at 10
        // used to produce inverted ranges (10..0) that crashed the screen.
        SpeedSliderSetting(
            label = stringResource(R.string.speed_tiltback),
            valueKmh = settings.tiltbackSpeedKmh,
            rangeKmh = 0f..maxSpeedCap,
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateTiltbackSpeed(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_alarm),
            valueKmh = settings.alarmSpeedKmh,
            rangeKmh = 0f..settings.tiltbackSpeedKmh,
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateAlarmSpeed(it) }
        )

        SectionHeader(stringResource(R.string.section_legal_mode_speed))
        HintText(stringResource(R.string.legal_mode_caption))
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_tiltback),
            valueKmh = settings.safetyTiltbackKmh,
            rangeKmh = 0f..(settings.tiltbackSpeedKmh - 1f).coerceAtLeast(0f),
            speedUnit = speedUnit,
            enabled = isConnected,
            onValueChangeKmh = { viewModel.updateSafetyTiltback(it) }
        )
        SpeedSliderSetting(
            label = stringResource(R.string.speed_legal_alarm),
            valueKmh = settings.safetyAlarmKmh,
            rangeKmh = 0f..settings.safetyTiltbackKmh,
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

        // Speech speed, while-speaking audio focus, and output channel
        // are tuning knobs most riders accept the defaults for. Tucked
        // behind an Advanced collapsable so the Speech section stays
        // focused on the rider's most-common task: picking the voice.
        AdvancedCollapsable(
            title = stringResource(R.string.section_voice_advanced),
            stateKey = "voice-advanced"
        ) {
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
        }

        SectionHeader(stringResource(R.string.section_announcements))

        BringIntoViewSection(expanded = settings.voiceEnabled) {
        SwitchSetting(stringResource(R.string.voice_enabled), settings.voiceEnabled) {
            viewModel.updateVoiceEnabled(it)
        }

        if (settings.voiceEnabled) {
            AnnounceWhenSelector(
                current = settings.voiceAnnounceWhen,
                onChange = { viewModel.updateVoiceAnnounceWhen(it) }
            )
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
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.appColors.divider.copy(alpha = 0.2f))

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

        // Top-bar visibility switch. Some riders never use Flic and find the
        // small indicator visually noisy. Default true preserves the previous
        // always-on rendering.
        SwitchSetting(
            label = stringResource(R.string.flic_show_on_dashboard),
            checked = settings.flicShowOnDashboard,
            onCheckedChange = { viewModel.updateFlicShowOnDashboard(it) }
        )

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
                        containerColor = MaterialTheme.appColors.statusDanger
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
                    Text(stringResource(R.string.volume_up), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.appColors.primary)
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
                    Text(stringResource(R.string.volume_down), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.appColors.primary)
                    ActionDropdown(stringResource(R.string.flic_click), settings.volumeDownClick) { settingsViewModel.updateVolumeDownClick(it) }
                    ActionDropdown(stringResource(R.string.flic_hold), settings.volumeDownHold) { settingsViewModel.updateVolumeDownHold(it) }
                }
            }
        }
        }   // end volumeKeysEnabled BringIntoViewSection

        SectionHeader(stringResource(R.string.section_radar))
        RadarSection()

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
    val pairedSurfaces by viewModel.pairedSurfaces.collectAsStateWithLifecycle()
    val hasWearOs by viewModel.hasWearOsPaired.collectAsStateWithLifecycle()
    val hasHardwareButtons by viewModel.hasHardwareButtonCapableWatch.collectAsStateWithLifecycle()
    val hasGarminPaired by viewModel.hasGarminPaired.collectAsStateWithLifecycle()
    // "Not on Garmin" badge for the Wear-only rows — shown only when a Garmin is
    // ALSO paired (the feature works on the Wear watch, just not the Garmin one).
    // When only a Garmin is paired these rows stay hidden (hasWearOs gate).
    val garminBadge: (@Composable () -> Unit)? = if (hasGarminPaired) {
        { com.eried.eucplanet.ui.theme.PlatformUnsupportedTextBadge("GARMIN") }
    } else null

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // "Device" section: a tinted panel of compact pills, one per paired
        // watch / Edge. Sits above General so the rider sees "what surfaces
        // will receive this" before the actual toggles. Each row holds up
        // to 3 pills; more devices wrap to additional rows.
        SectionHeader(stringResource(R.string.section_watch_device))
        DeviceRegion(pairedSurfaces)

        // General: the two settings every rider touches first (auto-launch
        // and auto-close). Keep-display-on used to live here but moved
        // under Display since it's part of the visual on/off behaviour.
        SectionHeader(stringResource(R.string.section_watch_general))

        // Auto-start is Wear OS-only (CIQ apps can't be launched
        // remotely). Hidden when no Wear OS watch is paired so the toggle
        // doesn't read as universally applicable.
        if (hasWearOs) {
            SwitchSettingWithDesc(
                label = stringResource(R.string.watch_auto_start),
                description = stringResource(R.string.watch_auto_start_desc),
                checked = settings.watchAutoStart,
                onCheckedChange = { viewModel.updateWatchAutoStart(it) },
                onTest = { viewModel.testWatchWake() },
                badge = garminBadge
            )
        }
        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_close_on_exit),
            description = stringResource(R.string.watch_close_on_exit_desc),
            checked = settings.watchCloseOnExit,
            onCheckedChange = { viewModel.updateWatchCloseOnExit(it) }
        )

        // Display: when the watch screen is on / what it primarily shows.
        // Pared down to the two switches that affect "do I see anything
        // useful right now" — Keep-display-on (Wear OS only — Garmin
        // watches manage screen timeout in their own system Settings)
        // and Show-navigation (mirror the turn arrow). Battery icons,
        // PWM rendering, unit labels, dial rotation are all visual
        // tweaks that live in Customization below.
        SectionHeader(stringResource(R.string.section_watch_display))

        if (hasWearOs) {
            SwitchSettingWithDesc(
                label = stringResource(R.string.watch_keep_on),
                description = stringResource(R.string.watch_keep_on_desc),
                checked = settings.watchKeepScreenOn,
                onCheckedChange = { viewModel.updateWatchKeepScreenOn(it) },
                badge = garminBadge
            )
        }
        SwitchSettingWithDesc(
            label = stringResource(R.string.watch_show_navigation),
            description = stringResource(R.string.watch_show_navigation_desc),
            checked = settings.watchShowNavigation,
            onCheckedChange = { viewModel.updateWatchShowNavigation(it) }
        )

        // Advanced (collapsed by default) — visual tweaks most riders
        // configure once and forget. Hiding them keeps the Watch tab
        // tight for first-time setup while still letting power users
        // dial things in. Update-rate sits here because it's a
        // battery-vs-smoothness tradeoff, not a "does my watch work?"
        // gate.
        AdvancedCollapsable(
            title = stringResource(R.string.section_watch_advanced),
            stateKey = "watch-advanced"
        ) {
            // Update rate is Wear-OS-only: the Connect IQ Mobile SDK
            // enforces its own rate cap on the Garmin transport regardless
            // of what we set here, so the choice would be misleading on a
            // Garmin-only setup. Surfaced only when a Wear OS device is paired.
            if (hasWearOs) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.watch_update_rate),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (garminBadge != null) {
                        Spacer(Modifier.width(6.dp))
                        garminBadge()
                    }
                }
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
                            shape = SegmentedButtonDefaults.itemShape(index, updateRateOptions.size),
                            colors = themedSegmentedColors(),
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
                Text(
                    stringResource(R.string.watch_update_rate_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                        shape = SegmentedButtonDefaults.itemShape(index, loadOptions.size),
                        colors = themedSegmentedColors(),
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

            // Dial rotation is Wear-OS-only: the Connect IQ Dc API has no
            // native transform matrix, so a rotated dial would need an
            // offscreen-bitmap-and-rotate path on Garmin that we haven't
            // implemented yet. Surfaced only when a Wear OS device is paired.
            if (hasWearOs) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.watch_dial_rotation),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (garminBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    garminBadge()
                }
            }
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
            }
        }

        // Buttons region — two collapsable sub-cards (collapsible surfaceVariant-card style)
        // grouping the on-screen tap targets and the side hardware keys.
        // Touch is always available (every watch has a touchscreen) so its
        // card always shows; Hardware only appears for surfaces that
        // actually deliver key events (every Garmin, Galaxy Watch Ultra)
        // — gated by [hasHardwareButtons]. Haptic-on-action lives after
        // the two cards because it applies to both kinds of press.
        SectionHeader(stringResource(R.string.section_watch_buttons))

        WatchButtonsCollapsable(
            title = stringResource(R.string.watch_buttons_touch_label),
            info = stringResource(R.string.watch_buttons_touch_info)
        ) {
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
        }

        if (hasHardwareButtons) {
            WatchButtonsCollapsable(
                title = stringResource(R.string.watch_buttons_hardware_label),
                info = stringResource(R.string.watch_buttons_hardware_info)
            ) {
                HardwareButtonGroup(
                    title = stringResource(R.string.watch_hardware_button_1),
                    subtitle = stringResource(R.string.watch_hardware_button_1_subtitle),
                    clickKey = settings.watchStem1Click,
                    holdKey = settings.watchStem1Hold,
                    onClick = { viewModel.updateWatchStem1Click(it) },
                    onHold = { viewModel.updateWatchStem1Hold(it) }
                )
                HardwareButtonGroup(
                    title = stringResource(R.string.watch_hardware_button_2),
                    subtitle = stringResource(R.string.watch_hardware_button_2_subtitle),
                    clickKey = settings.watchStem2Click,
                    holdKey = settings.watchStem2Hold,
                    onClick = { viewModel.updateWatchStem2Click(it) },
                    onHold = { viewModel.updateWatchStem2Hold(it) }
                )
            }
        }

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
    // Watch can bind eyes-free actions only. A synthetic "None" first
    // option lets the rider clear the binding.
    val noneLabel = stringResource(R.string.flic_action_none)
    val keys = remember {
        com.eried.eucplanet.data.model.ActionCatalog.keysFor(
            com.eried.eucplanet.data.model.ActionSurface.WATCH
        )
    }
    val options = buildList {
        add("NONE" to noneLabel)
        keys.forEach { key ->
            val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key) ?: return@forEach
            add(key to stringResource(spec.labelRes))
        }
    }
    SimpleDropdown(
        label = label,
        currentKey = currentKey,
        options = options,
        onSelect = onSelect
    )
}

/**
 * One hardware-button entry in the "Hardware buttons" section: a title +
 * device-specific subtitle (so the rider sees which physical key they're
 * mapping on their actual watch) plus the click and long-press pickers.
 * Groups click+hold under one heading rather than four flat dropdowns so
 * the bindings read as a single button, not as four independent slots.
 */
@Composable
private fun HardwareButtonGroup(
    title: String,
    subtitle: String,
    clickKey: String,
    holdKey: String,
    onClick: (String) -> Unit,
    onHold: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WatchActionPicker(
            label = stringResource(R.string.watch_button_click_label),
            currentKey = clickKey,
            onSelect = onClick
        )
        WatchActionPicker(
            label = stringResource(R.string.watch_button_hold_label),
            currentKey = holdKey,
            onSelect = onHold
        )
    }
}

@Composable
private fun SwitchSettingWithDesc(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTest: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null
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
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    badge()
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = themedSwitchColors())
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
    val eucstatsSyncRunning by viewModel.eucstatsSyncRunning.collectAsState()
    val eucstatsSyncProgress by viewModel.eucstatsSyncProgress.collectAsState()
    val syncConflict by viewModel.syncConflictPrompt.collectAsState()
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showBackupNameDialog by remember { mutableStateOf(false) }
    var backupNameDraft by remember { mutableStateOf("") }
    var overwritePrompt by remember { mutableStateOf<String?>(null) }
    var showRestorePicker by remember { mutableStateOf(false) }
    var showFactoryConfirm by remember { mutableStateOf(false) }

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
            CloudEvent.EucstatsNothingToSync -> context.getString(R.string.online_upload_sync_nothing)
            is CloudEvent.EucstatsSyncFinished -> context.getString(R.string.online_upload_sync_done, event.count)
            CloudEvent.RiderIdConflict -> context.getString(R.string.online_rider_id_conflict)
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
                if (entry.isFactory) showFactoryConfirm = true
                else viewModel.restoreSettingsFrom(entry.fileName)
            }
        )
    }

    if (showFactoryConfirm) {
        AlertDialog(
            onDismissRequest = { showFactoryConfirm = false },
            title = { Text(stringResource(R.string.cloud_factory_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cloud_factory_confirm_body_p1))
                    Text(stringResource(R.string.cloud_factory_confirm_body_p2))
                }
            },
            confirmButton = {
                Button(onClick = {
                    showFactoryConfirm = false
                    viewModel.restoreFactoryDefaults()
                }) { Text(stringResource(R.string.cloud_factory_confirm_action)) }
            },
            dismissButton = {
                Button(onClick = { showFactoryConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.appColors.statusDanger),
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

        // ---- Online stats section ----
        // State for the onboarding dialog, profile dialog, and unlink confirmation
        var showOnboarding  by remember { mutableStateOf(false) }
        var showOnlineProfile by remember { mutableStateOf(false) }
        var showUnlinkConfirm by remember { mutableStateOf(false) }

        // Rider recovery: tapping "Join" runs joinOrRecover(). If a previous
        // profile is found in the linked folder's backup, we prompt to continue
        // as that rider (the dialog below) rather than create a new identity;
        // otherwise joinOrRecover signals onboarding via startOnboarding.
        val restorableRider by viewModel.restorableRider.collectAsStateWithLifecycle()
        val startOnboarding by viewModel.startOnboarding.collectAsStateWithLifecycle()
        val rejoinConfirm by viewModel.rejoinConfirm.collectAsStateWithLifecycle()
        LaunchedEffect(startOnboarding) {
            if (startOnboarding) {
                showOnboarding = true
                viewModel.consumeStartOnboarding()
            }
        }
        // Rejoin confirmation: this phone already has a profile, so pressing Join
        // warns the rider they'll rejoin as that existing rider rather than
        // re-enabling silently.
        if (rejoinConfirm) {
            val rejoinName = settings.eucstatsDisplayName?.takeIf { it.isNotBlank() }
                ?: ("#" + (settings.eucstatsStoreId?.take(8) ?: ""))
            AlertDialog(
                onDismissRequest = { viewModel.dismissRejoinConfirm() },
                title = { Text(stringResource(R.string.online_rejoin_title)) },
                text = { Text(stringResource(R.string.online_rejoin_body, "“$rejoinName”")) },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmRejoin() }) {
                        Text(stringResource(R.string.online_rejoin_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissRejoinConfirm() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        restorableRider?.let { rider ->
            val riderName = rider.displayName?.takeIf { it.isNotBlank() }
                ?: ("#" + rider.storeId.take(8))
            val switching = settings.eucstatsStoreId != null
            AlertDialog(
                onDismissRequest = { viewModel.dismissRestorableRider() },
                title = { Text(stringResource(R.string.online_restore_title)) },
                text = {
                    Text(
                        stringResource(
                            if (switching) R.string.online_restore_body_switch
                            else R.string.online_restore_body_new,
                            riderName,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.restoreRider(rider) }) {
                        Text(stringResource(R.string.online_restore_confirm, riderName))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissRestorableRider() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        if (showOnboarding) {
            OnlineUploadOnboardingDialog(
                onDismiss = { showOnboarding = false },
                onRegistered = {
                    showOnboarding = false
                    viewModel.refreshOnlineUploadCard()
                },
                viewModel = viewModel,
            )
        }

        if (showOnlineProfile) {
            com.eried.eucplanet.ui.settings.eucstats.OnlineProfileDialog(
                onDismiss = {
                    showOnlineProfile = false
                    viewModel.refreshOnlineUploadCard()
                },
                viewModel = viewModel,
            )
        }

        if (showUnlinkConfirm) {
            AlertDialog(
                onDismissRequest = { showUnlinkConfirm = false },
                title = { Text(stringResource(R.string.online_unlink_title)) },
                text = { Text(stringResource(R.string.online_unlink_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.unlinkOnline()
                        showUnlinkConfirm = false
                    }) { Text(stringResource(R.string.online_unlink_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showUnlinkConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // Online leaderboards — only shown once a backup folder is configured.
        if (settings.syncFolderUri != null) {
            SectionHeader(stringResource(R.string.section_online_stats))

            if (!settings.onlineUploadEnabled) {
                val siteUrl = stringResource(R.string.online_upload_site_url)
                // Caption + inline link (one flowing, naturally-wrapping sentence),
                // shown ABOVE the Join button.
                val caption = stringResource(R.string.online_upload_join_caption)
                val siteLabel = stringResource(R.string.online_upload_site_label)
                val linkColor = MaterialTheme.appColors.primary
                val captionText = androidx.compose.ui.text.buildAnnotatedString {
                    append(caption)
                    append(" ")
                    withStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = linkColor,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    ) { append(siteLabel) }
                }
                Text(
                    captionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.appColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(siteUrl)
                            )
                            runCatching { context.startActivity(intent) }
                        }
                        .padding(bottom = 8.dp),
                )
                // Same button component the folder section uses (e.g. "Choose folder").
                LeftAlignedScanButton(
                    label = stringResource(R.string.online_upload_join),
                    onClick = { viewModel.joinOrRecover() },
                )
            }
        }

        // Rider card + actions: shown when online upload is enabled and storeId is known.
        if (settings.syncFolderUri != null && settings.onlineUploadEnabled && settings.eucstatsStoreId != null) {
            LaunchedEffect(Unit) { viewModel.refreshOnlineUploadCard() }
            val riderCard by viewModel.onlineUploadCard.collectAsStateWithLifecycle()
            val cardLoaded by viewModel.onlineUploadCardLoaded.collectAsStateWithLifecycle()
            val cardMissing by viewModel.onlineUploadCardMissing.collectAsStateWithLifecycle()
            val card = riderCard

            // Link to the public site: a short caption with the URL underlined;
            // tapping the line opens eucstats in the browser.
            val siteUrl = stringResource(R.string.online_upload_site_url)
            val linkedCaption = stringResource(R.string.online_upload_linked_caption)
            val linkedLabel = stringResource(R.string.online_upload_site_label)
            val linkColor = MaterialTheme.appColors.primary
            val linkedText = androidx.compose.ui.text.buildAnnotatedString {
                append(linkedCaption)
                append(" ")
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = linkColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )
                ) { append(linkedLabel) }
            }
            // Link to the public site, shown ABOVE the box at full width
            // (not inset by the box padding).
            Text(
                linkedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(siteUrl)
                        )
                        runCatching { context.startActivity(intent) }
                    }
                    .padding(vertical = 4.dp),
            )

            // The stats card uses the exact same flat surface + small shape as
            // the Devices cards (DeviceCard = Surface(appColors.surface)).
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.appColors.surface,
                contentColor = MaterialTheme.appColors.textPrimary,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (card != null) {
                        // Avatar + name/flag row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Avatar: the real photo when available, falling back to
                            // a circular initial while it loads or if it fails.
                            val initial = card.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            com.eried.eucplanet.ui.settings.eucstats.RemoteAvatar(
                                url = card.avatarUrl,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.appColors.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = initial,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.appColors.onPrimary,
                                    )
                                }
                            }
                            // Name and flag
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val nameAndFlag = buildString {
                                    if (!card.displayName.isNullOrBlank()) append(card.displayName)
                                    if (!card.flag.isNullOrBlank()) {
                                        if (isNotEmpty()) append("  ")
                                        // Show the flag emoji (e.g. 🇳🇴) instead of the raw code (NO).
                                        append(flagEmoji(card.flag).ifEmpty { card.flag })
                                    }
                                }
                                if (nameAndFlag.isNotEmpty()) {
                                    Text(
                                        nameAndFlag,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.appColors.textPrimary,
                                    )
                                }
                                if (!card.country.isNullOrBlank()) {
                                    Text(
                                        card.country,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.appColors.textSecondary,
                                    )
                                }
                            }
                        }

                        // Stats — shown in the unit system the app is currently set to
                        // (not both metric + imperial). 1 decimal for small distances.
                        val distUnit = Units.effectiveDistanceUnit(settings)
                        val spdUnit = Units.effectiveSpeedUnit(settings)
                        val totalVal = Units.distance(card.totalKm.toFloat(), distUnit)
                        val totalStr = if (totalVal < 100f) "%.1f".format(totalVal) else "%.0f".format(totalVal)
                        Text(
                            stringResource(R.string.online_upload_total_km, totalStr, Units.distanceUnit(distUnit)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                        if (card.mileageRank != null) {
                            Text(
                                stringResource(R.string.online_upload_mileage_rank, card.mileageRank),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.appColors.textSecondary,
                            )
                        }
                        Text(
                            stringResource(
                                R.string.online_upload_top_speed,
                                "%.0f".format(Units.speed(card.topSpeedKmh.toFloat(), spdUnit)),
                                Units.speedUnit(context, spdUnit)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                        Text(
                            stringResource(R.string.online_upload_trips, card.trips),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                    } else if (cardMissing) {
                        // The rider 404s on the server (dataset reset, deleted, or
                        // possibly banned). We deliberately do NOT offer to
                        // re-register here -- the app can't tell a removed account
                        // from a banned one, and a banned rider must not be able to
                        // re-create themselves. Warn clearly and stop here.
                        Text(
                            stringResource(R.string.online_upload_card_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.appColors.statusDanger,
                        )
                    } else if (cardLoaded) {
                        // Tried and got nothing back (a transient load failure),
                        // so don't spin forever.
                        Text(
                            stringResource(R.string.online_upload_card_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.appColors.primary,
                            )
                            Text(
                                stringResource(R.string.online_upload_card_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.appColors.textSecondary,
                            )
                        }
                    }
                }
            }

            // Buttons live OUTSIDE the stats card, at the section margin.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { showOnlineProfile = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.online_profile_manage))
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            // "Trip stats uploads" subsection — a title plus a one-line
            // description, matching the title + caption pattern used by the
            // other subsections in this group (e.g. Trips backup).
            Text(
                stringResource(R.string.online_upload_actions_caption),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.appColors.textPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.online_upload_actions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { viewModel.syncEucstatsNow() },
                    enabled = !eucstatsSyncRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cloud_retry_now))
                }
                Button(
                    onClick = { showUnlinkConfirm = true },
                    enabled = !eucstatsSyncRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.appColors.statusDanger,
                        contentColor   = MaterialTheme.appColors.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.online_upload_unlink))
                }
            }
            // Determinate progress while syncing — mirrors the trips-backup
            // "Sync all" (indeterminate "checking…" first, then done/total).
            if (eucstatsSyncRunning) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    val p = eucstatsSyncProgress
                    if (p != null) {
                        val (done, total) = p
                        LinearProgressIndicator(
                            progress = { if (total > 0) done.toFloat() / total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.online_upload_sync_progress, done, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.online_upload_sync_checking),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.appColors.textSecondary,
                        )
                    }
                }
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
            modifier = Modifier.weight(0.55f), colors = themedSwitchColors())
        Switch(checked = triggerChecked, onCheckedChange = onTriggerChange,
            modifier = Modifier.weight(0.55f), colors = themedSwitchColors())
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = themedSwitchColors())
    }
}

@Composable
internal fun SectionHeader(title: String) {
    val query = LocalSettingsSearchQuery.current
    Text(
        text = highlightMatches(title, query),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.appColors.sectionHeader
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
            // Defensive: if upstream produces an inverted range (e.g. a stale
            // settings value lower than the slider's minimum), collapse to a
            // single-point range so the Slider doesn't crash on coerceIn.
            val safeRange = if (range.endInclusive < range.start) range.start..range.start else range
            val computedSteps = steps
                ?: ((safeRange.endInclusive - safeRange.start) - 1).toInt().coerceAtLeast(0)
            Slider(
                value = value.coerceIn(safeRange),
                onValueChange = onValueChange,
                valueRange = safeRange,
                steps = computedSteps,
                enabled = enabled,
                colors = themedSliderColors()
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    badge: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(highlightMatches(label, LocalSettingsSearchQuery.current), style = MaterialTheme.typography.bodyLarge)
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                badge()
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = themedSwitchColors())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GaugeThresholdSlider(
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
    val orangeColor = MaterialTheme.appColors.gaugeWarn
    val redColor = MaterialTheme.appColors.gaugeDanger

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
private fun ThemeDropdown(
    label: String,
    current: String,
    builtIns: List<String>,
    saved: List<String>,
    unsaved: List<String>,
    onSelect: (String) -> Unit,
    onSelectUnsaved: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        // Custom themes carry a .json suffix internally (so "Light" the
        // built-in and a user-saved "Light" never collide on lookup). Strip
        // it for display only -- the underlying name passed to onSelect
        // keeps the suffix so resolution still picks the right file.
        fun displayName(n: String): String =
            if (n.endsWith(".json", ignoreCase = true)) n.dropLast(5) else n
        OutlinedTextField(
            value = displayName(current),
            onValueChange = {},
            readOnly = true,
            label = { Text(highlightMatches(label, LocalSettingsSearchQuery.current)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.appColors.menuBackground) {
            builtIns.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); expanded = false }
                )
            }
            // Saved custom themes + unsaved drafts live below the 3 built-ins,
            // behind a divider. Drafts are shown as "<name> (unsaved)".
            if (saved.isNotEmpty() || unsaved.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.appColors.divider)
            }
            saved.forEach { name ->
                DropdownMenuItem(
                    text = { Text(displayName(name)) },
                    onClick = { onSelect(name); expanded = false }
                )
            }
            unsaved.forEach { base ->
                DropdownMenuItem(
                    text = { Text("$base (unsaved)") },
                    onClick = { onSelectUnsaved(base); expanded = false }
                )
            }
        }
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.appColors.menuBackground) {
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
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.appColors.primary),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    onTitleChange(editText)
                                    editing = false
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                                }
                            },
                            colors = themedFieldColors(),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { editing = true }
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.appColors.primary
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
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.flic_forget), tint = MaterialTheme.appColors.statusDanger)
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

        // Gearbox only makes sense for engines that actually shift. Profiles
        // flagged gearless (2-stroke singles, CVT ATVs, electric/jet sims)
        // have EngineSoundEngine ignore the gearbox setting anyway, so
        // hiding it matches what already happens for supportsMuffler /
        // supportsPops / supportsBrakeWhine — keeps the panel honest about
        // which controls actually do something for the selected engine.
        if (!currentProfile.gearless) {
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
        }

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
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                colors = themedFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = MaterialTheme.appColors.menuBackground
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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
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
private fun AnnounceWhenSelector(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        "ALWAYS" to stringResource(R.string.voice_announce_always),
        "CONNECTED" to stringResource(R.string.voice_announce_connected),
        "RIDING" to stringResource(R.string.voice_announce_riding)
    )
    SegmentedChoice(
        label = stringResource(R.string.voice_announce_when_label),
        options = options,
        current = current,
        onChange = onChange
    )
}

@Composable
private fun AutoRecordModeSelector(
    current: String,
    onChange: (String) -> Unit
) {
    val options = listOf(
        "NEVER" to stringResource(R.string.auto_record_mode_never),
        "CONNECTED" to stringResource(R.string.auto_record_mode_connected),
        "RIDING" to stringResource(R.string.auto_record_mode_riding)
    )
    SegmentedChoice(
        label = stringResource(R.string.auto_record_mode_label),
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
                style = MaterialTheme.typography.bodyLarge
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
                    colors = themedSegmentedColors(),
                ) {
                    Text(
                        optLabel,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
    val volumeKeys = remember {
        com.eried.eucplanet.data.model.ActionCatalog.keysFor(
            com.eried.eucplanet.data.model.ActionSurface.VOLUME_KEY
        )
    }
    val noneLabel = stringResource(R.string.flic_action_none)
    val currentLabel = when {
        currentValue.isEmpty() || currentValue == "NONE" -> noneLabel
        else -> com.eried.eucplanet.data.model.ActionCatalog.byKey(currentValue)
            ?.labelRes?.let { stringResource(it) } ?: noneLabel
    }

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
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = themedFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    onValueChange("NONE")
                    expanded = false
                }
            )
            volumeKeys.forEach { key ->
                val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key) ?: return@forEach
                DropdownMenuItem(
                    text = { Text(stringResource(spec.labelRes)) },
                    onClick = {
                        onValueChange(key)
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
    val lineColor = MaterialTheme.appColors.metricVoltage
    val pointColor = MaterialTheme.appColors.tertiary
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
                    .focusRequester(focusRequester),
                colors = themedFieldColors(),
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
        // "(factory)" is a synthetic, always-available entry: it resets to
        // built-in defaults instead of reading a file. It sits right under
        // "(default)", with a divider separating it from the rider's named
        // backups below. Never pre-selected when real backups exist, so a
        // stray Restore tap can't wipe settings.
        val factory = com.eried.eucplanet.data.sync.BackupEntry(
            fileName = "", label = null, isFactory = true
        )
        val def = loaded.filter { it.label == null }
        val named = loaded.filter { it.label != null }
        entries = def + factory + named
        selected = loaded.firstOrNull() ?: factory
    }
    val defaultLabel = stringResource(R.string.cloud_restore_picker_default)
    val factoryLabel = stringResource(R.string.cloud_restore_picker_factory)
    fun entryLabel(e: com.eried.eucplanet.data.sync.BackupEntry): String =
        if (e.isFactory) factoryLabel else e.label ?: defaultLabel
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
                        value = selected?.let { entryLabel(it) } ?: defaultLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        colors = themedFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        list.forEachIndexed { index, entry ->
                            DropdownMenuItem(
                                text = { Text(entryLabel(entry)) },
                                onClick = {
                                    selected = entry
                                    dropdownOpen = false
                                }
                            )
                            // Divider after "(factory)" splits the built-in
                            // resets from the rider's named backups below.
                            if (entry.isFactory && index < list.lastIndex) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = MaterialTheme.appColors.divider
                                )
                            }
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
 * "Device" region: a tinted panel containing one detailed card per
 * paired surface. Each card shows the device name + kind, an Active/Idle
 * status chip, and the surface's effective update rate (Wear OS = the
 * bridge's publish interval, Garmin = the SDK-rate-capped delivery rate
 * from [GarminBridge.deliveryRateHz]). Cards lay out 2 per row on a
 * standard phone width; single device gets a full-width card; an empty
 * state shows the hint-only pill.
 *
 * Garmin limitations text renders below the panel when any Garmin card
 * is present — it's surface-level info, not per-device.
 */
/**
 * "Device" region: a tinted panel containing one compact card per paired
 * surface, stacked vertically (1 per row, full-width). Each card shows
 * the device name + kind subtitle on the left, and live/idle + update
 * rate badges on the right. Sits above General so the rider sees "what
 * receives this" before they touch any toggles.
 *
 * Garmin limitations text renders below the panel when any Garmin card
 * is present.
 */
@Composable
private fun DeviceRegion(
    surfaces: List<com.eried.eucplanet.data.model.PairedSurface>
) {
    // No outer Surface — cards extend to the same horizontal margin as
    // the surrounding text, so the "Device" section feels integrated
    // rather than boxed-in. Cards stack vertically with a small gap;
    // each card has its own tinted background.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (surfaces.isEmpty()) {
            EmptyDevicePill()
        } else {
            surfaces.forEach { surface -> DeviceCard(surface) }
        }
    }
}

/**
 * Card wrapper for the Touch / Hardware sub-sections of the Watch Buttons
 * region. Same compact collapsible-card pattern (surfaceVariant card +
 * titleSmall + chevron) so the sub-cards read as siblings of the Garmin
 * limits card and the Customization card elsewhere in the tab. When
 * expanded, leads with a MetricInfoBox carrying [info] above the
 * [content] (the actual pickers).
 */
/**
 * Generic "Advanced" collapsable card. Same compact collapsible-card pattern
 * as [WatchButtonsCollapsable] but without a leading info box — used by
 * tabs that group power-user controls behind a single tap (Watch, Voice,
 * …). Collapsed by default; the [title] is the only thing visible until
 * the rider expands. Includes smart-scroll-into-view on expand so the
 * freshly-revealed controls aren't off-screen below the fold.
 *
 * [stateKey] separates rememberSaveable buckets when multiple Advanced
 * cards live in the same tab.
 */
@Composable
private fun AdvancedCollapsable(
    title: String,
    stateKey: String = title,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(120)
            runCatching { requester.bringIntoView() }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.appColors.textPrimary
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
                    text = highlightMatches(title, LocalSettingsSearchQuery.current),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun WatchButtonsCollapsable(
    title: String,
    info: String,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    // Same smart-scroll behaviour as BringIntoViewSection / the top-level
    // CollapsibleSection: when the rider opens the card, after a short
    // settle delay scroll the card into view so the freshly-revealed
    // controls aren't off-screen below the fold.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            kotlinx.coroutines.delay(120)
            runCatching { requester.bringIntoView() }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.appColors.textPrimary
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
                    title,
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricInfoBox(info)
                    content()
                }
            }
        }
    }
}

/**
 * One device card, 1-per-row full-width. Left column: kind icon + device
 * name (title) + kind label (subtitle). Right column: compact status and
 * update-rate badges. Active cards tint with primaryContainer so the
 * rider can spot at a glance which wrist is currently receiving frames.
 */
@Composable
private fun DeviceCard(
    surface: com.eried.eucplanet.data.model.PairedSurface
) {
    // No active/idle background tint — the StatusBadge (live dot + label) already
    // conveys connection state, so every device card uses one neutral surface.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.appColors.surface,
        contentColor = MaterialTheme.appColors.textPrimary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (surface.kind) {
                    com.eried.eucplanet.data.model.PairedSurface.Kind.WEAR_OS -> Icons.Outlined.WatchOutlined
                    com.eried.eucplanet.data.model.PairedSurface.Kind.GARMIN -> Icons.Default.Watch
                },
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    surface.name.ifBlank { surfaceKindLabel(surface.kind) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    surfaceKindLabel(surface.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusBadge(active = surface.active)
            RateBadge(hz = surface.updateRateHz)
        }
    }
}

/** Compact live/idle indicator: filled dot (green when live, dim grey
 *  when idle) + status text. Reads as a single visual unit. */
@Composable
private fun StatusBadge(active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.FiberManualRecord,
            contentDescription = null,
            tint = if (active) com.eried.eucplanet.ui.theme.LocalAppColors.current.connectionActive
                else LocalContentColor.current.copy(alpha = 0.5f),
            modifier = Modifier.size(10.dp)
        )
        Text(
            stringResource(
                if (active) R.string.watch_paired_active
                else R.string.watch_paired_idle
            ),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Compact update-rate indicator: heart-monitor icon + Hz value. The
 *  pulse-on-monitor glyph reads as "live vital signs" — semantically
 *  matches an end-to-end heartbeat better than the earlier signal-bars,
 *  and is clearly a status indicator rather than a tappable button. */
@Composable
private fun RateBadge(hz: Double) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.MonitorHeart,
            contentDescription = null,
            modifier = Modifier.size(14.dp)
        )
        Text(
            stringResource(R.string.watch_paired_rate_hz, hz),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Empty-state pill, full-width, when no Wear OS / Garmin is paired.
 * Reads as a placeholder rather than a real device so the rider knows
 * to open the companion app on the watch.
 */
@Composable
private fun EmptyDevicePill() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.WatchOutlined,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.watch_paired_none),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.watch_paired_none_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun surfaceKindLabel(kind: com.eried.eucplanet.data.model.PairedSurface.Kind): String =
    when (kind) {
        com.eried.eucplanet.data.model.PairedSurface.Kind.WEAR_OS ->
            stringResource(R.string.watch_paired_kind_wear)
        com.eried.eucplanet.data.model.PairedSurface.Kind.GARMIN ->
            stringResource(R.string.watch_paired_kind_garmin)
    }
@Composable
private fun HudInstallHint(pairedHudVersion: String?, hudEverConnected: Boolean) {
    // Hide the hint as soon as a HUD has paired -- AND keep it hidden
    // for the rest of the session even if the WebSocket drops. Without
    // the sticky flag, the hint flashed back on every reconnect and
    // told a rider who has the HUD installed to "Get it at ...", which
    // reads as broken UX.
    if (pairedHudVersion != null || hudEverConnected) return

    val updateUrl = stringResource(R.string.hud_update_url)
    val raw = stringResource(R.string.hud_install_hint)
        .replace("<b>", "").replace("</b>", "")
    val linkColor = MaterialTheme.colorScheme.primary
    // Mirror the welcome tutorial's link handling (OutroLinkBullet): locate the
    // URL run by text — not via HtmlCompat bold-span detection, which wasn't
    // reliably tagging the run, so no clickable link was ever added — and attach
    // a LinkAnnotation.Url. The https:// prefix is required or ACTION_VIEW has no
    // scheme to launch.
    val annotated = remember(raw, updateUrl, linkColor) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(raw)
            val i = raw.indexOf(updateUrl)
            if (i >= 0) {
                addLink(
                    androidx.compose.ui.text.LinkAnnotation.Url(
                        url = "https://$updateUrl",
                        styles = androidx.compose.ui.text.TextLinkStyles(
                            style = androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                    ),
                    start = i,
                    end = i + updateUrl.length
                )
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
        val hudEverConnected by viewModel.hudEverConnected.collectAsState()
        HudInstallHint(
            pairedHudVersion = pairedHudVersion,
            hudEverConnected = hudEverConnected
        )

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
                modifier = Modifier.weight(2f),
                colors = themedFieldColors(),
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
                modifier = Modifier.weight(1f),
                colors = themedFieldColors(),
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

        // Three top-level collapsibles under the Integration card.
        // HUD screens first because the reorder list inside is the
        // primary repeat-visit destination; Map options second;
        // Joystick actions last (set-once bindings).
        HudScreensCard(settings = settings, viewModel = viewModel)
        HudMapOptionsCard(settings = settings, viewModel = viewModel)
        HudJoystickCard(settings = settings, viewModel = viewModel)
    }
}

/** HUD joystick long-press bindings: one action picker per direction.
 *  Reuses the same [ActionDropdown] (VOLUME_KEY action vocabulary) as the
 *  Volume keys section so the rider sees a consistent eyes-free action set;
 *  each picker persists through its own ViewModel setter. */
@Composable
private fun HudJoystickCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(R.string.hud_joystick_title),
        stateKey = "hud_joystick"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HintText(stringResource(R.string.hud_joystick_desc), small = true)
            ActionDropdown(stringResource(R.string.hud_joystick_up), settings.hudActionUp) {
                viewModel.updateHudActionUp(it)
            }
            ActionDropdown(stringResource(R.string.hud_joystick_down), settings.hudActionDown) {
                viewModel.updateHudActionDown(it)
            }
            ActionDropdown(stringResource(R.string.hud_joystick_left), settings.hudActionLeft) {
                viewModel.updateHudActionLeft(it)
            }
            ActionDropdown(stringResource(R.string.hud_joystick_right), settings.hudActionRight) {
                viewModel.updateHudActionRight(it)
            }
        }
    }
}

/** Map appearance: tile style + contrast + brightness sliders. Uses the
 *  shared AdvancedCollapsable for chrome + auto-scroll-on-expand. */
@Composable
private fun HudMapOptionsCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(R.string.hud_map_options_title),
        stateKey = "hud_map_options"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudMapStylePicker(settings = settings, viewModel = viewModel)
            HudMapContrastSlider(settings = settings, viewModel = viewModel)
            HudMapBrightnessSlider(settings = settings, viewModel = viewModel)
        }
    }
}

/** Carousel personalization: which screens appear + custom overlay choice.
 *  Uses the shared AdvancedCollapsable for chrome + auto-scroll-on-expand. */
@Composable
private fun HudScreensCard(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    AdvancedCollapsable(
        title = stringResource(R.string.hud_screens_title),
        stateKey = "hud_screens"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HudScreenList(settings = settings, viewModel = viewModel)
            HudOverlayPicker(settings = settings, viewModel = viewModel)
        }
    }
}

@Composable
private fun HudMapContrastSlider(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hud_map_contrast_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${settings.hudMapContrastPct}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Slider(
            value = settings.hudMapContrastPct.toFloat(),
            onValueChange = { viewModel.updateHudMapContrast(it.toInt()) },
            valueRange = 50f..200f,
            steps = 29, // 5% increments across the 150-wide range
            colors = themedSliderColors()
        )
    }
}

@Composable
private fun HudMapBrightnessSlider(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hud_map_brightness_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            // Signed display so the rider sees +20 / -20 rather than just
            // "20" -- it's a +/- offset, not a magnitude.
            val v = settings.hudMapBrightnessPct
            Text(
                if (v > 0) "+$v" else v.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Slider(
            value = settings.hudMapBrightnessPct.toFloat(),
            onValueChange = { viewModel.updateHudMapBrightness(it.toInt()) },
            valueRange = -100f..100f,
            steps = 39, // 5-unit increments across the 200-wide range
            colors = themedSliderColors()
        )
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
                    enabled = wantCheckedCanFlip(checked, canUncheck),
                    colors = themedSwitchColors()
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
    "MapNav" -> ctx.getString(R.string.hud_screen_name_map_nav)
    "Map" -> ctx.getString(R.string.hud_screen_name_map)
    "Nav" -> ctx.getString(R.string.hud_screen_name_nav)
    "Power" -> ctx.getString(R.string.hud_screen_name_power)
    "TripStats" -> ctx.getString(R.string.hud_screen_name_trip_stats)
    "Compass" -> ctx.getString(R.string.hud_screen_name_compass)
    "Safety" -> ctx.getString(R.string.hud_screen_name_safety)
    "BigClock" -> ctx.getString(R.string.hud_screen_name_big_clock)
    else -> id
}

/**
 * Map-tile style picker. The chosen code goes into AppSettings.hudMapStyle,
 * ships over the wire on the next 5 Hz frame, and the HUD's HudTileCache
 * swaps URL templates + clears its bitmap LRU so the change is visible
 * within a few seconds.
 *
 * Five Carto raster styles cover the readability range riders care about:
 * Voyager (default, neutral parchment), Dark + Dark-no-labels (night riding
 * / OLED-style cockpits), Light (high contrast on bright prisms), and
 * Positron (low-key low-saturation). All five are free with CDN attribution
 * (handled in the HUD's MapScreen footer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudMapStylePicker(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel
) {
    // Carto raster basemap slugs, all 10 publicly served styles. Labels
    // are the raw slugs on purpose: the rider asked to see the internal
    // names, not localised friendly text. Order: voyager family,
    // positron (light_*) family, dark matter (dark_*) family.
    val options = listOf(
        "voyager",
        "voyager_nolabels",
        "voyager_labels_under",
        "voyager_only_labels",
        "light_all",
        "light_nolabels",
        "light_only_labels",
        "dark_all",
        "dark_nolabels",
        "dark_only_labels",
    )
    val currentCode = settings.hudMapStyle.ifBlank { "voyager" }
    val currentLabel = currentCode
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.hud_map_style_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
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
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        viewModel.updateHudMapStyle(code)
                        expanded = false
                    }
                )
            }
        }
    }
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
                onClick = { sheetOpen = true },
                colors = themedTonalButtonColors(),
            ) {
                Text(stringResource(R.string.hud_overlay_select_preset))
            }
            if (settings.hudCustomOverlayName.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.pickHudOverlay("") },
                    colors = themedTextButtonColors()
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

