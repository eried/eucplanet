package com.eried.eucplanet.ui.dashboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.core.content.FileProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.model.SparklineStyle
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.ui.navigator.NavigationOverlayViewModel
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.remap
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import com.eried.eucplanet.ui.theme.themedFieldColors

/**
 * Opens the system gallery to the videos or photos collection. Overlay Studio
 * saves into Movies/EUC Planet and Pictures/EUC Planet; Android has no reliable
 * "open exactly this folder" intent, so this filters the gallery by media type.
 */
/** How long the wheel must have been at standstill before charging-rising-edge
 *  is allowed to trigger the Battery monitor auto-open. Short enough that a
 *  rider who just stopped and plugged in still gets the auto-open within a
 *  few seconds, long enough that regen-while-rocking and balance corrections
 *  can't slip through. */
private const val AUTO_OPEN_STILL_MS = 3000L

private fun openMediaGallery(context: Context, video: Boolean, onNoGalleryApp: () -> Unit) {
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(collection, if (video) "video/*" else "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }.onFailure { onNoGalleryApp() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToSettings: (Int?) -> Unit,
    onNavigateToRecording: () -> Unit,
    onNavigateToStudio: () -> Unit = {},
    onNavigateToNavigator: () -> Unit = {},
    onNavigateToFlic: () -> Unit = {},
    onNavigateToTripDetail: (Long) -> Unit = {},
    onNavigateToMetric: (String) -> Unit = {},
    onNavigateToCharging: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    DisposableEffect(Unit) {
        Log.i("EucDash", "DashboardScreen ENTER")
        onDispose { Log.i("EucDash", "DashboardScreen DISPOSE") }
    }
    val wheelData by viewModel.wheelData.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    // Quake-style console cheat (set via Settings search-bar). Multiplies ONLY the
    // displayed speed on the gauge, the underlying wheelData.speed stays accurate
    // for recording, alarms, voice announcements, motor sound, etc.
    val cheatSpeedMult by viewModel.cheatState.speedDisplayMultiplier.collectAsState()
    SideEffect { Log.d("EucDash", "recompose conn=$connectionState speed=${wheelData.speed}") }
    val safetyActive by viewModel.safetySpeedActive.collectAsState()
    val locked by viewModel.locked.collectAsState()
    val lockBusy by viewModel.lockBusy.collectAsState()
    val lightBusy by viewModel.lightBusy.collectAsState()
    val recording by viewModel.recording.collectAsState()
    val gpsExtra by viewModel.gpsExtraSpeed.collectAsState()
    val externalGpsSpeed = gpsExtra?.first
    val externalGpsAccent = when (gpsExtra?.second) {
        "EXTERNAL" -> MaterialTheme.appColors.metricPosition
        "PHONE" -> MaterialTheme.appColors.metricVoltage
        else -> MaterialTheme.appColors.metricPosition
    }
    val externalGpsPaired by viewModel.externalGpsPaired.collectAsState()
    val tripCount by viewModel.tripCount.collectAsState()
    val tiltbackSpeed by viewModel.tiltbackSpeed.collectAsState()
    val safetyTiltbackSpeed by viewModel.safetyTiltbackSpeed.collectAsState()
    val realHistory by viewModel.history.collectAsState()
    // No fake disconnected demo. The dashboard shows real samples only —
    // sparklines remain empty until the wheel sends at least 2 frames,
    // matching the per-catalog metric behavior we adopted in Phase 2.
    val history = realHistory
    val modelName by viewModel.modelName.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val connectedBrand by viewModel.connectedBrand.collectAsState()
    val wheelNameDisplay by viewModel.wheelNameDisplay.collectAsState()
    val firmwareVersion by viewModel.firmwareVersion.collectAsState()
    // Model name with the brand word dropped from the front ("InMotion V14
    // 50GB" -> "V14 50GB"). Brand tokens baked into the model id (e.g. KS-S22)
    // aren't a leading "<brand> " word, so they're left intact.
    val modelNameShort = modelName?.let { m ->
        val stripped = connectedBrand?.let { b -> m.removePrefix("$b ") } ?: m
        // If the name was only the brand word, stripping leaves nothing , 
        // fall back to the original so the bar never goes blank.
        stripped.ifBlank { m }
    }
    val speedUnit by viewModel.speedUnit.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val tempUnit by viewModel.tempUnit.collectAsState()
    val accentKey by viewModel.accentKey.collectAsState()
    val showGaugeColorBand by viewModel.showGaugeColorBand.collectAsState()
    val gaugeOrangePct by viewModel.gaugeOrangePct.collectAsState()
    val gaugeRedPct by viewModel.gaugeRedPct.collectAsState()
    val currentMode by viewModel.currentDisplayMode.collectAsState()

    // Auto-open the Battery monitor when charging starts (rising edge), if
    // enabled. Standstill debounce: only allow the auto-open when the wheel
    // has been stationary for at least AUTO_OPEN_STILL_MS. Charging while
    // moving (or just-stopped) is almost certainly a false positive — regen
    // while rocking the wheel, balance corrections on a parked wheel, a
    // momentary current dip the inference layer latched on, etc. Without
    // this the rider could be coasting down the street and have the Battery
    // monitor steal the dashboard, which is both wrong and unsafe.
    val chargeStatusForAutoOpen by viewModel.chargeStatus.collectAsState()
    val chargingAutoOpen by viewModel.chargingAutoOpen.collectAsState()
    var lastChargeStatus by remember { mutableStateOf(chargeStatusForAutoOpen) }
    var lastNonZeroSpeedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wheelData.speed) {
        if (kotlin.math.abs(wheelData.speed) >= 0.5f) {
            lastNonZeroSpeedAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(chargeStatusForAutoOpen, chargingAutoOpen) {
        val started = chargeStatusForAutoOpen == com.eried.eucplanet.data.model.ChargeStatus.Charging &&
            lastChargeStatus != com.eried.eucplanet.data.model.ChargeStatus.Charging
        lastChargeStatus = chargeStatusForAutoOpen
        val stillForLongEnough =
            System.currentTimeMillis() - lastNonZeroSpeedAt >= AUTO_OPEN_STILL_MS
        if (started && chargingAutoOpen && stillForLongEnough) onNavigateToCharging()
    }
    // Customizable dashboard layout — falls back to the catalog defaults
    // (BATTERY, TEMPERATURE, VOLTAGE, CURRENT, LOAD, TRIP) when the
    // saved order is blank or has fewer than 6 entries.
    val dashboardMetricOrderRaw by viewModel.dashboardMetricOrder.collectAsState()
    val dashboardMetricStatsJson by viewModel.dashboardMetricStats.collectAsState()
    val dashboardMetricsColumnsSetting by viewModel.dashboardMetricsColumns.collectAsState()
    val dashboardCompositesJson by viewModel.dashboardCompositeMetrics.collectAsState()
    val dashboardCustomTilesJson by viewModel.dashboardCustomTiles.collectAsState()
    val dashboardActionOrderRaw by viewModel.dashboardActionOrder.collectAsState()
    val dashboardActionGroupsJson by viewModel.dashboardActionGroups.collectAsState()
    val dashboardCustomBleJson by viewModel.dashboardCustomBle.collectAsState()
    // Phone-battery and GPS feeds for the catalog metrics that aren't
    // sourced from WheelData. Both update lazily; the value pipeline
    // just reads the latest StateFlow snapshot on each recomposition.
    val phoneBatteryPct by viewModel.phoneBatteryPercent.collectAsState()
    val gpsLocation by viewModel.currentLocation.collectAsState()
    val hasFlic by viewModel.hasFlicConfigured.collectAsState()
    val flicShowOnDashboard by viewModel.flicShowOnDashboard.collectAsState()
    val wheelMaxSpeedCap by viewModel.wheelMaxSpeedCap.collectAsState()
    // Navigation state, drives the dashboard's navigator button (the singleton
    // engine behind this VM is shared with the floating navigation overlay).
    val navOverlayVm: NavigationOverlayViewModel = hiltViewModel()
    val navState by navOverlayVm.navState.collectAsState()
    val currentRoute by navOverlayVm.currentRoute.collectAsState()
    val flicFlashAt by viewModel.flicFlashAt.collectAsState()
    val latestTripId by viewModel.latestTripId.collectAsState()
    val currentTripId by viewModel.currentTripId.collectAsState()
    val gpsFix by viewModel.gpsFix.collectAsState()
    val locationGranted by viewModel.locationPermissionGranted.collectAsState()
    var showQuitDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showNoTripsDialog by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    // Lifted from the bottom-info-row block so OPEN_ABOUT (when bound
    // to an action grid slot) can open the same dialog the version-text
    // tap opens.
    var showAboutDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    // The service-mode overlay floats outside the dashboard, so it can't flip
    // the local dialog state above directly. Instead it posts a request to
    // DashboardDialogBus and navigates here; we honor it and clear the bus.
    val dialogRequest by DashboardDialogBus.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(dialogRequest) {
        when (dialogRequest) {
            "about" -> { showAboutDialog = true; DashboardDialogBus.consume() }
            "service" -> { showDiagnosticsDialog = true; DashboardDialogBus.consume() }
        }
    }
    // Holds the CustomTile whose SHOW_QR action was just tapped on the
    // live dashboard. Null when the QR popup is dismissed.
    var showQrForTile by remember { mutableStateOf<com.eried.eucplanet.ui.settings.CustomTile?>(null) }
    // Text-display dialog for NONE-action custom tiles that the rider gave
    // a label/note. Mirrors showQrForTile's lifecycle so the rider can tap
    // a text tile to read the full note in a scrollable box rather than
    // squinting at the truncated tile face.
    var showTextForTile by remember { mutableStateOf<com.eried.eucplanet.ui.settings.CustomTile?>(null) }
    var showDiagnosticsConfirm by remember { mutableStateOf(false) }
    var showMapMenu by remember { mutableStateOf(false) }
    var showStudioMenu by remember { mutableStateOf(false) }
    var showGpsMenu by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var showWarningsDialog by remember { mutableStateOf(false) }
    val hasSyncFolder by viewModel.hasSyncFolder.collectAsState()
    val activity = LocalContext.current as? Activity
    val toastContext = LocalContext.current
    // Unified Material3 snackbar host, replaces the older Toast popups so the
    // dashboard styles match Overlay Studio / Navigator / Settings (no system
    // icon, swipe-to-dismiss).
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.cloudToasts.collect { resId ->
            snackbarScope.launch { snackbar.showSnackbar(toastContext.getString(resId)) }
        }
    }

    val backAction by viewModel.backButtonAction.collectAsState()

    // Reliable "Stop all" exit. Three things compound to make
    // `viewModel.stopEverything(); activity?.finish()` flaky in practice:
    //  (a) `activity?.finish()` no-ops when LocalContext.current isn't an
    //      Activity at click time (it can be a ContextWrapper across some
    //      recompositions, particularly under config changes). The rider
    //      then sees "back triggered the dialog but Stop all did nothing".
    //  (b) `finish()` alone leaves the task entry in recents, so the user
    //      sees the app card still there and assumes the exit didn't take.
    //  (c) `showQuitDialog` is never set false on the confirm path, so if
    //      finish() loses the race, the dialog state lives on and reopens.
    // Centralising the exit flow here means both the direct STOP_ALL back
    // action and the AlertDialog confirm button go through the same proven
    // shutdown sequence.
    val performStopAllAndExit: () -> Unit = {
        showQuitDialog = false
        // The service now SIGKILLs the process from inside its own
        // onDestroy when ACTION_STOP_ALL_AND_KILL is delivered. That
        // happens at the natural end of cleanup, no arbitrary timer
        // here -- the activity just signals + finishes its task.
        viewModel.stopEverything()
        val act = activity ?: (toastContext as? Activity)
        if (act != null) {
            act.finishAndRemoveTask()
        } else {
            Log.w("Dashboard", "Stop all: no Activity reference, exit may be incomplete")
        }
    }

    BackHandler {
        when (backAction) {
            "BACKGROUND" -> activity?.moveTaskToBack(true)
            "STOP_ALL" -> performStopAllAndExit()
            else -> showQuitDialog = true   // "ASK" (default) or anything we don't recognise
        }
    }

    if (showNoTripsDialog) {
        AlertDialog(
            onDismissRequest = { showNoTripsDialog = false },
            title = { Text(stringResource(R.string.no_trips_title)) },
            text = { Text(stringResource(R.string.no_trips_body)) },
            confirmButton = {
                TextButton(onClick = { showNoTripsDialog = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNoTripsDialog = false
                    onNavigateToRecording()
                }) {
                    Text(stringResource(R.string.no_trips_action_recorder))
                }
            }
        )
    }

    if (showWarningsDialog) {
        val warnings by viewModel.warnings.collectAsState()
        // Auto-dismiss when the rider has fixed every issue (e.g. they
        // granted the missing permission via Settings and came back).
        if (warnings.isEmpty()) {
            LaunchedEffect(Unit) { showWarningsDialog = false }
        }
        AlertDialog(
            onDismissRequest = { showWarningsDialog = false },
            // usePlatformDefaultWidth = false breaks Material3's default
            // ~280–560 dp cap so the dialog can stretch closer to the screen
            // edges — gives each warning card a useful body-text width and
            // keeps the inline Fix button from getting squeezed.
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            ),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp),
            title = { Text(stringResource(R.string.warnings_dialog_title)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    warnings.forEach { w ->
                        androidx.compose.material3.Card(
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            // Row layout — title/body in a weighted column on
                            // the left, Fix button hugging the right edge so
                            // the rider sees the call-to-action without
                            // scanning down past the body text.
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                androidx.compose.foundation.layout.Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        stringResource(w.titleRes),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        stringResource(w.bodyRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Primary filled Button — solid accent colour
                                // so the call-to-action is unmissable on
                                // every warning card. Close stays a neutral
                                // TextButton, matching Material guidance
                                // (one emphasised action per dialog, the
                                // dismissive button is muted).
                                Button(onClick = w.fix) {
                                    Text(stringResource(R.string.warnings_fix_button))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWarningsDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text(stringResource(R.string.cloud_restore_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.cloud_restore_confirm_body_p1))
                    Text(stringResource(R.string.cloud_restore_confirm_body_p2))
                }
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirmDialog = false
                    viewModel.restoreSettingsNow()
                }) { Text(stringResource(R.string.action_restore)) }
            },
            dismissButton = {
                Button(onClick = { showRestoreConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDisconnectDialog) {
        val wheelLabel = modelNameShort ?: connectedDeviceName
            ?: stringResource(R.string.wheel_generic)
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text(stringResource(R.string.disconnect)) },
            text = { Text(stringResource(R.string.disconnect_from, wheelLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                }) {
                    Text(stringResource(R.string.disconnect), color = MaterialTheme.appColors.statusDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = performStopAllAndExit) {
                    Text(stringResource(R.string.exit_stop_all), color = MaterialTheme.appColors.statusDanger)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showQuitDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = {
                        // "Background" should send the task to the back so
                        // the service + notification stay alive. The previous
                        // activity?.finish() killed the activity AND removed
                        // the task, which is silently a Stop-all-lite. The
                        // BackHandler's BACKGROUND branch already does the
                        // right moveTaskToBack(true); this button just
                        // matches it.
                        showQuitDialog = false
                        activity?.moveTaskToBack(true)
                    }) {
                        Text(stringResource(R.string.exit_background))
                    }
                }
            }
        )
    }

    // First-launch welcome tour. Targets register their bounds via
    // Modifier.coachmarkTarget below; the overlay (after the Scaffold, inside
    // this Box so it can dim the top bar too) spotlights each in turn. Shown
    // only while the persisted flag is still false; Skip / Done set it.
    val coachmark = rememberCoachmarkState()
    val welcomeTutorialSeen by viewModel.welcomeTutorialSeen.collectAsState()
    var tourDismissed by remember { mutableStateOf(false) }
    val showWelcomeTour = !welcomeTutorialSeen && !tourDismissed

    Box(modifier = Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.eried.eucplanet.ui.common.LocalSnackbar provides snackbar,
        com.eried.eucplanet.ui.common.LocalSnackbarScope provides snackbarScope
    ) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.imePadding()) {
                androidx.compose.material3.Snackbar(
                    it,
                    containerColor = MaterialTheme.appColors.snackbarBackground,
                    contentColor = MaterialTheme.appColors.snackbarText,
                    actionContentColor = MaterialTheme.appColors.snackbarAction
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.padding(start = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConnectionDot(connectionState)
                        Spacer(Modifier.width(8.dp))
                        val connectedLabel = stringResource(R.string.connection_connected)
                        val connectingLabel = stringResource(R.string.connection_connecting)
                        val initLabel = stringResource(R.string.connection_initializing)
                        val scanningLabel = stringResource(R.string.connection_scanning)
                        val disconnectedLabel = stringResource(R.string.connection_disconnected)
                        Text(
                            text = when (connectionState) {
                                ConnectionState.CONNECTED -> when (wheelNameDisplay) {
                                    "NONE" -> connectedLabel
                                    "BRAND" -> connectedBrand ?: connectedDeviceName ?: connectedLabel
                                    else -> modelNameShort ?: connectedDeviceName ?: connectedLabel
                                }
                                ConnectionState.CONNECTING -> connectingLabel
                                ConnectionState.INITIALIZING -> initLabel
                                ConnectionState.SCANNING -> scanningLabel
                                ConnectionState.DISCONNECTED -> disconnectedLabel
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                },
                actions = {
                    val warnings by viewModel.warnings.collectAsState()
                    if (warnings.isNotEmpty()) {
                        IconButton(onClick = { showWarningsDialog = true }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = stringResource(R.string.warnings_indicator_desc),
                                    tint = MaterialTheme.appColors.statusWarn
                                )
                                if (warnings.size > 1) {
                                    Text(
                                        text = warnings.size.toString(),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 2.dp, end = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (flicShowOnDashboard) {
                        FlicIndicator(
                            hasFlic = hasFlic,
                            flashAt = flicFlashAt,
                            onClick = onNavigateToFlic
                        )
                    }
                    // Battery spark — tap opens the Battery monitor; visibility is a
                    // setting. Tint signals charging (accent) vs not (muted).
                    val chargeStatus by viewModel.chargeStatus.collectAsState()
                    val showChargingIcon by viewModel.chargingDashboardIcon.collectAsState()
                    if (showChargingIcon) {
                        IconButton(onClick = onNavigateToCharging) {
                            val chargingNow = chargeStatus == com.eried.eucplanet.data.model.ChargeStatus.Charging ||
                                chargeStatus == com.eried.eucplanet.data.model.ChargeStatus.Full
                            Icon(
                                // Vertical battery: with the charging bolt while charging,
                                // plain full battery otherwise. Neutral tint (no green).
                                imageVector = if (chargingNow) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                                contentDescription = stringResource(R.string.charging_monitor),
                                tint = MaterialTheme.appColors.textSecondary,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (connectionState == ConnectionState.CONNECTED) {
                                showDisconnectDialog = true
                            } else {
                                onNavigateToScan()
                            }
                        },
                        modifier = Modifier.coachmarkTarget(coachmark, TutorialTarget.BLUETOOTH)
                    ) {
                        Icon(
                            if (connectionState == ConnectionState.DISCONNECTED)
                                Icons.AutoMirrored.Filled.BluetoothSearching
                            else Icons.Default.Bluetooth,
                            contentDescription = stringResource(R.string.connection)
                        )
                    }
                    Box {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { onNavigateToSettings(null) },
                                    onLongClick = { showSettingsMenu = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings)
                            )
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false },
                            containerColor = MaterialTheme.appColors.menuBackground
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.section_cloud_settings)) },
                                onClick = {
                                    showSettingsMenu = false
                                    onNavigateToSettings(4)
                                }
                            )
                            if (hasSyncFolder) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cloud_backup_now)) },
                                    onClick = {
                                        showSettingsMenu = false
                                        viewModel.backupSettingsNow()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cloud_restore)) },
                                    onClick = {
                                        showSettingsMenu = false
                                        showRestoreConfirmDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.appColors.topBar
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Experimental-build banner, flush to the screen edges, above all other content.
            com.eried.eucplanet.ui.common.ExperimentalBanner(
                state = viewModel.experimentalBannerState,
                detectedWheelName = modelName,
                detectedFirmware = firmwareVersion
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            val effectiveTiltback = if (safetyActive) safetyTiltbackSpeed else tiltbackSpeed
            // Floor the gauge at 30 km/h so a bogus/zero tiltback (e.g. when
            // a wheel doesn't report tiltback over BLE) still gives a usable
            // speedo. Without the floor we'd get ((0/10)+1)*10 = 10 km/h max,
            // which renders as ~6 mph and looks completely broken.
            //
            // Cap the dial at the detected wheel's hardware max (+5 km/h
            // breathing room) so a Mten3/Mten4 owner doesn't see a 110 km/h
            // dial just because they bumped tilt-back high — the dial is
            // useless if 80% of it is unreachable. The cap only applies
            // when we actually KNOW the wheel's max (model detected and
            // its enum sets maxSpeedKmh); for unrecognised wheels the cap
            // sits at WheelRepository.DEFAULT_MAX_SPEED_KMH (90), which we
            // treat as "unknown — don't constrain" so a high-end wheel we
            // failed to identify (or a rider on a new/protocol-unsupported
            // model) keeps the rider-tilt-back-driven scale they had
            // before.
            val rawGaugeMax = (((effectiveTiltback / 10f).toInt() + 1) * 10f).coerceAtLeast(30f)
            val knownModelCapped = wheelMaxSpeedCap < 90f
            val gaugeMax = if (knownModelCapped) {
                minOf(rawGaugeMax, wheelMaxSpeedCap + 5f)
            } else rawGaugeMax
            val pwm = wheelData.pwm.absoluteValue

            // Foldables / tablets: cap the speedo and use a 3-column stat grid so
            // the whole dashboard fits without the footer falling off-screen.
            val wideStats = LocalConfiguration.current.screenWidthDp >= 600

            // Speed gauge, wide arc dial (tap opens history)
            val useAccent = !com.eried.eucplanet.ui.theme.isDefaultAccent(accentKey)
            val primary = MaterialTheme.colorScheme.primary
            // BoxWithConstraints with weight(1f, fill=true): the dial Box
            // absorbs ALL leftover vertical space so the ODO footer stays
            // pinned just below the action buttons. On phones the dial is a
            // near-square at ratio 1.05; on tablets it becomes a wide-arc
            // car-dash style speedo (ratio 2.0) so the extra horizontal real
            // estate is actually used. Width is capped on phones at 0.85 of
            // screen, on tablets at 0.95 to leave a small margin.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = true)
            ) {
                val widthFraction = if (wideStats) 0.95f else 0.85f
                val ratio = if (wideStats) 2.0f else 1.05f
                val candidateW = maxWidth * widthFraction
                val maxByHeight = maxHeight * ratio
                val dialW = minOf(candidateW, maxByHeight)
                // Smoothly glide the displayed speed between telemetry pushes
                // (~4-5 Hz) so the dial arc and the big number track
                // continuously instead of snapping. ~250 ms linear ≈ one
                // update interval: no lag, no stutter. When telemetry goes
                // stale and speed is forced to 0, the animation to 0 is fine.
                val animatedSpeed by animateFloatAsState(
                    targetValue = wheelData.speed.absoluteValue * cheatSpeedMult,
                    animationSpec = tween(durationMillis = 250, easing = LinearEasing),
                    label = "dashSpeed"
                )
                SpeedGauge(
                    speed = animatedSpeed,
                    maxSpeed = gaugeMax,
                    speedUnit = speedUnit,
                    // Accents are gone; the speed arc now follows the themeable
                    // gaugeFill token (so it matches the watch and is editable).
                    overrideColor = null,
                    showColorBand = showGaugeColorBand,
                    orangeThresholdPct = gaugeOrangePct,
                    redThresholdPct = gaugeRedPct,
                    // Speed-arc fill = the gaugeFill token. When the color band is
                    // on, the warn/danger tiers still override it as safety signals.
                    safeBandColor = MaterialTheme.appColors.gaugeFill,
                    // Extra GPS overlay (dot on the dial). Colour depends on
                    // which source is active per user's GPS preferences.
                    externalSpeed = externalGpsSpeed,
                    externalAccentColor = externalGpsAccent,
                    modifier = Modifier
                        .width(dialW)
                        .aspectRatio(ratio)
                        .align(Alignment.Center)
                        .coachmarkTarget(coachmark, TutorialTarget.SPEED_DIAL)
                )
                // Unit label ("mph" / "km/h") aligned to the same bottom line
                // as the Map / Camera glyphs below, so when the experimental
                // banner squeezes the BoxWithConstraints they all move up
                // together instead of the unit text falling onto the cards.
                Text(
                    text = com.eried.eucplanet.util.Units.speedUnit(LocalContext.current, speedUnit),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Scale with the gauge so phones and tablets see a
                    // proportional unit label, matching the prominence the
                    // old Canvas-drawn version had.
                    fontSize = (dialW.value * 0.15f).sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // paddingFromBaseline pins the BASELINE of the text
                        // (the bottom of 'm' and 'h'), not the descender bottom
                        // of 'p', to 10dp above the parent's bottom edge --
                        // matching the Map icon's bottom padding so all three
                        // sit on the same line.
                        .paddingFromBaseline(bottom = 10.dp)
                )
                // Only the centre of the dial opens speed history, the empty
                // corners of the gauge's bounding box no longer steal taps
                // meant for the P/D, GPS, map and camera glyphs.
                Box(
                    Modifier
                        .size(dialW * 0.5f)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .clickable { onNavigateToMetric("SPEED") }
                )
                // Car-dashboard status cluster, top-left: P (park) / D (drive).
                val live = connectionState == ConnectionState.CONNECTED
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .coachmarkTarget(coachmark, TutorialTarget.PARK_DRIVE)
                        // The P / D cluster is one tap target, it opens the
                        // wheel speed settings.
                        .clickable { onNavigateToSettings(2) }
                        .padding(top = 8.dp, start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DashIndicatorLetter(
                        "P", active = live && wheelData.pcMode != 1,
                        activeColor = if (useAccent) primary else MaterialTheme.colorScheme.onSurface
                    )
                    DashIndicatorLetter(
                        "D", active = live && wheelData.pcMode == 1,
                        activeColor = if (useAccent) primary else MaterialTheme.appColors.statusGood
                    )
                }
                // GPS indicator, top-right. The icon glyph + colour speak for
                // the phone's GPS:
                //   no permission  -> GpsOff (dim)
                //   no fix yet     -> GpsNotFixed (dim)
                //   locked         -> GpsFixed (green)
                // A small "E" badge sits beneath the icon when a paired
                // external GPS (RaceBox today) is actively sending samples,
                // so the rider sees at a glance whether ground-truth speed
                // is coming from their box vs the phone. Tapping anywhere
                // opens the multi-source live data sheet.
                val gpsIcon = when {
                    !locationGranted -> Icons.Default.GpsOff
                    gpsFix -> Icons.Default.GpsFixed
                    else -> Icons.Default.GpsNotFixed
                }
                val externalLive = gpsExtra?.second == "EXTERNAL"
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .coachmarkTarget(coachmark, TutorialTarget.GPS_BUTTON)
                        .offset(x = 4.dp)
                        .padding(top = 8.dp)
                        .combinedClickable(
                            onClick = { showSourcesSheet = true },
                            onLongClick = { showGpsMenu = true }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DashIndicatorIcon(
                        icon = gpsIcon,
                        active = gpsFix && locationGranted,
                        activeColor = if (useAccent) primary else MaterialTheme.appColors.statusGood,
                        modifier = Modifier
                    )
                    // E badge only when an external GPS is paired at all. Dim
                    // when paired but stale, lit when actively sending. Hidden
                    // entirely for riders without a RaceBox or similar so the
                    // dashboard stays clean for the common case.
                    if (externalGpsPaired) {
                        DashIndicatorLetter(
                            "E",
                            active = externalLive,
                            activeColor = if (useAccent) primary else MaterialTheme.appColors.statusGood
                        )
                    }
                    DropdownMenu(
                        expanded = showGpsMenu,
                        onDismissRequest = { showGpsMenu = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.dash_external_gps_settings))
                            },
                            onClick = {
                                showGpsMenu = false
                                onNavigateToSettings(7)
                            }
                        )
                    }
                }
                // Overlay Studio, same indicator styling as the GPS / P-D
                // glyphs (no chrome, accent colour), mirroring the GPS column
                // in the dial's bottom-right corner.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .coachmarkTarget(coachmark, TutorialTarget.CAMERA_BUTTON)
                ) {
                    DashIndicatorIcon(
                        icon = Icons.Default.PhotoCamera,
                        active = true,
                        activeColor = MaterialTheme.appColors.dashIcon,
                        modifier = Modifier
                            .offset(x = 4.dp)
                            .padding(bottom = 8.dp)
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.studio_open),
                                onClick = { onNavigateToStudio() },
                                onLongClick = { showStudioMenu = true }
                            )
                    )
                    DropdownMenu(
                        expanded = showStudioMenu,
                        onDismissRequest = { showStudioMenu = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dash_view_video_gallery)) },
                            onClick = {
                                showStudioMenu = false
                                openMediaGallery(toastContext, video = true) {
                                    snackbarScope.launch {
                                        snackbar.showSnackbar(toastContext.getString(R.string.dash_no_gallery_app))
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dash_view_photo_gallery)) },
                            onClick = {
                                showStudioMenu = false
                                openMediaGallery(toastContext, video = false) {
                                    snackbarScope.launch {
                                        snackbar.showSnackbar(toastContext.getString(R.string.dash_no_gallery_app))
                                    }
                                }
                            }
                        )
                    }
                }
                // Navigator entry point, bottom-left, a bare icon styled to
                // match the P / D indicators above it (same left column). While
                // guidance runs it turns into a green arrow rotating toward the
                // next point; otherwise it follows the rider's accent colour.
                // Tapping it during guidance re-opens the navigation popup so
                // the rider can act on it; otherwise it opens the route builder.
                val navActive = navState.active
                val navBtnAngle by animateFloatAsState(
                    if (navActive) navState.arrowAngleDeg() else 0f,
                    label = "navBtnArrow"
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .coachmarkTarget(coachmark, TutorialTarget.MAP_BUTTON)
                ) {
                    Icon(
                        imageVector = if (navActive) Icons.Default.Navigation
                        else Icons.Default.Map,
                        contentDescription = stringResource(R.string.nav_open),
                        tint = MaterialTheme.appColors.dashIcon,
                        modifier = Modifier
                            .padding(start = 4.dp, bottom = 10.dp)
                            .size(32.dp)
                            .combinedClickable(
                                onClick = {
                                    if (navActive) navOverlayVm.requestPopup()
                                    else onNavigateToNavigator()
                                },
                                onLongClick = { showMapMenu = true }
                            )
                            .rotate(if (navActive) navBtnAngle else 0f)
                    )
                    DropdownMenu(
                        expanded = showMapMenu,
                        onDismissRequest = { showMapMenu = false },
                        containerColor = MaterialTheme.appColors.menuBackground
                    ) {
                        // a) Start / Stop, shown only when there is an active
                        // session to stop, or a saved route to start.
                        if (navActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_stop_short)) },
                                onClick = {
                                    showMapMenu = false
                                    navOverlayVm.endNavigation()
                                }
                            )
                        } else if (currentRoute != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_start_short)) },
                                onClick = {
                                    showMapMenu = false
                                    navOverlayVm.startCurrentRoute()
                                }
                            )
                        }
                        // b) Quick jump to the navigation settings.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_setting_params)) },
                            onClick = {
                                showMapMenu = false
                                onNavigateToSettings(8)
                            }
                        )
                    }
                }
                // Radar mini lanes. CenterStart / CenterEnd line up
                // vertically through the Map (BottomStart) and Studio
                // (BottomEnd) glyphs ,  one straight left/right axis from
                // each lane down to the corner button beneath it.
                // Radar mini lanes. Horizontal bias -1 / +1 = same as
                // CenterStart / CenterEnd. Vertical bias +0.3 drops them
                // a bit below the dial centre so they sit above the Map
                // and Studio corner glyphs rather than crowding the
                // top P/D and GPS clusters.
                //
                // The right lane gets a +9 dp horizontal offset to line
                // up with the Studio camera glyph's centre ,  the Studio
                // icon is offset(x=4.dp) past the parent edge (32 dp wide,
                // right edge at parent+4) so its centre sits at
                // parent_width - 12 dp. The radar lane is 42 dp wide
                // including its outer padding; at bias +1 its centre lands
                // at parent_width - 21 dp, leaving a 9 dp shortfall. The
                // offset closes that gap.
                //
                // Tap opens the radar pairing section of the Integration
                // settings (tab 7).
                DashboardRadarMiniForSide(
                    targetSide = "LEFT",
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(-1f, 0.45f)),
                    onOpenSettings = { onNavigateToSettings(7) }
                )
                DashboardRadarMiniForSide(
                    targetSide = "RIGHT",
                    modifier = Modifier
                        .align(androidx.compose.ui.BiasAlignment(1f, 0.45f))
                        .offset(x = 9.dp),
                    onOpenSettings = { onNavigateToSettings(7) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Stats grid, 3 rows of 2. Alert tiers only apply when connected (disconnected values are 0).
            val live = connectionState == ConnectionState.CONNECTED
            // Each metric tile colors its number with its own family token
            // (independent of the theme accent), keeping the warn/danger
            // overrides below so battery/temp/load still go yellow/red. Read the
            // family colors into vals once so the tile lambdas reference a Color
            // rather than re-reading the theme inline; that keeps this very large
            // dashboard composable under the bytecode-verifier method limit.
            val metricVoltageColor = MaterialTheme.appColors.metricVoltage
            val metricBatteryColor = MaterialTheme.appColors.metricBattery
            val metricTempColor = MaterialTheme.appColors.metricTemp
            val battColor = when {
                live && wheelData.batteryPercent < 20 -> MaterialTheme.appColors.statusDanger
                live && wheelData.batteryPercent < 40 -> MaterialTheme.appColors.statusWarn
                else -> metricBatteryColor
            }
            // EUC motor temperature tiers. The stored value is always °C, so
            // the thresholds are unit-independent, the display layer converts
            // to °F for imperial users but the color rule reads the same °C
            // value. Riders pushed back: 90 °C / 195 °F is normal under load,
            // 220 °F is the natural "ease off" mark, 300 °F is true danger.
            //   below 105 °C (≈ 221 °F)             green
            //   105–149 °C (≈ 221–300 °F)           yellow
            //   ≥ 150 °C (≈ 302 °F)                 red
            val tempColor = when {
                !live || wheelData.maxTemperature <= 0f -> metricTempColor
                wheelData.maxTemperature >= 150f -> MaterialTheme.appColors.statusDanger
                wheelData.maxTemperature >= 105f -> MaterialTheme.appColors.gaugeWarn
                else -> metricTempColor
            }
            val loadColor = when {
                live && pwm >= 80 -> MaterialTheme.appColors.statusDanger
                live && pwm >= 60 -> MaterialTheme.appColors.statusWarn
                else -> metricTempColor
            }

            // Em-dash reads as a substantial "no data" symbol in the stat
            // card; a plain hyphen looked too small for the card layout.
            // (The no-em-dashes rule applies to sentence punctuation, not
            // to a single decorative placeholder glyph.)
            val placeholder = "--"

            val tempValue = com.eried.eucplanet.util.Units.temperature(wheelData.maxTemperature, tempUnit)
            val tempUnitLabel = com.eried.eucplanet.util.Units.tempUnit(tempUnit)
            val showWatts = currentMode == "WATTS"
            val ampsLabel = stringResource(R.string.stat_amps)
            val wattsLabel = stringResource(R.string.stat_watts)
            val currentValue = if (showWatts) wheelData.voltage * wheelData.current else wheelData.current
            val currentText = when {
                !live -> placeholder
                showWatts -> "%.0fW".format(currentValue)
                else -> "%.1fA".format(currentValue)
            }
            val tripValue = com.eried.eucplanet.util.Units.distance(wheelData.tripDistance, distanceUnit)
            val distUnit = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
            // Short unit string for the WHEEL_MAX_SPEED / WHEEL_ALARM_SPEED
            // tiles. Mirrors the dashboard's existing speed-unit-label
            // pattern but only the suffix, no context lookup, so the
            // composite renderer (which doesn't have a Compose Context in
            // its closure) can use it straight.
            val speedUnitLabel = when (speedUnit) {
                "mph" -> "mph"
                "ms" -> "m/s"
                "kn" -> "kn"
                else -> "km/h"
            }

            // A connected wheel that hasn't sent telemetry yet leaves these
            // fields at the WheelData defaults (0). Showing "0%" or "0.0V"
            // looks like a real reading; the placeholder makes it obvious
            // we're waiting for the wheel to talk.
            // Read the rider's customized metric order. Falls back to
            // the catalog defaults (BATTERY, TEMPERATURE, VOLTAGE,
            // CURRENT, LOAD, TRIP) when blank or short — guarantees the
            // out-of-box layout is byte-identical to the old hardcoded
            // 6-card grid.
            val activeMetricKeys = remember(dashboardMetricOrderRaw) {
                val parsed = dashboardMetricOrderRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val defaults = listOf("BATTERY", "TEMPERATURE", "VOLTAGE", "CURRENT", "LOAD", "TRIP")
                (parsed + defaults).distinct().take(6)
            }
            // Tablets force 3 columns to honour wideStats. Phones use
            // whatever the rider set in the editor (2 or 3).
            val metricColumns = if (wideStats) 3
                else dashboardMetricsColumnsSetting.coerceIn(2, 3)
            val metricRows = (activeMetricKeys.size + metricColumns - 1) / metricColumns

            // Per-slot sparkline-enabled flag. JSON shape mirrors
            // SettingsViewModel.parseSlotStats: { "<KEY>": { "spark": Bool, "l": Stat, "r": Stat } }
            // Sparkline defaults to true so unconfigured tiles match
            // today's always-on rendering.
            fun sparkEnabledFor(key: String): Boolean = try {
                val root = org.json.JSONObject(dashboardMetricStatsJson.ifBlank { "{}" })
                root.optJSONObject(key)?.optBoolean("spark", true) ?: true
            } catch (_: Exception) { true }

            // Per-slot corner-stat parser. Same JSON shape as SettingsViewModel's
            // parseSlotStats: { "<KEY>": { "spark": Bool, "l": Stat, "c": Stat, "r": Stat } }
            // Returns a triple of (left, center, right) stats; unconfigured slots
            // get NONE for the corners (no readout) and CURRENT for the center
            // (live value, today's default).
            data class SlotStatsTriple(
                val left: com.eried.eucplanet.ui.settings.DashboardStat,
                val center: com.eried.eucplanet.ui.settings.DashboardStat,
                val right: com.eried.eucplanet.ui.settings.DashboardStat
            )
            fun slotStatsFor(key: String): SlotStatsTriple {
                return try {
                    val root = org.json.JSONObject(dashboardMetricStatsJson.ifBlank { "{}" })
                    val node = root.optJSONObject(key) ?: return SlotStatsTriple(
                        com.eried.eucplanet.ui.settings.DashboardStat.NONE,
                        com.eried.eucplanet.ui.settings.DashboardStat.CURRENT,
                        com.eried.eucplanet.ui.settings.DashboardStat.NONE
                    )
                    fun parseStat(jsonKey: String, default: com.eried.eucplanet.ui.settings.DashboardStat) =
                        runCatching {
                            com.eried.eucplanet.ui.settings.DashboardStat.valueOf(
                                node.optString(jsonKey, default.name)
                            )
                        }.getOrDefault(default)
                    SlotStatsTriple(
                        left = parseStat("l", com.eried.eucplanet.ui.settings.DashboardStat.NONE),
                        center = parseStat("c", com.eried.eucplanet.ui.settings.DashboardStat.CURRENT),
                        right = parseStat("r", com.eried.eucplanet.ui.settings.DashboardStat.NONE)
                    )
                } catch (_: Exception) {
                    SlotStatsTriple(
                        com.eried.eucplanet.ui.settings.DashboardStat.NONE,
                        com.eried.eucplanet.ui.settings.DashboardStat.CURRENT,
                        com.eried.eucplanet.ui.settings.DashboardStat.NONE
                    )
                }
            }

            // Compute a corner-stat value for a metric key from its 5-min
            // sparkline history. Returns a pre-formatted string like "82%" /
            // "27°" / "84.1V" so the tile can drop it straight into a corner
            // chip without re-deriving units.
            //
            // Returns null ONLY when the stat itself is NONE or CURRENT (those
            // paths don't render a corner chip / don't override the centre).
            // When the rider has picked a real stat but the history buffer is
            // empty (cold boot, no wheel connected yet), returns the
            // placeholder dash — that way the rider sees confirmation the
            // setting took effect on the tile, with the value filling in
            // once samples start flowing.
            fun cornerStatValueFor(
                key: String,
                stat: com.eried.eucplanet.ui.settings.DashboardStat
            ): String? {
                if (stat == com.eried.eucplanet.ui.settings.DashboardStat.NONE ||
                    stat == com.eried.eucplanet.ui.settings.DashboardStat.CURRENT ||
                    stat == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY) return null
                val buf = when (key) {
                    "BATTERY" -> history.battery
                    "TEMPERATURE" -> history.temperature
                    "VOLTAGE" -> history.voltage
                    "CURRENT" -> history.current
                    "LOAD" -> history.load
                    "SPEED" -> history.speed
                    else -> return placeholder
                }
                if (buf.isEmpty()) return placeholder
                val samples = buf.mapIndexed { idx, v ->
                    com.eried.eucplanet.data.repository.MetricSample(idx.toLong(), v)
                }
                val raw = com.eried.eucplanet.ui.settings.computeDashboardStatValue(
                    stat, samples, fallbackCurrent = buf.last()
                ) ?: return placeholder
                return when (key) {
                    "BATTERY", "LOAD" -> "${raw.toInt()}%"
                    "TEMPERATURE" -> "${raw.toInt()}°"
                    "VOLTAGE" -> "%.1fV".format(raw)
                    "CURRENT" -> "%.1fA".format(raw)
                    "SPEED" -> "%.0f".format(raw)
                    else -> "%.1f".format(raw)
                }
            }

            // Short stat label for the corner chip — "MAX", "MIN", "AVG",
            // "P50", etc. Mirrors statShortLabel in SettingsScreen so the
            // tile reads the same as the editor preview.
            fun shortStatLabel(stat: com.eried.eucplanet.ui.settings.DashboardStat): String =
                when (stat) {
                    com.eried.eucplanet.ui.settings.DashboardStat.NONE -> ""
                    com.eried.eucplanet.ui.settings.DashboardStat.CURRENT -> ""
                    // EMPTY is a reserved placeholder -- no visible label or
                    // value, but the tile renderer still reserves its slot.
                    com.eried.eucplanet.ui.settings.DashboardStat.EMPTY -> ""
                    com.eried.eucplanet.ui.settings.DashboardStat.MIN -> "MIN"
                    com.eried.eucplanet.ui.settings.DashboardStat.MAX -> "MAX"
                    com.eried.eucplanet.ui.settings.DashboardStat.AVG -> "AVG"
                    com.eried.eucplanet.ui.settings.DashboardStat.SUSTAINED_PEAK -> "PEAK"
                    com.eried.eucplanet.ui.settings.DashboardStat.MEDIAN -> "P50"
                    com.eried.eucplanet.ui.settings.DashboardStat.P75 -> "P75"
                    com.eried.eucplanet.ui.settings.DashboardStat.P95 -> "P95"
                    com.eried.eucplanet.ui.settings.DashboardStat.P99 -> "P99"
                }

            // Per-slot value resolver — turns any metric key into its
            // current displayable value. Reused by composite tiles to
            // populate each of their 2-3 cells. Static metric formatting
            // mirrors the default-6 cards above; new metric keys fall
            // back to placeholder until their value path lands.
            fun displayValueFor(metricKey: String): String {
                if (!live) return placeholder
                return when (metricKey) {
                    "BATTERY" -> if (wheelData.batteryPercent > 0) "${wheelData.batteryPercent}%" else placeholder
                    "TEMPERATURE" -> if (wheelData.maxTemperature > 0f)
                        "%.0f%s".format(tempValue, tempUnitLabel) else placeholder
                    "VOLTAGE" -> if (wheelData.voltage > 0f) "%.1fV".format(wheelData.voltage) else placeholder
                    "CURRENT" -> currentText
                    "LOAD" -> "%.0f%%".format(pwm)
                    "TRIP" -> "%.1f %s".format(tripValue, distUnit)
                    "SPEED" -> "%.0f".format(wheelData.speed)
                    "BATTERY_POWER", "POWER" -> "${wheelData.batteryPower}W"
                    "MOTOR_POWER" -> "${wheelData.motorPower}W"
                    // Composite-friendly extras (also work as standalone
                    // tiles -- the small numbers fit fine in a 61 dp box).
                    "LAT_LONG" -> if (wheelData.latitude != 0.0 || wheelData.longitude != 0.0)
                        "%.4f, %.4f".format(wheelData.latitude, wheelData.longitude) else placeholder
                    "WHEEL_MAX_SPEED" -> if (wheelData.wheelMaxSpeedKmh > 0f)
                        "%.0f %s".format(
                            com.eried.eucplanet.util.Units.speed(wheelData.wheelMaxSpeedKmh, speedUnit),
                            speedUnitLabel
                        ) else placeholder
                    "WHEEL_ALARM_SPEED" -> if (wheelData.wheelAlarmSpeedKmh > 0f)
                        "%.0f %s".format(
                            com.eried.eucplanet.util.Units.speed(wheelData.wheelAlarmSpeedKmh, speedUnit),
                            speedUnitLabel
                        ) else placeholder
                    "PC_MODE" -> when (wheelData.pcMode) {
                        0 -> "LOCK"
                        1 -> "DRIVE"
                        2 -> "OFF"
                        3 -> "IDLE"
                        else -> placeholder
                    }
                    "LIGHT_ON" -> if (wheelData.lightOn) "ON" else "OFF"
                    "ODOMETER" -> "%.1f %s".format(
                        com.eried.eucplanet.util.Units.distance(wheelData.totalDistance, distanceUnit),
                        distUnit
                    )
                    "BATTERY_1" -> if (wheelData.battery1Percent > 0f) "%.0f%%".format(wheelData.battery1Percent) else placeholder
                    "BATTERY_2" -> if (wheelData.battery2Percent > 0f) "%.0f%%".format(wheelData.battery2Percent) else placeholder
                    "PITCH" -> "%.1f°".format(wheelData.pitchAngle)
                    "ROLL" -> "%.1f°".format(wheelData.rollAngle)
                    "G_FORCE" -> "%.2fg".format(wheelData.gForce)
                    "LATERAL_G" -> "%.2fg".format(wheelData.accelX)
                    "FORWARD_G" -> "%.2fg".format(wheelData.forwardGFromSpeed)
                    "TORQUE" -> "%.1fNm".format(wheelData.torque)
                    "DYN_SPEED_LIMIT" -> if (wheelData.dynamicSpeedLimit > 0f)
                        "%.0f".format(wheelData.dynamicSpeedLimit) else placeholder
                    "DYN_CURRENT_LIMIT" -> if (wheelData.dynamicCurrentLimit > 0f)
                        "%.1fA".format(wheelData.dynamicCurrentLimit) else placeholder
                    "MOTOR_TEMP" -> wheelData.temperatures.getOrNull(0)
                        ?.takeIf { com.eried.eucplanet.util.MetricSanity.isPlausibleTempC(it) }
                        ?.let { "%.0f%s".format(com.eried.eucplanet.util.Units.temperature(it, tempUnit), tempUnitLabel) }
                        ?: placeholder
                    "CONTROLLER_TEMP" -> wheelData.temperatures.getOrNull(1)
                        ?.takeIf { com.eried.eucplanet.util.MetricSanity.isPlausibleTempC(it) }
                        ?.let { "%.0f%s".format(com.eried.eucplanet.util.Units.temperature(it, tempUnit), tempUnitLabel) }
                        ?: placeholder
                    "BATTERY_TEMP" -> wheelData.temperatures.getOrNull(2)
                        ?.takeIf { com.eried.eucplanet.util.MetricSanity.isPlausibleTempC(it) }
                        ?.let { "%.0f%s".format(com.eried.eucplanet.util.Units.temperature(it, tempUnit), tempUnitLabel) }
                        ?: placeholder
                    "PHONE_BATTERY" -> if (phoneBatteryPct in 0..100) "$phoneBatteryPct%" else placeholder
                    "GPS_ALTITUDE" -> gpsLocation?.altitude?.let { alt ->
                        // Imperial users see feet; everyone else meters.
                        if (distanceUnit == "mi") "%.0fft".format(alt.toFloat() * 3.28084f)
                        else "%.0fm".format(alt.toFloat())
                    } ?: placeholder
                    "GPS_SPEED" -> gpsLocation?.let { loc ->
                        // Same conversion path as every other speed tile: store
                        // km/h, convert to the rider's unit at render time.
                        if (loc.hasSpeed()) "%.1f %s".format(
                            com.eried.eucplanet.util.Units.speed(loc.speed * 3.6f, speedUnit),
                            speedUnitLabel
                        ) else placeholder
                    } ?: placeholder
                    "GPS_HEADING" -> gpsLocation?.let { loc ->
                        if (loc.hasBearing()) "%.0f°".format(loc.bearing) else placeholder
                    } ?: placeholder
                    "GPS_ACCURACY" -> gpsLocation?.let { loc ->
                        if (loc.hasAccuracy()) {
                            if (distanceUnit == "mi") "%.0fft".format(loc.accuracy * 3.28084f)
                            else "%.0fm".format(loc.accuracy)
                        } else placeholder
                    } ?: placeholder
                    // SLOPE / ASCENT / DESCENT need integrated altitude
                    // history (not yet wired). MOTOR_RPM / REGEN_WH /
                    // BT_RSSI aren't surfaced on WheelData today — those
                    // need adapter-side plumbing. Placeholder for now.
                    else -> placeholder
                }
            }

            // Lookup composite definition by id from the JSON blob the
            // editor writes. Returns null if the id isn't found (which
            // means the rider deleted it but the order entry still
            // points at it — render an empty placeholder in that case).
            fun compositeFor(id: String): com.eried.eucplanet.ui.settings.MetricComposite? = try {
                val root = org.json.JSONObject(dashboardCompositesJson.ifBlank { "{}" })
                val node = root.optJSONObject(id) ?: return@compositeFor null
                val layout = runCatching {
                    com.eried.eucplanet.ui.settings.CompositeLayout.valueOf(
                        node.optString("layout", com.eried.eucplanet.ui.settings.CompositeLayout.ROW2.name)
                    )
                }.getOrDefault(com.eried.eucplanet.ui.settings.CompositeLayout.ROW2)
                val cellsArr = node.optJSONArray("cells")
                val cells = if (cellsArr != null) {
                    (0 until cellsArr.length()).mapNotNull {
                        cellsArr.optString(it).takeIf { s -> s.isNotEmpty() }
                    }
                } else emptyList()
                val statsArr = node.optJSONArray("cellStats")
                val cellStats: List<com.eried.eucplanet.ui.settings.DashboardStat> = if (statsArr != null) {
                    (0 until statsArr.length()).map { idx ->
                        runCatching {
                            com.eried.eucplanet.ui.settings.DashboardStat.valueOf(
                                statsArr.optString(idx, com.eried.eucplanet.ui.settings.DashboardStat.CURRENT.name)
                            )
                        }.getOrDefault(com.eried.eucplanet.ui.settings.DashboardStat.CURRENT)
                    }
                } else List(cells.size) { com.eried.eucplanet.ui.settings.DashboardStat.CURRENT }
                com.eried.eucplanet.ui.settings.MetricComposite(layout, cells, cellStats)
            } catch (_: Exception) { null }

            fun customTileFor(id: String): com.eried.eucplanet.ui.settings.CustomTile? = try {
                val root = org.json.JSONObject(dashboardCustomTilesJson.ifBlank { "{}" })
                val node = root.optJSONObject(id) ?: return@customTileFor null
                val text = node.optString("text", "")
                val icon = node.optString("icon", "")
                val url = node.optString("url", "")
                val action = runCatching {
                    com.eried.eucplanet.ui.settings.CustomTileAction.valueOf(
                        node.optString("action", com.eried.eucplanet.ui.settings.CustomTileAction.NONE.name)
                    )
                }.getOrDefault(com.eried.eucplanet.ui.settings.CustomTileAction.NONE)
                com.eried.eucplanet.ui.settings.CustomTile(text, icon, action, url)
            } catch (_: Exception) { null }

            for (rowIdx in 0 until metricRows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Spotlight the WHOLE metrics grid: each row unions into
                        // one rect for the welcome tour.
                        .coachmarkTargetUnion(coachmark, TutorialTarget.METRICS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (colIdx in 0 until metricColumns) {
                        val slotIdx = rowIdx * metricColumns + colIdx
                        val rawKey = activeMetricKeys.getOrNull(slotIdx)
                        // EMPTY_SLOT_KEY = intentional blank from a move-and-
                        // empty drop in the editor. Render the same blank
                        // weight-1 spacer the missing-key path renders so the
                        // grid columns stay aligned.
                        val key = if (rawKey == com.eried.eucplanet.ui.settings.EMPTY_SLOT_KEY) null else rawKey
                        if (key == null) {
                            Box(modifier = Modifier.weight(1f))
                            continue
                        }
                        val spec = MetricCatalog.byKey(key)
                        val sparklineEnabled = sparkEnabledFor(key)
                        // Per-slot corner-stat config. Standalone tiles honor
                        // these (centre overrides the big number; left/right
                        // render small "MAX 94" / "MIN 78" chips at the bottom
                        // corners). Composite (MULTI) tiles ignore this layer
                        // and route through their per-cell stat list below.
                        val slotStats = slotStatsFor(key)
                        val centerOverride = cornerStatValueFor(key, slotStats.center)
                        val centerStatLabel = shortStatLabel(slotStats.center).takeIf { it.isNotEmpty() }
                        val cornerLeftLabel = shortStatLabel(slotStats.left).takeIf { it.isNotEmpty() }
                        val cornerLeftValue = cornerStatValueFor(key, slotStats.left)
                        val cornerRightLabel = shortStatLabel(slotStats.right).takeIf { it.isNotEmpty() }
                        val cornerRightValue = cornerStatValueFor(key, slotStats.right)
                        // Per-key value / colour / click ingredients.
                        // The default 6 keys preserve every quirk of the
                        // old StatCard era — long-press on CURRENT toggles
                        // A↔W, tap on TRIP opens the latest trip detail.
                        // New / customized keys still render via the
                        // catalog with a placeholder value where the
                        // dashboard hasn't extended the value path yet.
                        when (key) {
                            "BATTERY" -> Box(modifier = Modifier.weight(1f)) {
                                // Tap → Battery monitor; long-press → pick monitor or history.
                                var batteryMenuOpen by remember { mutableStateOf(false) }
                                LiveMetricTile(
                                    label = stringResource(R.string.stat_battery),
                                    value = centerOverride ?: if (live && wheelData.batteryPercent > 0)
                                        "${wheelData.batteryPercent}%" else placeholder,
                                    accent = battColor,
                                    sparkData = history.battery,
                                    sparkStyle = spec?.sparkline ?: SparklineStyle.AREA,
                                    sparklineEnabled = sparklineEnabled,
                                    bipolarBaseline = spec?.bipolarBaseline ?: 0f,
                                    bipolarNegativeAccent = spec?.bipolarNegativeAccent,
                                    cornerLeftLabel = cornerLeftLabel,
                                    cornerLeftValue = cornerLeftValue,
                                    cornerRightLabel = cornerRightLabel,
                                    cornerRightValue = cornerRightValue,
                                    leftReservesSlot = slotStats.left == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                    rightReservesSlot = slotStats.right == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                    centerStatLabel = centerStatLabel,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onNavigateToCharging() },
                                    onLongClick = { batteryMenuOpen = true },
                                )
                                DropdownMenu(
                                    expanded = batteryMenuOpen,
                                    onDismissRequest = { batteryMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.charging_monitor)) },
                                        onClick = { batteryMenuOpen = false; onNavigateToCharging() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.battery_history)) },
                                        onClick = { batteryMenuOpen = false; onNavigateToMetric("BATTERY") },
                                    )
                                }
                            }
                            "TEMPERATURE" -> {
                                val tempUnknown = wheelData.maxTemperature <= 0f
                                LiveMetricTile(
                                    label = stringResource(R.string.stat_temp),
                                    value = centerOverride ?: if (live && !tempUnknown)
                                        "%.0f%s".format(tempValue, tempUnitLabel) else placeholder,
                                    accent = tempColor,
                                    sparkData = history.temperature,
                                    sparkStyle = spec?.sparkline ?: SparklineStyle.AREA,
                                    sparklineEnabled = sparklineEnabled,
                                    cornerLeftLabel = cornerLeftLabel,
                                    cornerLeftValue = cornerLeftValue,
                                    cornerRightLabel = cornerRightLabel,
                                    cornerRightValue = cornerRightValue,
                                    leftReservesSlot = slotStats.left == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                    rightReservesSlot = slotStats.right == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                    centerStatLabel = centerStatLabel,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigateToMetric("TEMPERATURE") }
                                )
                            }
                            "VOLTAGE" -> LiveMetricTile(
                                label = stringResource(R.string.stat_voltage),
                                value = centerOverride ?: if (live && wheelData.voltage > 0f)
                                    "%.1fV".format(wheelData.voltage) else placeholder,
                                accent = metricVoltageColor,
                                sparkData = history.voltage,
                                sparkStyle = spec?.sparkline ?: SparklineStyle.LINE,
                                sparklineEnabled = sparklineEnabled,
                                cornerLeftLabel = cornerLeftLabel,
                                cornerLeftValue = cornerLeftValue,
                                cornerRightLabel = cornerRightLabel,
                                cornerRightValue = cornerRightValue,
                                leftReservesSlot = slotStats.left == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                rightReservesSlot = slotStats.right == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                centerStatLabel = centerStatLabel,
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigateToMetric("VOLTAGE") }
                            )
                            "CURRENT" -> LiveMetricTile(
                                label = if (showWatts) wattsLabel else ampsLabel,
                                value = centerOverride ?: currentText,
                                accent = if (live && wheelData.current > 20) MaterialTheme.appColors.statusWarn else metricVoltageColor,
                                sparkData = history.current,
                                sparkStyle = spec?.sparkline ?: SparklineStyle.AREA_BIPOLAR,
                                sparklineEnabled = sparklineEnabled,
                                bipolarBaseline = spec?.bipolarBaseline ?: 0f,
                                bipolarNegativeAccent = spec?.bipolarNegativeAccent ?: MaterialTheme.appColors.statusGood,
                                cornerLeftLabel = cornerLeftLabel,
                                cornerLeftValue = cornerLeftValue,
                                cornerRightLabel = cornerRightLabel,
                                cornerRightValue = cornerRightValue,
                                leftReservesSlot = slotStats.left == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                rightReservesSlot = slotStats.right == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                centerStatLabel = centerStatLabel,
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigateToMetric("CURRENT") },
                                onLongClick = { viewModel.toggleCurrentDisplayMode() }
                            )
                            "LOAD" -> LiveMetricTile(
                                label = stringResource(R.string.stat_load),
                                value = centerOverride ?: if (live) "%.0f%%".format(pwm) else placeholder,
                                accent = loadColor,
                                sparkData = history.load,
                                sparkStyle = spec?.sparkline ?: SparklineStyle.AREA,
                                sparklineEnabled = sparklineEnabled,
                                cornerLeftLabel = cornerLeftLabel,
                                cornerLeftValue = cornerLeftValue,
                                cornerRightLabel = cornerRightLabel,
                                cornerRightValue = cornerRightValue,
                                leftReservesSlot = slotStats.left == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                rightReservesSlot = slotStats.right == com.eried.eucplanet.ui.settings.DashboardStat.EMPTY,
                                centerStatLabel = centerStatLabel,
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigateToMetric("LOAD") }
                            )
                            "TRIP" -> LiveMetricTile(
                                label = stringResource(R.string.stat_trip),
                                value = if (live) "%.1f %s".format(tripValue, distUnit) else placeholder,
                                accent = metricBatteryColor,
                                sparkData = emptyList(),
                                sparkStyle = SparklineStyle.NONE,
                                sparklineEnabled = false,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val targetTripId = currentTripId ?: latestTripId
                                    if (targetTripId != null) onNavigateToTripDetail(targetTripId)
                                    else showNoTripsDialog = true
                                }
                            )
                            else -> when {
                                // Composite metric instance — render via
                                // the shared CompositeMetricBody so the
                                // live dashboard, the editor preview,
                                // and the pool pill all draw the tile
                                // the same way. Cell values come from
                                // displayValueFor so each sub-metric
                                // gets the same formatting it would in
                                // a standalone slot.
                                key.startsWith("M:") -> {
                                    val composite = compositeFor(key)
                                    // Tap-side detection: derive which
                                    // cell the rider hit so the History
                                    // popup opens on that cell's tab.
                                    // COL3 splits horizontally into
                                    // thirds, COL2 in halves, ROW2 splits
                                    // vertically. Tap position is the
                                    // only signal; long-press still goes
                                    // through the drag controller above.
                                    val allCells = remember(composite) {
                                        composite?.cells?.filter { it.isNotBlank() }.orEmpty()
                                    }
                                    val tappedCellIndex = remember { mutableStateOf(0) }
                                    val tileSize = remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                                    fun openTabsStartingAt(cellIdx: Int) {
                                        if (allCells.isEmpty()) {
                                            onNavigateToMetric(key)
                                            return
                                        }
                                        val safeIdx = cellIdx.coerceIn(0, allCells.lastIndex)
                                        // Keep the original cell order so
                                        // the History tab strip looks the
                                        // same whichever cell the rider
                                        // tapped. The "|<index>" suffix
                                        // tells the History screen which
                                        // tab to pre-select.
                                        val payload = allCells.joinToString(",") + "|" + safeIdx
                                        onNavigateToMetric(payload)
                                    }
                                    // Per-cell renderer: applies the
                                    // cell's chosen stat (Now / Min /
                                    // Max / Avg / percentiles) over the
                                    // matching history buffer when
                                    // available. CURRENT short-circuits
                                    // to displayValueFor for live wheel
                                    // values; computed stats render with
                                    // no unit suffix because the buffer
                                    // values are already in display
                                    // units (BATTERY %, etc.).
                                    val historySnapshot = history
                                    val cellRenderer: (String) -> String =
                                        cellRenderer@ { metricKey ->
                                            // Default path = whatever
                                            // the stat-less renderer
                                            // would have produced. The
                                            // body iterates by key so
                                            // we look up which cell
                                            // index this key occupies
                                            // and apply that cell's
                                            // selected stat.
                                            if (composite == null) return@cellRenderer displayValueFor(metricKey)
                                            val cellIdx = composite.cells.indexOf(metricKey)
                                            val stat = composite.cellStats.getOrNull(cellIdx)
                                                ?: com.eried.eucplanet.ui.settings.DashboardStat.CURRENT
                                            if (stat == com.eried.eucplanet.ui.settings.DashboardStat.CURRENT) {
                                                return@cellRenderer displayValueFor(metricKey)
                                            }
                                            val buf = when (metricKey) {
                                                "BATTERY" -> historySnapshot.battery
                                                "TEMPERATURE" -> historySnapshot.temperature
                                                "VOLTAGE" -> historySnapshot.voltage
                                                "CURRENT" -> historySnapshot.current
                                                "LOAD" -> historySnapshot.load
                                                "SPEED" -> historySnapshot.speed
                                                else -> emptyList()
                                            }
                                            if (buf.size < 2) return@cellRenderer placeholder
                                            val samples = buf.mapIndexed { idx, v ->
                                                com.eried.eucplanet.data.repository.MetricSample(idx.toLong(), v)
                                            }
                                            val value = com.eried.eucplanet.ui.settings.computeDashboardStatValue(
                                                stat, samples, fallbackCurrent = buf.last()
                                            ) ?: return@cellRenderer placeholder
                                            "%.1f".format(value)
                                        }
                                    // A composite always occupies one standard slot
                                    // (61 dp), exactly like every other dashboard tile.
                                    // A 2-cell ROW2 stacks its two reads WITHIN that
                                    // height rather than doubling to 122 dp -- the
                                    // double-height tile blew out the row it shared and
                                    // broke the front-of-app grid alignment.
                                    val compositeHeight = 61.dp
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(compositeHeight)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .onGloballyPositioned { coords ->
                                                tileSize.value = coords.size
                                            }
                                            .pointerInput(composite?.layout, allCells) {
                                                detectTapGestures { offset ->
                                                    val sz = tileSize.value
                                                    if (sz.width == 0 || sz.height == 0 || allCells.isEmpty()) {
                                                        openTabsStartingAt(0); return@detectTapGestures
                                                    }
                                                    val idx = when (composite?.layout) {
                                                        com.eried.eucplanet.ui.settings.CompositeLayout.COL3 -> {
                                                            val third = sz.width / 3f
                                                            (offset.x / third).toInt().coerceIn(0, 2)
                                                        }
                                                        com.eried.eucplanet.ui.settings.CompositeLayout.COL2 -> {
                                                            if (offset.x < sz.width / 2f) 0 else 1
                                                        }
                                                        com.eried.eucplanet.ui.settings.CompositeLayout.ROW2 -> {
                                                            if (offset.y < sz.height / 2f) 0 else 1
                                                        }
                                                        else -> 0
                                                    }
                                                    tappedCellIndex.value = idx
                                                    openTabsStartingAt(idx)
                                                }
                                            }
                                    ) {
                                        if (composite != null) {
                                            com.eried.eucplanet.ui.settings.CompositeMetricBody(
                                                composite = composite,
                                                valueOf = cellRenderer
                                            )
                                        }
                                    }
                                }
                                // Custom tile — rider's icon + text label.
                                key.startsWith("C:") -> {
                                    val tile = customTileFor(key)
                                    val ctxLocal = LocalContext.current
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(61.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                when (tile?.action) {
                                                    com.eried.eucplanet.ui.settings.CustomTileAction.OPEN_URL -> {
                                                        // Pass whatever the rider typed straight to the
                                                        // browser intent. Auto-prepend https:// only when
                                                        // the input clearly looks like a host (no scheme,
                                                        // not empty); a blank URL opens about:blank so a
                                                        // mis-configured tile lands the rider in a browser
                                                        // rather than silently navigating to history.
                                                        val raw = tile.url.trim()
                                                        val target = when {
                                                            raw.isEmpty() -> "about:blank"
                                                            raw.contains("://") -> raw
                                                            else -> "https://$raw"
                                                        }
                                                        runCatching {
                                                            ctxLocal.startActivity(
                                                                android.content.Intent(
                                                                    android.content.Intent.ACTION_VIEW,
                                                                    android.net.Uri.parse(target)
                                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            )
                                                        }
                                                    }
                                                    com.eried.eucplanet.ui.settings.CustomTileAction.SHOW_QR -> {
                                                        showQrForTile = tile
                                                    }
                                                    // NONE-action: tile is either a text note (show
                                                    // dialog with the text) or truly empty (toast).
                                                    // Used to silently navigate to MetricDetailScreen
                                                    // for a 'C:<uuid>' key which is nonsense; the
                                                    // tile owns its content here.
                                                    else -> {
                                                        val hasText = tile?.text?.isNotBlank() == true
                                                        if (hasText) {
                                                            showTextForTile = tile
                                                        } else {
                                                            snackbarScope.launch {
                                                                snackbar.showSnackbar(
                                                                    toastContext.getString(
                                                                        R.string.dashboard_custom_tile_empty_toast
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                    ) {
                                        if (tile != null) {
                                            com.eried.eucplanet.ui.settings.CustomTileBody(tile)
                                        }
                                    }
                                }
                                else -> {
                                    // Catalog-driven generic rendering for any
                                    // other static metric the rider drops into
                                    // a slot (BATTERY_1/2, MOTOR_TEMP,
                                    // CONTROLLER_TEMP, PHONE_BATTERY, DYN_*,
                                    // GPS_*, etc.). These tiles honour the
                                    // same per-slot corner stats as the
                                    // hardcoded 5 above — without this the
                                    // rider's Left/Right/Center pick from the
                                    // slot editor would silently vanish.
                                    // History buffers only exist for the six
                                    // sparkline-backed metrics; for everything
                                    // else cornerStatValueFor returns the
                                    // placeholder dash so the chip still
                                    // confirms the picked stat visually.
                                    val tileCtx = LocalContext.current
                                    val tileLabelOverrideId = remember(key) {
                                        tileCtx.resources.getIdentifier(
                                            "metric_chip_${key.lowercase()}_tile",
                                            "string",
                                            tileCtx.packageName
                                        )
                                    }
                                    LiveMetricTile(
                                        label = when {
                                            tileLabelOverrideId != 0 -> stringResource(tileLabelOverrideId)
                                            spec != null -> stringResource(spec.labelRes)
                                            else -> key
                                        },
                                        value = centerOverride ?: displayValueFor(key),
                                        accent = spec?.accent?.let { MaterialTheme.appColors.remap(it) } ?: primary,
                                        sparkData = emptyList(),
                                        sparkStyle = spec?.sparkline ?: SparklineStyle.NONE,
                                        sparklineEnabled = sparklineEnabled,
                                        bipolarBaseline = spec?.bipolarBaseline ?: 0f,
                                        bipolarNegativeAccent = spec?.bipolarNegativeAccent,
                                        cornerLeftLabel = cornerLeftLabel,
                                        cornerLeftValue = cornerLeftValue,
                                        cornerRightLabel = cornerRightLabel,
                                        cornerRightValue = cornerRightValue,
                                        centerStatLabel = centerStatLabel,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onNavigateToMetric(key) }
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowIdx < metricRows - 1) Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))

            // Action buttons, fixed height on both phone and wide layouts so the
            // bottom rows always fit even with the taller speedometer and stat cards.
            val actionAspect: Float? = null
            val actionHeight: Int? = if (wideStats) 88 else 104

            // Read the rider's customized action order. Falls back to the
            // catalog defaults (HORN / LIGHT / VOICE / SAFETY / LOCK /
            // RECORD) when blank — out-of-box layout stays byte-identical
            // to the old hardcoded 6-button arrangement.
            val activeActionKeys = remember(dashboardActionOrderRaw) {
                val parsed = dashboardActionOrderRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val defaults = listOf(
                    "HORN", "LIGHT_TOGGLE", "VOICE_ANNOUNCE",
                    "SAFETY_TOGGLE", "LOCK_TOGGLE", "RECORD_TOGGLE"
                )
                (parsed + defaults).distinct().take(6)
            }
            val periodicVoiceOn by viewModel.voicePeriodicEnabled.collectAsState()
            val lockAtAnySpeed by viewModel.cheatState.lockAtAnySpeed.collectAsState()
            val lockBlockedBySpeed = !locked && kotlin.math.abs(wheelData.speed) >= 5f && !lockAtAnySpeed
            val wheelHasLock by viewModel.wheelHasLock.collectAsState()

            // Two rows of 3 — match today's layout. Tablets (wideStats)
            // and phones both render 3 columns; only the height changes.
            for (rowIdx in 0 until 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Spotlight ALL action buttons: each row unions into one
                        // rect for the welcome tour.
                        .coachmarkTargetUnion(coachmark, TutorialTarget.ACTIONS),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (colIdx in 0 until 3) {
                        val slotIdx = rowIdx * 3 + colIdx
                        val rawKey = activeActionKeys.getOrNull(slotIdx)
                        // Same EMPTY_SLOT_KEY handling as the metric grid.
                        val key = if (rawKey == com.eried.eucplanet.ui.settings.EMPTY_SLOT_KEY) null else rawKey
                        if (key == null) {
                            Box(modifier = Modifier.weight(1f))
                            continue
                        }
                        // Per-key bridge: today's 6 defaults preserve
                        // every quirk (active highlights, submenus,
                        // lock-in-motion toast, trip-count label, etc.).
                        // Any other key the rider drops in renders as a
                        // catalog-driven generic tile that fires through
                        // FlicManager via the standard onAction handler.
                        when (key) {
                            "HORN" -> ActionButton(
                                Icons.Default.Campaign,
                                stringResource(R.string.action_horn),
                                enabled = connectionState == ConnectionState.CONNECTED,
                                onClick = { viewModel.onHornPress() },
                                modifier = Modifier.weight(1f),
                                aspectRatio = actionAspect, heightDp = actionHeight
                            )
                            "LIGHT_TOGGLE" -> ActionTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.FlashlightOn,
                                label = stringResource(R.string.action_light),
                                active = wheelData.lightOn,
                                activeColor = if (useAccent) primary else MaterialTheme.appColors.gaugeWarn,
                                enabled = connectionState == ConnectionState.CONNECTED && !lightBusy,
                                onClick = { viewModel.onLightToggle() },
                                aspectRatio = actionAspect, heightDp = actionHeight,
                                menu = { dismiss ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_auto_lights)) },
                                        onClick = { dismiss(); onNavigateToSettings(6) }
                                    )
                                }
                            )
                            "VOICE_ANNOUNCE" -> ActionTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.RecordVoiceOver,
                                label = stringResource(R.string.action_voice),
                                onClick = { viewModel.onVoiceAnnounce() },
                                aspectRatio = actionAspect, heightDp = actionHeight,
                                menu = { dismiss ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_voice)) },
                                        onClick = { dismiss(); onNavigateToSettings(3) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.tab_alarms)) },
                                        onClick = { dismiss(); onNavigateToSettings(5) }
                                    )
                                    androidx.compose.material3.HorizontalDivider(
                                        color = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (periodicVoiceOn) stringResource(R.string.menu_voice_periodic_off)
                                                else stringResource(R.string.menu_voice_periodic_on)
                                            )
                                        },
                                        onClick = { dismiss(); viewModel.toggleVoicePeriodic() }
                                    )
                                }
                            )
                            "SAFETY_TOGGLE" -> ActionTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Shield,
                                label = if (safetyActive) stringResource(R.string.action_legal_on)
                                    else stringResource(R.string.action_legal_mode),
                                active = safetyActive,
                                activeColor = if (useAccent) primary else MaterialTheme.appColors.statusWarn,
                                enabled = connectionState == ConnectionState.CONNECTED,
                                onClick = { viewModel.onSafetySpeedToggle() },
                                aspectRatio = actionAspect, heightDp = actionHeight,
                                menu = { dismiss ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.section_legal_mode_speed)) },
                                        onClick = { dismiss(); onNavigateToSettings(2) }
                                    )
                                }
                            )
                            "LOCK_TOGGLE" -> ActionButton(
                                if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                if (locked) stringResource(R.string.action_locked)
                                    else stringResource(R.string.action_lock_wheel),
                                active = locked,
                                activeColor = if (useAccent) primary else MaterialTheme.appColors.statusDanger,
                                enabled = connectionState == ConnectionState.CONNECTED && !lockBusy && wheelHasLock,
                                onClick = {
                                    if (lockBlockedBySpeed) {
                                        val msg = toastContext.getString(R.string.lock_blocked_in_motion_toast)
                                        snackbarScope.launch { snackbar.showSnackbar(msg) }
                                    } else {
                                        viewModel.onLockToggle()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                aspectRatio = actionAspect, heightDp = actionHeight
                            )
                            "RECORD_TOGGLE" -> ActionTile(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.FiberManualRecord,
                                label = when {
                                    tripCount == 1 -> stringResource(R.string.action_recorder_trips_one, tripCount)
                                    tripCount > 1 -> stringResource(R.string.action_recorder_trips, tripCount)
                                    else -> stringResource(R.string.action_recorder)
                                },
                                active = recording,
                                activeColor = if (useAccent) primary else MaterialTheme.appColors.statusDanger,
                                onClick = { onNavigateToRecording() },
                                aspectRatio = actionAspect, heightDp = actionHeight,
                                menu = { dismiss ->
                                    val targetTripId = currentTripId ?: latestTripId
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_trip_backup_options)) },
                                        onClick = { dismiss(); onNavigateToSettings(4) }
                                    )
                                    androidx.compose.material3.HorizontalDivider(
                                        color = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (recording) stringResource(R.string.menu_view_current_trip)
                                                else stringResource(R.string.menu_view_last_trip)
                                            )
                                        },
                                        enabled = targetTripId != null,
                                        onClick = {
                                            dismiss()
                                            targetTripId?.let { onNavigateToTripDetail(it) }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (recording) stringResource(R.string.menu_stop_recording)
                                                else stringResource(R.string.menu_start_recording)
                                            )
                                        },
                                        onClick = {
                                            dismiss()
                                            if (recording) viewModel.stopRecording()
                                            else viewModel.startRecording()
                                        }
                                    )
                                }
                            )
                            else -> when {
                                // Action group instance (G:uuid). Reads
                                // the rider's chosen name + icon from
                                // dashboardActionGroupsJson. Tapping a
                                // group should open a popover with its
                                // sub-actions (matches the editor's
                                // preview), but the popover machinery
                                // lives in the settings module; for
                                // now the tap is a no-op so the rider
                                // still sees a recognisable tile.
                                key.startsWith("B:") -> {
                                    // Custom BLE command instance (B:uuid).
                                    // Reads the rider's label + icon from
                                    // dashboardCustomBleJson; tap dispatches
                                    // through the shared FlicManager path,
                                    // which family-gates the raw frames.
                                    val parsedBle = remember(dashboardCustomBleJson, key) {
                                        try {
                                            val root = org.json.JSONObject(
                                                dashboardCustomBleJson.ifBlank { "{}" }
                                            )
                                            val node = root.optJSONObject(key)
                                            val lbl = node?.optString("label", "") ?: ""
                                            val ic = node?.optString("icon", "BOLT") ?: "BOLT"
                                            Pair(lbl, ic)
                                        } catch (_: Exception) {
                                            Pair("", "BOLT")
                                        }
                                    }
                                    val (bleLabel, bleIcon) = parsedBle
                                    val bleDefault = stringResource(
                                        R.string.dashboard_custom_ble_default_name
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        ActionButton(
                                            icon = com.eried.eucplanet.ui.settings.groupIconFor(
                                                bleIcon.ifBlank { "BOLT" }
                                            ),
                                            label = bleLabel.ifBlank { bleDefault },
                                            enabled = true,
                                            onClick = { viewModel.dispatchActionByName(key) },
                                            modifier = Modifier.fillMaxWidth(),
                                            aspectRatio = actionAspect, heightDp = actionHeight
                                        )
                                    }
                                }
                                key.startsWith("G:") -> {
                                    // Parse the group definition once per
                                    // change to the groups JSON. Name +
                                    // icon drive the tile face; the
                                    // actions list drives the popover.
                                    val parsedGroup = remember(dashboardActionGroupsJson, key) {
                                        try {
                                            val root = org.json.JSONObject(
                                                dashboardActionGroupsJson.ifBlank { "{}" }
                                            )
                                            val node = root.optJSONObject(key)
                                            val name = node?.optString("name", "") ?: ""
                                            val iconKey = node?.optString("icon", "") ?: ""
                                            val actionsArr = node?.optJSONArray("actions")
                                            val actions = if (actionsArr != null) {
                                                (0 until actionsArr.length()).mapNotNull {
                                                    actionsArr.optString(it).takeIf { s -> s.isNotEmpty() }
                                                }
                                            } else emptyList()
                                            Triple(name, iconKey, actions)
                                        } catch (_: Exception) {
                                            Triple("", "", emptyList<String>())
                                        }
                                    }
                                    val (groupName, groupIcon, groupActions) = parsedGroup
                                    val defaultGroupLabel = stringResource(
                                        R.string.dashboard_group_default_name
                                    )
                                    // Capture the anchor tile's measured
                                    // size so the popover can render its
                                    // sub-action buttons at exactly the
                                    // same width and height as the
                                    // dashboard action row.
                                    var anchorSizePx by remember {
                                        mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .onGloballyPositioned { coords ->
                                                anchorSizePx = coords.size
                                            }
                                    ) {
                                        var popoverOpen by remember { mutableStateOf(false) }
                                        ActionButton(
                                            icon = if (groupIcon.isNotBlank())
                                                com.eried.eucplanet.ui.settings.groupIconFor(groupIcon)
                                            else Icons.Default.Campaign,
                                            label = groupName.ifBlank { defaultGroupLabel },
                                            enabled = true,
                                            onClick = {
                                                if (groupActions.isNotEmpty()) {
                                                    popoverOpen = true
                                                } else {
                                                    // Empty group used to silently no-op, which
                                                    // looked like a broken tile. Now an explicit
                                                    // Snackbar confirms the tap landed and tells
                                                    // the rider why nothing happened.
                                                    snackbarScope.launch {
                                                        snackbar.showSnackbar(
                                                            toastContext.getString(
                                                                R.string.dashboard_group_empty_toast
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            aspectRatio = actionAspect, heightDp = actionHeight
                                        )
                                        if (popoverOpen) {
                                            ActionGroupPopover(
                                                actions = groupActions,
                                                anchorSizePx = anchorSizePx,
                                                onPick = { subKey ->
                                                    viewModel.dispatchActionByName(subKey)
                                                    popoverOpen = false
                                                },
                                                onDismiss = { popoverOpen = false }
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    // Catalog-driven generic action. Dashboard-only
                                    // actions (OPEN_*, unit toggle, alarms-mute,
                                    // reset-trip) need handles the FlicManager
                                    // service doesn't have (navController, snackbar,
                                    // settings writer); those are supplied via the
                                    // ActionUi below. Everything else falls through
                                    // to the shared physical-surface dispatch. This
                                    // is the same ActionUi the service-mode overlay
                                    // builds, so both surfaces fire the full catalog.
                                    val actionSpec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key)
                                    val labelText = actionSpec?.let { stringResource(it.labelRes) } ?: key
                                    val tap: () -> Unit = {
                                        com.eried.eucplanet.data.model.dispatchAction(
                                            key,
                                            ui = object : com.eried.eucplanet.data.model.ActionUi {
                                                override fun openNavigation() = onNavigateToNavigator()
                                                override fun openStudio() = onNavigateToStudio()
                                                override fun openAbout() { showAboutDialog = true }
                                                override fun openService() { showDiagnosticsDialog = true }
                                                override fun openTrips() = onNavigateToRecording()
                                                override fun toggleUnits() {
                                                    viewModel.toggleUnits()
                                                    snackbarScope.launch {
                                                        snackbar.showSnackbar(
                                                            toastContext.getString(R.string.action_chip_toggle_units)
                                                        )
                                                    }
                                                }
                                                override fun toggleAlarmsMuted() { viewModel.toggleAlarmsMuted() }
                                                override fun resetTrip() {
                                                    snackbarScope.launch {
                                                        val ok = viewModel.resetWheelTrip()
                                                        snackbar.showSnackbar(
                                                            toastContext.getString(
                                                                if (ok) R.string.action_chip_reset_trip
                                                                else R.string.action_unsupported_on_wheel
                                                            )
                                                        )
                                                    }
                                                }
                                            },
                                            fallback = { viewModel.dispatchActionByName(it) }
                                        )
                                    }
                                    val offlineSafe = key.startsWith("OPEN_") ||
                                            key == "TOGGLE_UNITS" || key == "MUTE_ALARMS" ||
                                            key.startsWith("MEDIA_")
                                    ActionButton(
                                        icon = actionSpec?.icon ?: Icons.Default.Campaign,
                                        label = labelText,
                                        enabled = connectionState == ConnectionState.CONNECTED || offlineSafe,
                                        onClick = tap,
                                        modifier = Modifier.weight(1f),
                                        aspectRatio = actionAspect, heightDp = actionHeight
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowIdx == 0) Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))

            // Bottom info row: ODO + wheel model + firmware + version (tap for About)
            // (showAboutDialog / showDiagnosticsDialog hoisted to the top of
            // the screen so OPEN_ABOUT action bindings can trigger them.)
            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
            }
            val versionRevision = remember {
                try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                } catch (_: Exception) { 0 }
            }
            val diagEnabled by com.eried.eucplanet.diagnostics.DiagnosticsLogger.enabled.collectAsState()
            WheelInfoBox(
                odoKm = wheelData.totalDistance,
                distanceUnit = distanceUnit,
                firmwareVersion = firmwareVersion,
                versionName = if (versionRevision > 0) "$versionName-$versionRevision" else versionName,
                diagnosticsActive = diagEnabled,
                onVersionClick = {
                    if (diagEnabled) showDiagnosticsDialog = true
                    else showAboutDialog = true
                },
                // Spotlight only the version (right side), not the whole ODO row.
                versionModifier = Modifier.coachmarkTarget(coachmark, TutorialTarget.VERSION)
            )

            if (showDiagnosticsDialog) {
                com.eried.eucplanet.diagnostics.WheelDiagnosticsDialog(
                    onDismiss = { showDiagnosticsDialog = false }
                )
            }

            // Custom-tile text-display dialog. NONE-action tiles with a
            // non-blank text show their full note in a read-only text box
            // so the rider can read content longer than the truncated
            // tile face. Same dismiss/confirm shape as the QR dialog
            // below so the two interactions feel like siblings.
            showTextForTile?.let { textTile ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showTextForTile = null },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showTextForTile = null }
                        ) { Text(stringResource(R.string.action_ok)) }
                    },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = textTile.text,
                            onValueChange = {},
                            readOnly = true,
                            // Allow multi-line; no maxLines cap so the
                            // whole note is visible — the dialog grows
                            // with content (Material caps the dialog
                            // height itself so it can't run off-screen).
                            modifier = Modifier.fillMaxWidth(),
                            colors = themedFieldColors(),
                        )
                    }
                )
            }

            // Custom-tile SHOW_QR action: render the URL as a centred QR
            // code in an AlertDialog so the rider can quickly hand the
            // phone to a friend or scan it themselves with a separate
            // device. Dismiss-on-outside-tap keeps it lightweight.
            showQrForTile?.let { qrTile ->
                val raw = qrTile.url.trim()
                val display = if (raw.contains("://")) raw else "https://$raw"
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showQrForTile = null },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showQrForTile = null }
                        ) { Text(stringResource(R.string.action_ok)) }
                    },
                    // Title only renders when the rider gave the tile a label;
                    // we never leak the raw URL into the dialog header so the
                    // QR is the only thing competing for attention.
                    title = if (qrTile.text.isNotBlank()) {
                        { Text(qrTile.text) }
                    } else null,
                    text = {
                        QrCodeImage(content = display, sizeDp = 240)
                    }
                )
            }

            if (showSourcesSheet) {
                com.eried.eucplanet.ui.dashboard.sources.DataSourcesSheet(
                    speedUnit = speedUnit,
                    onDismiss = { showSourcesSheet = false }
                )
            }

            if (showAboutDialog) {
                var crashes by remember {
                    mutableStateOf(com.eried.eucplanet.util.CrashHandler.listCrashes(context))
                }
                var crashMenuFor by remember { mutableStateOf<java.io.File?>(null) }
                var confirmDeleteAllCrashes by remember { mutableStateOf(false) }
                val licenseText = remember {
                    try {
                        val raw = context.resources.openRawResource(R.raw.license)
                            .bufferedReader().use { it.readText() }
                        // LICENSE files are hard-wrapped at ~78 columns, which
                        // renders as ragged lines on a phone. Re-flow each
                        // paragraph into a single line (collapse single \n to
                        // space) while keeping blank-line paragraph breaks.
                        raw.split(Regex("\\r?\\n\\s*\\r?\\n"))
                            .joinToString("\n\n") { paragraph ->
                                paragraph
                                    .replace(Regex("\\r?\\n"), " ")
                                    .replace(Regex(" +"), " ")
                                    .trim()
                            }
                    } catch (_: Exception) { "" }
                }
                var logoPressed by remember { mutableStateOf(false) }
                val logoAlpha by animateFloatAsState(
                    targetValue = if (logoPressed) 0.55f else 1f,
                    animationSpec = tween(durationMillis = 200),
                    label = "logoHoldAlpha"
                )
                Dialog(
                    onDismissRequest = { showAboutDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .heightIn(max = 820.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                      // Debug builds get a big translucent "DEBUG" stamped
                      // over the About dialog so the rider can tell at a
                      // glance whether they're on a debug APK (signed with
                      // the debug key, no Play store) vs the release one.
                      // Drawn last so it floats above every tab's content.
                      Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(colorResource(R.color.ic_launcher_background))
                                    .align(Alignment.CenterHorizontally)
                                    .alpha(logoAlpha)
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                                            logoPressed = true
                                            val pointerId = firstDown.id
                                            val deadline = System.currentTimeMillis() + 3000L
                                            // Poll pointer events while bounding each wait by the
                                            // remaining time. Three exit conditions:
                                            //  - 3s elapses with the finger still down inside the
                                            //    logo bounds  -> trigger Service Mode
                                            //  - user lifts the finger early                 -> abort
                                            //  - user drags the finger off the logo          -> abort
                                            var triggered = false
                                            while (true) {
                                                val remaining = deadline - System.currentTimeMillis()
                                                if (remaining <= 0L) {
                                                    triggered = true
                                                    break
                                                }
                                                val event = withTimeoutOrNull(remaining) {
                                                    awaitPointerEvent()
                                                }
                                                if (event == null) {
                                                    // No further event in the remaining window:
                                                    // the user is still pressing, so this is a
                                                    // successful hold.
                                                    triggered = true
                                                    break
                                                }
                                                val change = event.changes.firstOrNull { it.id == pointerId }
                                                if (change == null || !change.pressed) {
                                                    // Lifted (or another pointer took over).
                                                    break
                                                }
                                                val pos = change.position
                                                if (pos.x !in 0f..size.width.toFloat() ||
                                                    pos.y !in 0f..size.height.toFloat()
                                                ) {
                                                    // Dragged outside the logo circle.
                                                    break
                                                }
                                            }
                                            logoPressed = false
                                            if (triggered) {
                                                showDiagnosticsConfirm = true
                                            }
                                        }
                                    }
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "EUC Planet",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Text(
                                buildString {
                                    append("v")
                                    append(versionName)
                                    if (versionRevision > 0) {
                                        append("-")
                                        append(versionRevision)
                                    }
                                    // Branch tag, baked in at build time. Hidden
                                    // for main / detached HEAD / unknown.
                                    val branch = com.eried.eucplanet.BuildConfig.GIT_BRANCH
                                    if (branch.isNotEmpty() && branch != "main" && branch != "HEAD") {
                                        append(" (")
                                        append(branch)
                                        append(")")
                                    }
                                    append(" · ")
                                    append(com.eried.eucplanet.BuildConfig.BUILD_STAMP)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Text(
                                "eucplanet.ried.no",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clickable { openUrl(context, "https://eucplanet.ried.no") }
                            )
                            Spacer(Modifier.height(16.dp))

                            Text(
                                "A no-nonsense, open-source app for electric unicycles.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.CenterHorizontally)
                            )

                            Spacer(Modifier.height(12.dp))

                            var aboutTab by remember { mutableStateOf(0) }
                            TabRow(
                                selectedTabIndex = aboutTab,
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                // Material 3 Tab applies internal horizontal
                                // padding around the text slot; with the count
                                // suffix the slot measures narrower than the
                                // text and clips on the right even when the
                                // tab visually has room. wrapContentWidth with
                                // unbounded = true lets the Text report its
                                // intrinsic width so the label renders fully.
                                val unbounded = Modifier.wrapContentWidth(unbounded = true)
                                Tab(
                                    selected = aboutTab == 0,
                                    onClick = { aboutTab = 0 },
                                    text = {
                                        Text(
                                            stringResource(R.string.about_thanks),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = unbounded
                                        )
                                    }
                                )
                                Tab(
                                    selected = aboutTab == 1,
                                    onClick = { aboutTab = 1 },
                                    text = {
                                        Text(
                                            stringResource(R.string.about_license),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = unbounded
                                        )
                                    }
                                )
                                Tab(
                                    selected = aboutTab == 2,
                                    onClick = { aboutTab = 2 },
                                    text = {
                                        Text(
                                            if (crashes.isEmpty())
                                                stringResource(R.string.about_crash_logs)
                                            else
                                                "${stringResource(R.string.about_crash_logs)} (${crashes.size})",
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = unbounded
                                        )
                                    }
                                )
                            }

                            Box(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                                when (aboutTab) {
                                    0 -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(10.dp)
                                            ) {
                                                Row {
                                                    Text(
                                                        "Made by ",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        "Erwin Ried",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.clickable {
                                                            openUrl(context, "https://ried.no")
                                                        }
                                                    )
                                                    Text(
                                                        " in Norway",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    stringResource(R.string.about_thanks_body),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.height(12.dp))
                                                androidx.compose.material3.HorizontalDivider(
                                                    color = MaterialTheme.appColors.divider.copy(alpha = 0.4f),
                                                    thickness = 1.dp
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    "Thanks to:",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                // Credits table. Two columns, name on the left,
                                                // why on the right, no headers since the format
                                                // is self-evident. Hardcoded English because the
                                                // entries are proper nouns + short context.
                                                val credits = listOf(
                                                    "Gio (Wheel In Motion)" to "Promotion, suggestions and P6 testing. Stitched scalp, intact enthusiasm.",
                                                    "FlyboyEUC (Adam)" to "Mten3, E20 and EX30 testing.",
                                                    "Soolek" to "KS-16X testing.",
                                                    "Jonathan Wiesner" to "LeaperKim Lynx S testing.",
                                                    "Felix K" to "LeaperKim Oryx testing.",
                                                    "Ilya Shkolnik" to "Advice and help, and maintains DarknessBot.",
                                                    "InMotion" to "For making my awesome V14."
                                                )
                                                val dividerColor = MaterialTheme.appColors.divider.copy(alpha = 0.2f)
                                                credits.forEachIndexed { idx, (name, why) ->
                                                    if (idx > 0) {
                                                        androidx.compose.material3.HorizontalDivider(
                                                            color = dividerColor,
                                                            thickness = 0.5.dp
                                                        )
                                                    }
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp)
                                                    ) {
                                                        Text(
                                                            name,
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                fontWeight = FontWeight.SemiBold
                                                            ),
                                                            modifier = Modifier.weight(0.42f),
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            why,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            modifier = Modifier.weight(0.58f),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.height(14.dp))
                                                androidx.compose.material3.HorizontalDivider(
                                                    color = MaterialTheme.appColors.divider.copy(alpha = 0.4f),
                                                    thickness = 1.dp
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    "Resources & libraries:",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(6.dp))
                                                val resources = listOf(
                                                    "BigSoundBank, engine samples" to "Joseph SARDIN. CC0 / public domain. All sampled engines in the Motor sound generator (V8 Cobra, V-twin Ducati, diesel truck, motorcycle, city car, helicopter, tractor, lawn mower, steam locomotive, Aston Martin, big diesel, car cruise, broken exhaust, quad ATV).",
                                                    "Jetpack Compose, Material 3" to "Google. Apache 2.0. UI toolkit and design system.",
                                                    "Hilt, Room, WorkManager, Navigation" to "Google. Apache 2.0. DI, persistence, background jobs, navigation graph.",
                                                    "Kotlin & coroutines" to "JetBrains. Apache 2.0. Language and structured concurrency.",
                                                    "Flic2 SDK" to "Shortcut Labs. Used for hardware Flic button integration.",
                                                    "Play services (location, wearable)" to "Google. Apache 2.0. GPS and watch companion data layer."
                                                )
                                                resources.forEachIndexed { idx, (name, why) ->
                                                    if (idx > 0) {
                                                        androidx.compose.material3.HorizontalDivider(
                                                            color = dividerColor,
                                                            thickness = 0.5.dp
                                                        )
                                                    }
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp)
                                                    ) {
                                                        Text(
                                                            name,
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                fontWeight = FontWeight.SemiBold
                                                            ),
                                                            modifier = Modifier.weight(0.42f),
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            why,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            modifier = Modifier.weight(0.58f),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Text(
                                                if (licenseText.isNotBlank()) licenseText else "--",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Justify,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(10.dp)
                                            )
                                        }
                                    }
                                    2 -> {
                                        if (crashes.isEmpty()) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    stringResource(R.string.about_crash_logs_empty),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                crashes.forEach { file ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .combinedClickable(
                                                                onClick = { shareCrashFile(context, file) },
                                                                onLongClick = { crashMenuFor = file }
                                                            )
                                                            .padding(vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.BugReport,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Text(
                                                            file.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showAboutDialog = false }) {
                                    Text(stringResource(R.string.action_ok))
                                }
                            }
                        }
                        if (com.eried.eucplanet.BuildConfig.DEBUG) {
                            // "DEV" stamped giant over the About panel for
                            // debug builds -- shorter than "DEBUG" so we can
                            // make it BIGGER without wrapping on a phone-
                            // width dialog. 40 % alpha so it doesn't obscure
                            // the text underneath.
                            Text(
                                "DEV",
                                fontSize = 180.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.Red.copy(alpha = 0.40f),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                      }  // Box (debug-overlay wrapper)
                    }
                    crashMenuFor?.let { target ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { crashMenuFor = null },
                            title = { Text(stringResource(R.string.about_crash_logs)) },
                            text = {
                                Text(stringResource(R.string.crash_log_action_prompt, target.name))
                            },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        runCatching { target.delete() }
                                        crashes = com.eried.eucplanet.util.CrashHandler.listCrashes(context)
                                        crashMenuFor = null
                                    }) { Text(stringResource(R.string.action_delete)) }
                                    TextButton(onClick = { confirmDeleteAllCrashes = true }) {
                                        Text(stringResource(R.string.crash_log_delete_all))
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { crashMenuFor = null }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                        )
                    }
                    if (confirmDeleteAllCrashes) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmDeleteAllCrashes = false },
                            title = { Text(stringResource(R.string.crash_log_delete_all)) },
                            text = { Text(stringResource(R.string.crash_log_delete_all_warning)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    crashes.forEach { runCatching { it.delete() } }
                                    crashes = com.eried.eucplanet.util.CrashHandler.listCrashes(context)
                                    confirmDeleteAllCrashes = false
                                    crashMenuFor = null
                                }) { Text(stringResource(R.string.action_delete)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDeleteAllCrashes = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                        )
                    }
                }
                if (showDiagnosticsConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDiagnosticsConfirm = false },
                        title = { Text(stringResource(R.string.service_mode_title)) },
                        text = {
                            Column {
                                Text(
                                    stringResource(R.string.service_mode_caution),
                                    color = MaterialTheme.appColors.statusDanger,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.service_mode_purpose),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.service_mode_recording),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(12.dp))
                                val linkText = stringResource(R.string.service_mode_link)
                                val urlPart = "eucplanet.ried.no/service"
                                val linkColor = MaterialTheme.colorScheme.primary
                                val annotated = buildAnnotatedString {
                                    val idx = linkText.indexOf(urlPart)
                                    if (idx >= 0) {
                                        append(linkText.substring(0, idx))
                                        withLink(
                                            LinkAnnotation.Url(
                                                url = "https://$urlPart",
                                                styles = TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = linkColor,
                                                        textDecoration = TextDecoration.Underline
                                                    )
                                                )
                                            )
                                        ) { append(urlPart) }
                                        append(linkText.substring(idx + urlPart.length))
                                    } else {
                                        append(linkText)
                                    }
                                }
                                Text(
                                    annotated,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDiagnosticsConfirm = false
                                com.eried.eucplanet.diagnostics.DiagnosticsLogger.enable()
                                showAboutDialog = false
                                showDiagnosticsDialog = true
                            }) { Text(stringResource(R.string.service_mode_enter)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDiagnosticsConfirm = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }
            }
            }  // close inner Column (dashboard content)
        }
    }

        // Welcome tour overlay -- drawn last so it sits above the dashboard
        // (and the top bar) and can spotlight any element by its bounds.
        val wizardVoiceOn by viewModel.voicePeriodicEnabled.collectAsState()
        // Dev-only wizard tools (branch builds): reuse the Cloud-settings folder
        // picker + restore dialog so there is one implementation.
        val wizardBackupFolderSet by viewModel.backupFolderSet.collectAsState()
        val wizardHasSettingsBackup by viewModel.hasSettingsBackup.collectAsState()
        var showWizardRestore by remember { mutableStateOf(false) }
        val wizardPickFolder = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri -> if (uri != null) viewModel.setBackupFolder(uri) }
        if (showWelcomeTour) {
            WelcomeTutorialOverlay(
                state = coachmark,
                onVoiceToggle = { on -> viewModel.setAllVoiceAnnouncements(on) },
                // Reflect the real voice state so the switch can't show "off"
                // while announcements are actually on.
                voiceCurrentlyOn = wizardVoiceOn,
                isDev = com.eried.eucplanet.BuildConfig.IS_DEV,
                onSetBackupFolder = { wizardPickFolder.launch(null) },
                backupFolderSet = wizardBackupFolderSet,
                hasSettingsBackup = wizardHasSettingsBackup,
                onRestoreSettings = { showWizardRestore = true },
                onJoinLeaderboards = { viewModel.joinLeaderboards() },
                onSyncTrips = { viewModel.syncAllTrips() },
            ) {
                viewModel.markWelcomeTutorialSeen()
                tourDismissed = true
            }
        }
        if (showWizardRestore) {
            com.eried.eucplanet.ui.settings.RestorePickerDialog(
                onDismiss = { showWizardRestore = false },
                loadEntries = { viewModel.listSettingsBackups() },
                onPicked = { entry ->
                    showWizardRestore = false
                    if (entry.isFactory) viewModel.restoreFactoryDefaults()
                    else viewModel.restoreSettingsFrom(entry.fileName)
                },
            )
        }
        }  // close CompositionLocalProvider
    }
}

// --- Speed gauge: thick arc dial, no needle, centered speed ---

@Composable
private fun SpeedGauge(
    speed: Float,
    maxSpeed: Float,
    speedUnit: String,
    overrideColor: Color? = null,
    showColorBand: Boolean = false,
    orangeThresholdPct: Int = 65,
    redThresholdPct: Int = 85,
    safeBandColor: Color = MaterialTheme.appColors.gaugeFill,
    /** External GPS speed in km/h, or null when no external GPS box is connected.
     *  Drives a small marker on the dial and a smaller readout under the main number,
     *  both in [externalAccentColor]. */
    externalSpeed: Float? = null,
    externalAccentColor: Color = MaterialTheme.appColors.metricPosition,
    modifier: Modifier = Modifier
) {
    // Speed-arc + speed-number colour rule (phone & watch share this rule):
    //  - Color band ON  → band tier wins (safe / orange / red), even if the user
    //    has a custom accent, the band is a safety signal, not a style choice.
    //  - Color band OFF → custom accent wins (overrideColor), else stay safe-green.
    val orangeFrac = (orangeThresholdPct / 100f).coerceIn(0.05f, 0.95f)
    val redFrac = (redThresholdPct / 100f).coerceIn(orangeFrac + 0.04f, 0.95f)
    val speedFraction = (speed / maxSpeed).coerceIn(0f, 1f)
    // Gauge band tier colors. Captured into vals here (composable scope) so the
    // Canvas DrawScope below — which can't read MaterialTheme — can still use them.
    // The "orange" approaching tier maps to statusWarn (defaults to AccentOrange,
    // pixel-identical); the "red" tier maps to gaugeDanger.
    val bandWarnColor = MaterialTheme.appColors.statusWarn
    val bandDangerColor = MaterialTheme.appColors.gaugeDanger
    val speedColor = when {
        showColorBand && speedFraction >= redFrac    -> bandDangerColor
        showColorBand && speedFraction >= orangeFrac -> bandWarnColor
        showColorBand                                -> safeBandColor
        overrideColor != null                        -> overrideColor
        else                                          -> safeBandColor
    }
    val trackColor = MaterialTheme.appColors.gaugeTrack
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    val displaySpeed = com.eried.eucplanet.util.Units.speed(speed, speedUnit)
    val displayMax = com.eried.eucplanet.util.Units.speed(maxSpeed, speedUnit)
    val maxInt = displayMax.toInt()
    val step = (maxInt / 3f).toInt().coerceAtLeast(5)
    val scaleLabels = listOf(0, step, step * 2, maxInt)
    Canvas(modifier = modifier) {
        val dim = size.minDimension
        val arcThickness = dim * 0.07f
        val arcRadius = dim / 2f - arcThickness - dim * 0.06f
        val center = Offset(size.width / 2f, size.height * 0.52f)

        val startAngle = 140f
        val sweepTotal = 260f
        // speedFraction is computed at the top of the composable now (for speedColor),
        // so the band-drawing branch reuses it.
        val speedSweep = sweepTotal * speedFraction

        // Background arc
        drawArc(
            color = trackColor,
            startAngle = startAngle, sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = arcThickness, cap = StrokeCap.Round)
        )

        // Thin color band behind the arc: safe (accent/blue) > orange > red.
        if (showColorBand) {
            val bandThickness = arcThickness * 0.35f
            val bandRadius = arcRadius + arcThickness * 0.55f + bandThickness * 0.5f
            val bandAlpha = 0.65f
            // orangeFrac / redFrac are computed at the top of the composable (for
            // speedColor), reuse so the band and number can never drift apart.
            val orangeStart = startAngle + sweepTotal * orangeFrac
            val orangeSweep = sweepTotal * (redFrac - orangeFrac)
            val redStart = startAngle + sweepTotal * redFrac
            val redSweep = sweepTotal * (1f - redFrac)
            val bandTopLeft = Offset(center.x - bandRadius, center.y - bandRadius)
            val bandSize = Size(bandRadius * 2, bandRadius * 2)
            // Safe zone: entire arc from 0 up to the orange handle.
            drawArc(color = safeBandColor.copy(alpha = bandAlpha),
                startAngle = startAngle, sweepAngle = sweepTotal * orangeFrac,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = bandWarnColor.copy(alpha = bandAlpha),
                startAngle = orangeStart, sweepAngle = orangeSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
            drawArc(color = bandDangerColor.copy(alpha = bandAlpha),
                startAngle = redStart, sweepAngle = redSweep,
                useCenter = false, topLeft = bandTopLeft, size = bandSize,
                style = Stroke(width = bandThickness, cap = StrokeCap.Butt))
        }

        // Speed arc
        if (speedSweep > 0.5f) {
            drawArc(
                color = speedColor,
                startAngle = startAngle, sweepAngle = speedSweep,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(width = arcThickness, cap = StrokeCap.Round)
            )
        }

        // Tick marks on outside of arc
        val tickOuter = arcRadius + arcThickness * 0.7f
        val tickInner = arcRadius + arcThickness * 0.1f
        for (i in 0..24) {
            val angle = startAngle + (sweepTotal * i / 24f)
            val rad = Math.toRadians(angle.toDouble())
            val isMajor = i % 8 == 0
            val isMinor = i % 4 == 0
            if (!isMajor && !isMinor) continue
            drawLine(
                color = if (isMajor) dimColor else dimColor.copy(alpha = 0.35f),
                start = Offset(center.x + tickInner * cos(rad).toFloat(), center.y + tickInner * sin(rad).toFloat()),
                end = Offset(center.x + tickOuter * cos(rad).toFloat(), center.y + tickOuter * sin(rad).toFloat()),
                strokeWidth = if (isMajor) 2.5f else 1.2f
            )
        }

        // Scale labels outside ticks, pushed outward so digits aren't hugging the dial.
        val labelRadius = arcRadius + arcThickness + size.minDimension * 0.08f
        for ((idx, label) in scaleLabels.withIndex()) {
            val angle = startAngle + (sweepTotal * idx / (scaleLabels.size - 1).toFloat())
            val rad = Math.toRadians(angle.toDouble())
            val measured = textMeasurer.measure(
                "$label",
                style = TextStyle(fontSize = (size.minDimension * 0.05f).sp, color = dimColor)
            )
            drawText(
                measured,
                topLeft = Offset(
                    center.x + labelRadius * cos(rad).toFloat() - measured.size.width / 2f,
                    center.y + labelRadius * sin(rad).toFloat() - measured.size.height / 2f
                )
            )
        }

        // Speed number, dead center of the arc circle.
        // Dynamically clamp text width so digits keep a visible horizontal margin from the arc.
        val speedText = "%.0f".format(displaySpeed)
        val baseFactor = if (speedText.length >= 3) 0.17f else 0.2f
        val innerRadius = arcRadius - arcThickness * 0.5f
        val maxTextHalfWidth = innerRadius * 0.72f  // leaves ~28% clearance each side
        var speedFontFactor = baseFactor
        var speedMeasured = textMeasurer.measure(
            speedText,
            style = TextStyle(
                fontSize = (size.minDimension * speedFontFactor).sp,
                fontWeight = FontWeight.Bold,
                color = speedColor
            )
        )
        if (speedMeasured.size.width / 2f > maxTextHalfWidth) {
            val scale = maxTextHalfWidth / (speedMeasured.size.width / 2f)
            speedFontFactor *= scale
            speedMeasured = textMeasurer.measure(
                speedText,
                style = TextStyle(
                    fontSize = (size.minDimension * speedFontFactor).sp,
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )
            )
        }
        // Digit-stable reference height (measured from "0" at the BASE font
        // factor), used to pin the unit label so it doesn't dance when the
        // speed text width-clamps to a smaller size at 2+ digits.
        val refHeight = textMeasurer.measure(
            "0",
            style = TextStyle(
                fontSize = (size.minDimension * baseFactor).sp,
                fontWeight = FontWeight.Bold
            )
        ).size.height.toFloat()
        // Speed text: vertically centered against its OWN measured height, so
        // smaller (width-clamped) variants still look centered rather than
        // glued to the top of the refHeight box.
        drawText(
            speedMeasured,
            topLeft = Offset(
                center.x - speedMeasured.size.width / 2f,
                center.y - speedMeasured.size.height / 2f - size.minDimension * 0.03f
            )
        )

        // Speed unit (mph / km/h) is drawn OUTSIDE this canvas by the
        // dashboard layout, aligned to the same bottom as the Map / Camera
        // glyphs in the gauge container. Drawing it here used to push it
        // onto the cards row when the experimental banner squeezed the
        // gauge vertically (Pixel 6 Pro / Begode Race).

        // External GPS marker. Tiny dot on the arc at the angle matching the
        // external speed, plus a small numeric readout under the main number.
        // Drawn last so it sits on top of the speed arc.
        if (externalSpeed != null) {
            val extFraction = (externalSpeed / maxSpeed).coerceIn(0f, 1f)
            val extAngle = startAngle + sweepTotal * extFraction
            val extRad = Math.toRadians(extAngle.toDouble())
            val dotRadius = arcThickness * 0.45f
            // Sit the dot on the centerline of the speed arc so it visually
            // tracks the speed sweep. Drawing last means it sits on top of
            // the arc colour band at the same angle.
            val dotDistance = arcRadius
            val dotCenter = Offset(
                center.x + dotDistance * cos(extRad).toFloat(),
                center.y + dotDistance * sin(extRad).toFloat()
            )
            // Halo for contrast against any arc colour.
            drawCircle(
                color = androidx.compose.ui.graphics.Color.Black,
                radius = dotRadius * 1.45f,
                center = dotCenter
            )
            drawCircle(
                color = externalAccentColor,
                radius = dotRadius,
                center = dotCenter
            )
        }
    }
}

// Car-dashboard style status indicator: dim (off) / lit (on).
@Composable
private fun DashIndicatorLetter(
    letter: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    Text(
        text = letter,
        // Fixed width, matches DashIndicatorIcon and the map glyph, so the
        // left-corner cluster lines up on both edges, like the right corner.
        modifier = modifier.width(32.dp),
        textAlign = TextAlign.Center,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = if (active) activeColor else dim
    )
}

@Composable
private fun DashIndicatorIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (active) activeColor else dim,
        modifier = modifier.size(32.dp)
    )
}

// --- Stat card with sparkline ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    sparkData: List<Float> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val clickModifier = when {
        onClick != null || onLongClick != null -> Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
        else -> Modifier
    }
    Box(
        modifier = modifier
            .heightIn(min = 61.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        // Sparkline background
        if (sparkData.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
            ) {
                val min = sparkData.min()
                val max = sparkData.max()
                val range = (max - min).coerceAtLeast(0.1f)
                val padding = size.height * 0.15f
                val drawHeight = size.height - padding * 2
                val stepX = size.width / (sparkData.size - 1).toFloat()

                val path = androidx.compose.ui.graphics.Path()
                sparkData.forEachIndexed { idx, v ->
                    val x = idx * stepX
                    val y = padding + drawHeight - ((v - min) / range) * drawHeight
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                // Filled area under curve
                val fillPath = androidx.compose.ui.graphics.Path()
                fillPath.addPath(path)
                fillPath.lineTo(size.width, size.height)
                fillPath.lineTo(0f, size.height)
                fillPath.close()
                drawPath(fillPath, color = color.copy(alpha = 0.08f))
                // Line on top
                drawPath(path, color = color.copy(alpha = 0.4f), style = Stroke(width = 2.5f))
            }
        }

        // Text content centered
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1)
        }
    }
}

// --- Bottom info box: ODO + firmware ---

@Composable
private fun WheelInfoBox(
    odoKm: Float,
    distanceUnit: String,
    firmwareVersion: String?,
    versionName: String,
    diagnosticsActive: Boolean,
    onVersionClick: () -> Unit,
    versionModifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Left: ODO
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val odoValue = com.eried.eucplanet.util.Units.distance(odoKm, distanceUnit)
            val odoUnit = com.eried.eucplanet.util.Units.distanceUnit(distanceUnit)
            Text(stringResource(R.string.stat_odo), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
            Text("%.0f %s".format(odoValue, odoUnit), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
        }

        // Center: firmware version (the wheel name is shown in the top bar)
        val infoText = firmwareVersion?.let { "v$it" }.orEmpty()
        if (infoText.isNotBlank()) {
            Text(
                infoText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Right: app version (eased red glow when Service Mode is recording)
        val versionColor = if (diagnosticsActive) {
            val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "diagGlow")
            val alpha by infinite.animateFloat(
                initialValue = 1f,
                targetValue = 0.45f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 1000,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "diagGlowAlpha"
            )
            Color.Red.copy(alpha = alpha)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        Text(
            text = versionName,
            fontSize = 10.sp,
            color = versionColor,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .then(versionModifier)
                .clickable(onClick = onVersionClick)
        )
    }
}

// --- Helpers ---

@Composable
private fun FlicIndicator(
    hasFlic: Boolean,
    flashAt: Long,
    onClick: () -> Unit
) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        flashAlpha.snapTo(1f)
        flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 1200))
    }
    // Always visible. Filled circle when paired, hollow ring when not. Flash turns it green on action.
    val baseAlpha = if (hasFlic) 1f else 0.55f
    val alpha = (baseAlpha + flashAlpha.value * (1f - baseAlpha)).coerceAtMost(1f)
    val tint = if (flashAlpha.value > 0f) MaterialTheme.appColors.statusGood
               else MaterialTheme.colorScheme.onSurface
    val icon = if (hasFlic || flashAlpha.value > 0f) Icons.Default.RadioButtonChecked
               else Icons.Default.RadioButtonUnchecked
    IconButton(
        onClick = onClick,
        modifier = Modifier.alpha(alpha)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.flic_button),
            tint = tint
        )
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.appColors.connectionActive
        ConnectionState.CONNECTING, ConnectionState.INITIALIZING, ConnectionState.SCANNING -> MaterialTheme.appColors.statusWarn
        ConnectionState.DISCONNECTED -> MaterialTheme.appColors.statusDanger
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * PopupPositionProvider that surfaces a popup just above the anchor
 * with a slight overlap (top 25% of anchor sits under the popup) and a
 * horizontal shift that depends on the anchor's column position:
 *
 *   - Left column (anchor in left third of window):
 *       popup.left aligns to (anchor.left + 25% of anchor.width),
 *       so the leftmost 25% of the anchor stays visible.
 *   - Center column:
 *       popup centers horizontally on the anchor.
 *   - Right column (anchor in right third):
 *       popup.right aligns to (anchor.right - 25% of anchor.width),
 *       so the rightmost 25% of the anchor stays visible.
 *
 * The 25% slivers let the rider see where the popup came from while
 * keeping the popup body close to the finger position.
 */
private class AboveAnchorPositionProvider : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize
    ): androidx.compose.ui.unit.IntOffset {
        val overlapFraction = 0.25f
        // Vertical overlap: pull the popup's bottom edge 25% of the
        // anchor's height INTO the anchor, so the popup overlaps the
        // top of the button.
        val verticalOverlap = (anchorBounds.height * overlapFraction).toInt()
        val y = (anchorBounds.top - popupContentSize.height + verticalOverlap)
            .coerceAtLeast(8)

        // Horizontal: figure out which third of the window the anchor
        // sits in, then anchor the popup's left/center/right
        // accordingly so the rider gets a 25% "sliver" visible on the
        // far side from the screen center.
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        val leftThird = windowSize.width / 3
        val rightThird = windowSize.width * 2 / 3
        val edgeSliver = (anchorBounds.width * overlapFraction).toInt()
        val rawX = when {
            anchorCenterX < leftThird ->
                anchorBounds.left + edgeSliver
            anchorCenterX > rightThird ->
                anchorBounds.right - edgeSliver - popupContentSize.width
            else ->
                anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        }
        val maxX = (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)
        val clampedX = rawX.coerceIn(8, maxX)
        return androidx.compose.ui.unit.IntOffset(clampedX, y)
    }
}

/**
 * Floating popover that renders an action group's sub-actions as compact
 * tile-shaped buttons, surfacing ABOVE the tapped tile so the popup never
 * lands under the rider's finger. Layout adapts to the number of actions:
 *   - 1 action  → single tile
 *   - 2 actions → one row of 2
 *   - 3 actions → one row of 3
 *   - 4 actions → 2x2 grid
 *
 * Tap any tile → [onPick] fires (which dispatches the sub-action) and
 * the parent closes the popover. Tapping outside dismisses without
 * firing anything.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
private fun ActionGroupPopover(
    actions: List<String>,
    anchorSizePx: androidx.compose.ui.unit.IntSize,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Convert the anchor tile's measured size to dp so each sub-action
    // button can render at the EXACT same width and height. Fallback to
    // sensible defaults if the anchor hasn't measured yet (first frame).
    val buttonWidthDp = with(density) {
        if (anchorSizePx.width > 0) anchorSizePx.width.toDp() else 104.dp
    }
    val buttonHeightDp = with(density) {
        if (anchorSizePx.height > 0) anchorSizePx.height.toDp() else 104.dp
    }

    // Animation: scale-in + fade-in from the bottom-center so the popup
    // visibly pops out of the button the rider tapped. Trigger by
    // flipping `visible` to true on first composition.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    androidx.compose.ui.window.Popup(
        popupPositionProvider = AboveAnchorPositionProvider(),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.scaleIn(
                initialScale = 0.85f,
                animationSpec = androidx.compose.animation.core.tween(180),
                // Pivot the scale-in from the bottom of the popup so it
                // visibly "grows out" of the action button below it.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.0f)
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(150)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(100)
            )
        ) {
            // ElevatedCard draws a native Android drop shadow at the
            // requested elevation (handled by the platform Renderer,
            // not a custom blur), which is what the rider asked for —
            // crisp and consistent with other M3 surfaces.
            androidx.compose.material3.ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val takeFour = actions.take(4)
                    val rows: List<List<String>> = when (takeFour.size) {
                        4 -> listOf(takeFour.subList(0, 2), takeFour.subList(2, 4))
                        else -> listOf(takeFour)
                    }
                    rows.forEachIndexed { rowIdx, rowActions ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowActions.forEach { subKey ->
                                val subSpec = com.eried.eucplanet.data.model.ActionCatalog.byKey(subKey)
                                val subLabel = subSpec?.let { stringResource(it.labelRes) } ?: subKey
                                // Each tile gets the anchor's exact
                                // pixel-perfect width and height so it
                                // looks identical to the dashboard
                                // action button row.
                                ActionButton(
                                    icon = subSpec?.icon ?: Icons.Default.Campaign,
                                    label = subLabel,
                                    onClick = { onPick(subKey) },
                                    modifier = Modifier
                                        .width(buttonWidthDp)
                                        .height(buttonHeightDp),
                                    aspectRatio = null,
                                    heightDp = null
                                )
                            }
                        }
                        if (rowIdx < rows.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    aspectRatio: Float? = 1f,
    heightDp: Int? = null
) {
    val disabledAlpha = 0.35f
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = disabledAlpha)
        active -> activeColor.copy(alpha = 0.2f)
        else -> MaterialTheme.appColors.tileBackground
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
        active -> activeColor
        else -> MaterialTheme.appColors.tileLabel
    }
    Box(
        modifier = modifier
            .let { if (aspectRatio != null) it.aspectRatio(aspectRatio) else it }
            .let { if (heightDp != null) it.height(heightDp.dp) else it }
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 2, textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    aspectRatio: Float? = 1f,
    heightDp: Int? = null
) {
    Box(modifier = modifier) {
        var menuOpen by remember { mutableStateOf(false) }
        ActionButton(
            icon = icon,
            label = label,
            active = active,
            activeColor = activeColor,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { menuOpen = true },
            modifier = Modifier.fillMaxWidth(),
            aspectRatio = aspectRatio,
            heightDp = heightDp
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.appColors.menuBackground
        ) {
            menu { menuOpen = false }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun shareCrashFile(context: Context, file: java.io.File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "EUC Planet crash: ${file.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.about_share_crash))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
