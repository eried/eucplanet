package com.eried.eucplanet.ui.studio

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.OverlayElement
import com.eried.eucplanet.data.model.OverlayElementType
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.ViewportSourceType
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.studio.camera.rememberStudioCameraHub
import com.eried.eucplanet.ui.studio.recording.StudioApngEncoder
import com.eried.eucplanet.ui.studio.recording.StudioCapture
import com.eried.eucplanet.ui.studio.recording.StudioVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Overlay Studio — a fullscreen video / photo recorder with a fully
 * customisable data-overlay layout.
 *
 * The viewports and overlays are drawn into a recording [GraphicsLayer]; video
 * recording and photo snapshots both capture that layer, so the output is the
 * finished composite. No MediaProjection, so no "share your screen" dialog.
 */
@Composable
fun OverlayStudioScreen(
    onBack: () -> Unit,
    onOpenBackupSettings: () -> Unit,
    replayTripId: Long? = null,
    viewModel: OverlayStudioViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val graphicsLayer = rememberGraphicsLayer()

    val preset by viewModel.preset.collectAsState()
    val selectedId by viewModel.selectedElementId.collectAsState()
    val liveWheelData by viewModel.wheelData.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val wheelName by viewModel.wheelName.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val history by viewModel.history.collectAsState()
    val folderAvailable by viewModel.folderAvailable.collectAsState()
    val savedPresets by viewModel.savedPresets.collectAsState()
    val bundledPresets by viewModel.bundledPresets.collectAsState()

    var sheet by remember { mutableStateOf<StudioSheet>(StudioSheet.None) }
    var confirm by remember { mutableStateOf<StudioConfirm?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var imageTargetId by remember { mutableStateOf<String?>(null) }
    var imageTargetViewport by remember { mutableStateOf<Int?>(null) }
    var elapsed by remember { mutableStateOf(0) }

    // --- Replay mode -------------------------------------------------------
    var studioMode by remember { mutableStateOf(StudioMode.LIVE) }
    var replayRecord by remember { mutableStateOf<TripRecord?>(null) }
    var replayTrip by remember { mutableStateOf<ReplayTrip?>(null) }
    var replayPosMs by remember { mutableStateOf(0L) }
    var replayStartMs by remember { mutableStateOf(0L) }
    var replayEndMs by remember { mutableStateOf(0L) }
    var replaySpeed by remember { mutableStateOf(1f) }
    var replayPlaying by remember { mutableStateOf(false) }
    var replayDimmed by remember { mutableStateOf(false) }
    val replayMode = studioMode == StudioMode.REPLAY
    // Overlays read this: trip telemetry while replaying, live telemetry otherwise.
    val wheelData = if (replayMode) {
        replayTrip?.dataAt(replayPosMs) ?: WheelData()
    } else {
        liveWheelData
    }

    // Opened from a trip ("Replay in Studio") — jump straight into replay.
    LaunchedEffect(replayTripId) {
        val id = replayTripId ?: return@LaunchedEffect
        val rec = viewModel.tripById(id) ?: return@LaunchedEffect
        replayRecord = rec
        studioMode = StudioMode.REPLAY
        val rt = viewModel.loadReplayTrip(rec)
        replayTrip = rt
        replayStartMs = 0L
        replayEndMs = rt?.durationMs ?: 0L
        replayPosMs = 0L
        replayPlaying = false
    }

    // Replay playback clock — advances the scrub position while playing.
    LaunchedEffect(replayMode, replayPlaying, replaySpeed, replayStartMs, replayEndMs) {
        if (!replayMode || !replayPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000.0 * replaySpeed).toLong()
            last = now
            val next = replayPosMs + dt
            if (next >= replayEndMs) {
                replayPosMs = replayEndMs
                replayPlaying = false
                break
            }
            replayPosMs = next.coerceAtLeast(replayStartMs)
        }
    }
    var recording by remember { mutableStateOf(false) }
    var encoder by remember { mutableStateOf<StudioVideoEncoder?>(null) }
    var micEnabled by remember { mutableStateOf(true) }
    // Replay APNG export — offline frame-by-frame render with a progress bar.
    var rendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableStateOf(0f) }
    // Physical device rotation (0/90/180/270) — the layout stays fixed but the
    // control icons counter-rotate so they read upright when held sideways.
    var deviceRotation by remember { mutableStateOf(0) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val chromeVisible = !recording && !capturing && !rendering
    val editable = !recording && !capturing && !rendering

    // --- Cameras -----------------------------------------------------------
    val requestedCameras = remember(preset) {
        buildList {
            preset.viewports.forEach {
                if (it.source == ViewportSourceType.CAMERA) add(it.cameraKey)
            }
            preset.elements.forEach {
                if (it.type == OverlayElementType.FLOATING_CAMERA) add(it.cameraKey)
            }
        }.distinct()
    }
    val cameraNeeded = requestedCameras.isNotEmpty()
    val hub = rememberStudioCameraHub(
        requestedKeys = requestedCameras,
        enabled = cameraNeeded && hasCameraPermission
    )

    // --- Permissions -------------------------------------------------------
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasCameraPermission = grants[android.Manifest.permission.CAMERA] == true ||
            hasCameraPermission
        hasAudioPermission = grants[android.Manifest.permission.RECORD_AUDIO] == true ||
            hasAudioPermission
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasCameraPermission) add(android.Manifest.permission.CAMERA)
            if (!hasAudioPermission) add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        viewModel.refreshFolderState()
    }

    // --- Image picker (embeds the picked image in the preset) --------------
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val encoded = StudioImages.encodeForPreset(context, uri)
            if (encoded == null) {
                snackbar.showSnackbar(context.getString(R.string.studio_image_failed))
                return@launch
            }
            val vpTarget = imageTargetViewport
            val elTarget = imageTargetId
            when {
                vpTarget != null ->
                    viewModel.preset.value.viewports.getOrNull(vpTarget)?.let {
                        viewModel.setViewport(
                            vpTarget,
                            it.copy(
                                source = ViewportSourceType.IMAGE,
                                imageData = encoded
                            )
                        )
                    }
                elTarget != null ->
                    viewModel.preset.value.elements.firstOrNull { it.id == elTarget }
                        ?.let { viewModel.updateElement(it.copy(imageData = encoded)) }
                else -> viewModel.addElement(
                    OverlayElement(
                        type = OverlayElementType.IMAGE,
                        imageData = encoded,
                        x = 0.18f, y = 0.2f, width = 0.32f
                    )
                )
            }
            imageTargetId = null
            imageTargetViewport = null
        }
    }

    // --- Recording: capture the GraphicsLayer frame-by-frame ---------------
    LaunchedEffect(encoder) {
        val enc = encoder ?: return@LaunchedEffect
        var ok = true
        var started = false
        try {
            while (recording && ok) {
                val t0 = System.currentTimeMillis()
                val img = runCatching { graphicsLayer.toImageBitmap() }.getOrNull()
                if (img != null) {
                    val bmp = img.asAndroidBitmap()
                    ok = withContext(Dispatchers.IO) {
                        if (!started) {
                            started = enc.start(bmp.width, bmp.height)
                            if (!started) return@withContext false
                        }
                        enc.submitFrame(bmp)
                        true
                    }
                }
                val dt = System.currentTimeMillis() - t0
                if (dt < FRAME_INTERVAL_MS) delay(FRAME_INTERVAL_MS - dt)
            }
        } finally {
            val uri = withContext(NonCancellable + Dispatchers.IO) { enc.finish() }
            // Only clear shared state if a new recording has not already
            // replaced this encoder (guards a fast stop-then-record).
            if (encoder === enc) {
                encoder = null
                recording = false
            }
            scope.launch {
                snackbar.showSnackbar(
                    context.getString(
                        if (uri != null && ok) R.string.studio_recording_saved
                        else R.string.studio_recording_failed
                    )
                )
            }
        }
    }

    // --- Recording elapsed timer ------------------------------------------
    LaunchedEffect(recording) {
        if (recording) {
            elapsed = 0
            while (true) {
                delay(1000)
                elapsed++
            }
        }
    }

    // --- Photo snapshot ----------------------------------------------------
    LaunchedEffect(capturing) {
        if (capturing) {
            delay(160) // let the element selection chrome clear for a clean frame
            val bmp = runCatching { graphicsLayer.toImageBitmap().asAndroidBitmap() }
                .getOrNull()
            // Replay snapshots are PNG so the transparent background survives;
            // live snapshots stay JPEG.
            val uri = bmp?.let {
                if (replayMode) StudioCapture.savePng(context, it)
                else StudioCapture.saveJpeg(context, it)
            }
            // Restore the chrome the instant the save is done — showSnackbar
            // suspends for the snackbar's whole lifetime, so clearing this
            // after it would freeze the studio for ~4 s every photo.
            capturing = false
            snackbar.showSnackbar(
                context.getString(
                    if (uri != null) R.string.studio_photo_saved
                    else R.string.studio_photo_failed
                )
            )
        }
    }

    // --- Replay APNG export (offline, frame-by-frame) ----------------------
    LaunchedEffect(rendering) {
        if (!rendering) return@LaunchedEffect
        val trip = replayTrip
        if (trip == null || replayEndMs <= replayStartMs) {
            rendering = false
            return@LaunchedEffect
        }
        val savedPos = replayPosMs
        renderProgress = 0f
        val fps = 12
        val frameMs = 1000 / fps
        val span = replayEndMs - replayStartMs
        var frameCount = (span / frameMs).toInt() + 1
        val maxFrames = 360
        val stepMs: Long = if (frameCount > maxFrames) {
            frameCount = maxFrames
            span / (frameCount - 1)
        } else {
            frameMs.toLong()
        }

        // Frame 0 — also tells us the capture dimensions.
        replayPosMs = replayStartMs
        repeat(3) { withFrameNanos {} }
        val first = runCatching { graphicsLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        if (first == null) {
            rendering = false
            return@LaunchedEffect
        }
        // Cap the longest side at 720 px — keeps render time and file size sane.
        val srcMax = maxOf(first.width, first.height)
        val scale = if (srcMax > 720) 720f / srcMax else 1f
        val ew = (first.width * scale).toInt().coerceAtLeast(2)
        val eh = (first.height * scale).toInt().coerceAtLeast(2)

        val pending = withContext(Dispatchers.IO) {
            StudioCapture.newPendingImage(
                context, "${StudioCapture.timestampedName()}.png", "image/png"
            )
        }
        val stream = pending?.openStream()
        if (pending == null || stream == null) {
            rendering = false
            return@LaunchedEffect
        }
        val ok = try {
            val apng = StudioApngEncoder(stream, ew, eh, frameCount, frameMs)
            withContext(Dispatchers.IO) { apng.addFrame(first) }
            renderProgress = 1f / frameCount
            for (i in 1 until frameCount) {
                replayPosMs = replayStartMs + i * stepMs
                repeat(3) { withFrameNanos {} }
                val frame = graphicsLayer.toImageBitmap().asAndroidBitmap()
                withContext(Dispatchers.IO) { apng.addFrame(frame) }
                renderProgress = (i + 1f) / frameCount
            }
            withContext(Dispatchers.IO) { apng.finish() }
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayStudio", "Replay APNG render failed", e)
            false
        } finally {
            withContext(Dispatchers.IO) { runCatching { stream.close() } }
        }
        pending.finalize(ok)
        replayPosMs = savedPos
        rendering = false
        snackbar.showSnackbar(if (ok) "Replay clip saved" else "Replay export failed")
    }

    // Keep the screen awake and run immersive while the studio is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
            recording = false // the capture loop's finally finalises the file
        }
    }

    // Track physical rotation so the control icons can counter-rotate (the
    // layout itself never rotates — that would scramble the viewport panes).
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val snapped = (((orientation + 45) / 90) * 90) % 360
                if (snapped != deviceRotation) deviceRotation = snapped
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    // Back while recording stops the take instead of leaving the studio.
    BackHandler(enabled = recording) { recording = false }

    fun openGallery() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                }
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"
                        )
                    }
                )
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                // Replay shows a Photoshop-style checkerboard so the rider sees
                // the background is transparent; live mode is plain black.
                if (replayMode) Modifier.drawBehind { replayCheckerboard() }
                else Modifier.background(Color.Black)
            )
    ) {
        // Recordable region — everything drawn here is captured into the
        // GraphicsLayer (and therefore into the video / photo). The bottom
        // bar and recording chrome sit outside it, so they never appear.
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    graphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(graphicsLayer)
                }
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                StudioViewportLayer(
                    preset = preset,
                    hub = hub,
                    hasCameraPermission = hasCameraPermission,
                    editable = editable,
                    replayMode = replayMode,
                    onDividerChange = viewModel::setDividers,
                    onConfigViewport = { sheet = StudioSheet.ViewportConfig(it) },
                    onConfigDivider = { sheet = StudioSheet.DividerConfig },
                    onTapEmpty = { viewModel.selectElement(null) },
                    onDoubleTapEmpty = { sheet = StudioSheet.AddElement }
                )
                StudioElementLayer(
                    elements = preset.elements,
                    replayMode = replayMode,
                    data = StudioElementData(
                        wheelData = wheelData,
                        wheelName = wheelName,
                        connected = connected,
                        history = history,
                        cameraHub = hub,
                        speedUnit = viewModel.speedUnit,
                        distanceUnit = viewModel.distanceUnit,
                        tempUnit = viewModel.tempUnit
                    ),
                    editable = editable,
                    selectedId = selectedId,
                    onSelect = {
                        viewModel.selectElement(it)
                        viewModel.bringToFront(it)
                    },
                    onConfigure = { sheet = StudioSheet.ElementConfig(it) },
                    onDelete = { viewModel.removeElement(it) },
                    onChange = viewModel::updateElement
                )
            }
        }

        // Recording indicator (not recorded — outside the layer region).
        if (recording) {
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                RecordingPill(
                    elapsed = elapsed,
                    rotation = -deviceRotation.toFloat(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                    onStop = { recording = false }
                )
            }
        }

        // Bottom bar — gallery (left), photo/record/mic (centre), "..." (right).
        if (chromeVisible) {
            val iconRot = -deviceRotation.toFloat()
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                // Back to dashboard — top-left corner.
                StudioRoundButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    background = Color(0xCC1E1E26),
                    size = 48.dp,
                    iconRotation = iconRot,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 14.dp)
                ) { onBack() }
                // Gallery — pinned to the bottom-left corner, like a camera app.
                StudioRoundButton(
                    icon = Icons.Default.PhotoLibrary,
                    background = Color(0xCC1E1E26),
                    size = 48.dp,
                    iconRotation = iconRot,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, bottom = 33.dp)
                ) { openGallery() }
                // Photo / record / mic — centred. Mic is disabled in replay.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    StudioRoundButton(
                        icon = Icons.Default.PhotoCamera,
                        background = Color(0xCC1E1E26),
                        size = 52.dp,
                        iconRotation = iconRot
                    ) { if (!capturing) capturing = true }
                    RecordButton {
                        if (replayMode) {
                            // Replay records an offline transparent APNG clip.
                            if (!rendering && replayTrip != null) rendering = true
                        } else if (encoder == null) {
                            encoder = StudioVideoEncoder(
                                context,
                                withAudio = micEnabled && hasAudioPermission
                            )
                            recording = true
                        }
                    }
                    StudioRoundButton(
                        icon = if (micEnabled && !replayMode) Icons.Default.Mic
                        else Icons.Default.MicOff,
                        background = Color(0xCC1E1E26),
                        size = 52.dp,
                        iconTint = when {
                            replayMode -> Color(0xFF5A5A5A)
                            micEnabled -> Color(0xFF8BC34A)
                            else -> Color(0xFFE57373)
                        },
                        iconRotation = iconRot
                    ) {
                        // Mic is disabled in replay — a trip has no live audio.
                        if (replayMode) return@StudioRoundButton
                        val turningOn = !micEnabled
                        micEnabled = turningOn
                        if (turningOn && !hasAudioPermission) {
                            permissionLauncher.launch(
                                arrayOf(android.Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    }
                }
                // "..." camera-tools flyout — bottom-right corner.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 33.dp)
                ) {
                    StudioRoundButton(
                        icon = Icons.Default.MoreHoriz,
                        background = Color(0xCC1E1E26),
                        size = 48.dp,
                        iconRotation = iconRot,
                        onLongClick = { sheet = StudioSheet.AddElement }
                    ) { menuOpen = true }
                    StudioToolsFlyout(
                        expanded = menuOpen,
                        hasElements = preset.elements.isNotEmpty(),
                        onDismiss = { menuOpen = false },
                        onAddElement = { sheet = StudioSheet.AddElement },
                        onManageElements = { sheet = StudioSheet.ManageElements },
                        onChangeLayout = { sheet = StudioSheet.LayoutPicker },
                        onNew = { confirm = StudioConfirm.ClearLayout },
                        onLoadPreset = {
                            viewModel.refreshFolderState()
                            sheet = StudioSheet.LoadPreset
                        },
                        onSavePreset = {
                            viewModel.refreshFolderState()
                            sheet = StudioSheet.SavePreset
                        },
                        onReplayMode = { studioMode = StudioMode.REPLAY }
                    )
                }
            }
        }

        // Replay panel — always on while in replay mode; its X returns to live.
        if (replayMode) {
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                StudioReplayDialog(
                    trips = trips,
                    selectedTrip = replayRecord,
                    trip = replayTrip,
                    positionMs = replayPosMs,
                    rangeStartMs = replayStartMs,
                    rangeEndMs = replayEndMs,
                    speed = replaySpeed,
                    playing = replayPlaying,
                    onPickTrip = { rec ->
                        replayRecord = rec
                        replayPlaying = false
                        scope.launch {
                            val rt = viewModel.loadReplayTrip(rec)
                            replayTrip = rt
                            replayStartMs = 0L
                            replayEndMs = rt?.durationMs ?: 0L
                            replayPosMs = 0L
                        }
                    },
                    onScrub = { replayPosMs = it },
                    onRange = { s, e ->
                        replayStartMs = s
                        replayEndMs = e
                        replayPosMs = replayPosMs.coerceIn(s, e)
                    },
                    onSpeed = { replaySpeed = it },
                    onPlayPause = { replayPlaying = !replayPlaying },
                    dimmed = replayDimmed,
                    onToggleDim = { replayDimmed = !replayDimmed },
                    onClose = {
                        studioMode = StudioMode.LIVE
                        replayPlaying = false
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                        .alpha(if (replayDimmed) 0.4f else 1f)
                )
            }
        }

        // Replay clip render progress.
        if (rendering) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(36.dp)
                ) {
                    Text("Rendering replay clip", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { renderProgress },
                        modifier = Modifier.width(220.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(renderProgress * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // Camera permission hint.
        if (cameraNeeded && !hasCameraPermission && chromeVisible) {
            CameraPermissionCard(
                modifier = Modifier.align(Alignment.Center),
                onGrant = {
                    permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                }
            )
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)
        )
    }

    // --- Sheets ------------------------------------------------------------
    when (val s = sheet) {
        StudioSheet.None -> {}
        StudioSheet.ManageElements -> ManageElementsSheet(
            elements = preset.elements,
            onMove = { from, to -> viewModel.moveElement(from, to) },
            onSelect = { id ->
                viewModel.selectElement(id)
                sheet = StudioSheet.None
            },
            onDelete = { viewModel.removeElement(it) },
            onDismiss = { sheet = StudioSheet.None }
        )
        StudioSheet.AddElement -> AddElementSheet(
            onPick = { type ->
                viewModel.addElement(newElement(type, preset.elements, deviceRotation))
                sheet = StudioSheet.None
            },
            onPickImage = {
                imageTargetId = null
                imageTargetViewport = null
                sheet = StudioSheet.None
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onDismiss = { sheet = StudioSheet.None }
        )
        StudioSheet.LayoutPicker -> LayoutPickerSheet(
            current = preset.layout,
            onPick = {
                viewModel.setLayout(it)
                sheet = StudioSheet.None
            },
            onDismiss = { sheet = StudioSheet.None }
        )
        StudioSheet.SavePreset -> SavePresetDialog(
            folderAvailable = folderAvailable,
            onSave = { name ->
                viewModel.savePresetAs(name) { result ->
                    scope.launch {
                        snackbar.showSnackbar(
                            context.getString(
                                when (result) {
                                    PresetSaveResult.SAVED -> R.string.studio_preset_saved
                                    PresetSaveResult.NO_FOLDER -> R.string.studio_no_folder
                                    PresetSaveResult.FAILED -> R.string.studio_preset_failed
                                }
                            )
                        )
                    }
                }
                sheet = StudioSheet.None
            },
            onOpenFolderSettings = {
                sheet = StudioSheet.None
                onOpenBackupSettings()
            },
            onDismiss = { sheet = StudioSheet.None }
        )
        StudioSheet.LoadPreset -> LoadPresetSheet(
            folderAvailable = folderAvailable,
            presets = savedPresets,
            bundledPresets = bundledPresets,
            onLoad = { name ->
                sheet = StudioSheet.None
                confirm = StudioConfirm.LoadUserPreset(name)
            },
            onLoadBundled = { name ->
                sheet = StudioSheet.None
                confirm = StudioConfirm.LoadBundledPreset(name)
            },
            onDelete = { viewModel.deletePreset(it) },
            onOpenFolderSettings = {
                sheet = StudioSheet.None
                onOpenBackupSettings()
            },
            onDismiss = { sheet = StudioSheet.None }
        )
        StudioSheet.DividerConfig -> DividerConfigSheet(
            color = preset.dividerColor,
            thickness = preset.dividerThickness,
            onChange = { color, thickness -> viewModel.setDividerStyle(color, thickness) },
            onDismiss = { sheet = StudioSheet.None }
        )
        is StudioSheet.ViewportConfig -> {
            val cfg = preset.viewports.getOrNull(s.index)
            if (cfg == null) {
                sheet = StudioSheet.None
            } else {
                ViewportConfigSheet(
                    index = s.index,
                    config = cfg,
                    cameras = hub.cameras,
                    onChange = { viewModel.setViewport(s.index, it) },
                    onPickImage = {
                        imageTargetViewport = s.index
                        imageTargetId = null
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onDismiss = { sheet = StudioSheet.None }
                )
            }
        }
        is StudioSheet.ElementConfig -> {
            val element = preset.elements.firstOrNull { it.id == s.elementId }
            if (element == null) {
                sheet = StudioSheet.None
            } else {
                ElementConfigSheet(
                    element = element,
                    cameras = hub.cameras,
                    onChange = viewModel::updateElement,
                    onReplaceImage = {
                        imageTargetId = element.id
                        imageTargetViewport = null
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onDismiss = { sheet = StudioSheet.None }
                )
            }
        }
    }

    // --- Confirmation dialogs for destructive / layout-replacing actions ----
    confirm?.let { pending ->
        StudioConfirmDialog(
            confirm = pending,
            onConfirm = {
                when (pending) {
                    StudioConfirm.ClearLayout -> viewModel.clearLayout()
                    is StudioConfirm.LoadUserPreset -> viewModel.loadPreset(pending.name) { ok ->
                        scope.launch {
                            snackbar.showSnackbar(
                                context.getString(
                                    if (ok) R.string.studio_preset_loaded
                                    else R.string.studio_preset_failed
                                )
                            )
                        }
                    }
                    is StudioConfirm.LoadBundledPreset ->
                        viewModel.loadBundledPreset(pending.name) { ok ->
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(
                                        if (ok) R.string.studio_preset_loaded
                                        else R.string.studio_preset_failed
                                    )
                                )
                            }
                        }
                }
                confirm = null
            },
            onDismiss = { confirm = null }
        )
    }
}

private const val FRAME_INTERVAL_MS = 33L

/**
 * Default element for [type], dropped near the top-left of the layout. New
 * elements are pre-rotated by [deviceRotation] so they read upright for a rider
 * holding the phone sideways (the layout itself never rotates).
 */
private fun newElement(
    type: OverlayElementType,
    elements: List<OverlayElement>,
    deviceRotation: Int = 0
): OverlayElement {
    val isGauge = type == OverlayElementType.DATA_DIAL ||
        type == OverlayElementType.DATA_BAR
    val metric = nextMetric(elements)
    return OverlayElement(
        type = type,
        x = 0.12f,
        y = 0.16f,
        width = when (type) {
            OverlayElementType.DATA_GRAPH, OverlayElementType.DATA_BAR,
            OverlayElementType.TEXT -> 0.5f
            OverlayElementType.DATA_VALUE, OverlayElementType.DATA_DIAL,
            OverlayElementType.FLOATING_CAMERA -> 0.34f
            else -> 0.42f
        },
        metric = metric,
        gaugeMax = if (isGauge) StudioMetric.fromKey(metric).defaultMax else 100f,
        rotationDeg = ((360 - deviceRotation) % 360).toFloat()
    )
}

/**
 * Picks the next metric in [StudioMetric] order no data element uses yet
 * (Speed, then Battery, Temperature, …) so a fresh layout fills out with
 * varied values instead of a wall of speed.
 */
private fun nextMetric(elements: List<OverlayElement>): String {
    val dataTypes = setOf(
        OverlayElementType.DATA_VALUE, OverlayElementType.DATA_GRAPH,
        OverlayElementType.DATA_DIAL, OverlayElementType.DATA_BAR
    )
    val used = elements.filter { it.type in dataTypes }.map { it.metric }.toSet()
    return StudioMetric.entries.firstOrNull { it.key !in used }?.key
        ?: StudioMetric.SPEED.key
}

@Composable
private fun StudioRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconTint: Color = Color.White,
    iconRotation: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pointerInput(onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = onLongClick?.let { cb -> { cb() } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.45f).rotate(iconRotation)
        )
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(Color(0x55FFFFFF))
            .border(3.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
        )
    }
}

@Composable
private fun RecordingPill(
    elapsed: Int,
    rotation: Float,
    modifier: Modifier,
    onStop: () -> Unit
) {
    Row(
        modifier
            .rotate(rotation)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onStop() })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FiberManualRecord,
            contentDescription = null,
            tint = Color(0xFFE53935),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "%02d:%02d".format(elapsed / 60, elapsed % 60),
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.Stop,
            contentDescription = stringResource(R.string.studio_stop),
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CameraPermissionCard(modifier: Modifier, onGrant: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1E1E26))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            stringResource(R.string.studio_camera_permission),
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(12.dp))
        androidx.compose.material3.Button(onClick = onGrant) {
            Text(stringResource(R.string.studio_grant))
        }
    }
}
