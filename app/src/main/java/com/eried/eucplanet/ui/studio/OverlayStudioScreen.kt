package com.eried.eucplanet.ui.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.hud.protocol.OverlayElement
import com.eried.eucplanet.hud.protocol.OverlayElementType
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.hud.protocol.ViewportSourceType
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.ui.studio.camera.rememberStudioCameraHub
import com.eried.eucplanet.ui.studio.recording.StudioApngEncoder
import com.eried.eucplanet.ui.studio.recording.StudioGifEncoder
import com.eried.eucplanet.ui.studio.recording.StudioCapture
import com.eried.eucplanet.ui.studio.recording.StudioVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Overlay Studio: a fullscreen video / photo recorder with a fully
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
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val preset by viewModel.preset.collectAsState()
    val selectedId by viewModel.selectedElementId.collectAsState()
    val liveWheelData by viewModel.wheelData.collectAsState()
    val liveGForceTrail by viewModel.liveGForceTrail.collectAsState()
    val riderMarkerPhoto by viewModel.riderMarkerPhotoDataUrl.collectAsState()
    val trips by viewModel.trips.collectAsState()
    val wheelName by viewModel.wheelName.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val history by viewModel.history.collectAsState()
    val folderAvailable by viewModel.folderAvailable.collectAsState()
    val savedPresets by viewModel.savedPresets.collectAsState()
    val bundledPresets by viewModel.bundledPresets.collectAsState()
    val bundledLandscapePresets by viewModel.bundledLandscapePresets.collectAsState()
    val exportPrefs by viewModel.replayExportPrefs.collectAsState()

    var sheet by remember { mutableStateOf<StudioSheet>(StudioSheet.None) }
    var confirm by remember { mutableStateOf<StudioConfirm?>(null) }
    // The element config sheet's "Style" section open / closed state, hoisted
    // here so it sticks for the studio session across different elements (not
    // persisted to settings).
    var styleSheetExpanded by remember { mutableStateOf(false) }
    // Likewise, the viewport config sheet's camera "Geometry" section, hoisted
    // so it sticks for the session as the rider hops between viewports.
    var geometrySheetExpanded by remember { mutableStateOf(false) }
    // And the viewport config sheet's camera "Style" (colour-grading) section.
    var cameraStyleExpanded by remember { mutableStateOf(false) }
    val dirty by viewModel.dirty.collectAsState()
    // Layouts are capped; past the limit, Add is disabled everywhere.
    val canAddElement = preset.elements.size < OverlayStudioViewModel.MAX_ELEMENTS

    // Runs a New / Load action. When the working layout is untouched there is
    // nothing to lose, so it applies straight away with no confirmation dialog.
    val applyLayoutChange: (StudioConfirm) -> Unit = { pending ->
        when (pending) {
            StudioConfirm.ClearLayout -> viewModel.clearLayout()
            is StudioConfirm.LoadUserPreset -> viewModel.loadPreset(pending.name) { ok ->
                if (!ok) scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.studio_preset_failed))
                }
            }
            is StudioConfirm.LoadBundledPreset ->
                viewModel.loadBundledPreset(pending.name) { ok ->
                    if (!ok) scope.launch {
                        snackbar.showSnackbar(context.getString(R.string.studio_preset_failed))
                    }
                }
        }
    }
    val requestLayoutChange: (StudioConfirm) -> Unit = { pending ->
        if (dirty) confirm = pending else applyLayoutChange(pending)
    }
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
    var panelsDimmed by remember { mutableStateOf(false) }
    // When ON, drag / resize handlers snap to a 5 px grid (in canvas-
    // fraction units). Persists across navigation through Manage so the
    // rider doesn't need to keep re-enabling it; resets per Studio open
    // because it's an editor-mode preference, not a persisted setting.
    var snapToGrid by remember { mutableStateOf(false) }
    val replayMode = studioMode == StudioMode.REPLAY
    // Overlays read this: trip telemetry while replaying, live telemetry otherwise.
    val wheelData = if (replayMode) {
        replayTrip?.dataAt(replayPosMs) ?: WheelData()
    } else {
        liveWheelData
    }

    // Trip CSVs carry no wheel name, so in replay fall back to the file name
    // (without the .csv) so the WHEEL_NAME overlay still says something useful.
    val displayWheelName = if (replayMode && wheelName.isBlank()) {
        replayRecord?.fileName?.removeSuffix(".csv").orEmpty()
    } else {
        wheelName
    }

    // Graph elements read history; in replay there is no live feed, so build it
    // from the trip's own samples up to the current scrub position.
    val elementHistory: List<StudioSample> = if (replayMode) {
        replayTrip?.samples
            ?.filter { it.offsetMs <= replayPosMs }
            ?.map { StudioSample(it.offsetMs, it.data) }
            ?: emptyList()
    } else {
        history
    }

    // CLOCK elements: a 2 Hz tick drives the live time; in replay the time is
    // the trip's start epoch plus the scrub position.
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockTick = System.currentTimeMillis()
            delay(500)
        }
    }
    val clockTimeMs = if (replayMode) {
        (replayTrip?.startEpochMs ?: 0L) + replayPosMs
    } else {
        clockTick
    }

    // Opened from a trip ("Replay in Studio"): jump straight into replay.
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

    // Replay playback clock: advances the scrub position while playing.
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
    // STOPWATCH overlay: synced to the recording take. recordingStartMs is the
    // wall-clock instant the take began; stopwatchNowMs ticks once per frame so
    // the overlay advances with ss.sss precision while recording.
    var recordingStartMs by remember { mutableStateOf(0L) }
    var stopwatchNowMs by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (true) {
            withFrameNanos {}
            stopwatchNowMs = System.currentTimeMillis()
        }
    }
    // In replay the stopwatch follows the scrub position; live, it runs only
    // while a take is recording so the clip and the overlay clock stay in sync.
    val stopwatchMs = when {
        replayMode -> replayPosMs
        recording -> (stopwatchNowMs - recordingStartMs).coerceAtLeast(0L)
        else -> 0L
    }
    // Replay APNG export: offline frame-by-frame render with a progress bar.
    var rendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableStateOf(0f) }
    var renderCancelRequested by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    // True while an alpha-less export (JPG / MP4) is rendering and the rider has
    // the "force opaque" toggle on; the recording layer then draws every
    // overlay element at 100% opacity so nothing blends with the chroma fill.
    var renderForceOpaque by remember { mutableStateOf(false) }
    // Physical device rotation (0/90/180/270): the layout stays fixed but the
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
    // Camera conversion is never paused: a paused feed shows the rider the
    // LAST converted frame frozen behind the translucent scrim, which reads
    // as "the dialog is a screenshot, the app is broken". Recording also
    // keeps capturing live frames regardless, so a frozen preview misleads
    // about what's actually being recorded. The GC pressure that motivated
    // the original pause needs to be addressed at its source (reusing
    // Bitmaps in the YUV→Bitmap fan-out, throttling slider recomposition)
    // rather than by hiding the feed.
    LaunchedEffect(hub) {
        hub.convertPaused = false
    }

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

    // --- Recording: draw the studio layer straight onto the encoder --------
    LaunchedEffect(encoder) {
        val enc = encoder ?: return@LaunchedEffect
        // The studio is already a GraphicsLayer on the GPU. Replay it directly
        // onto the H.264 encoder's input surface, no toImageBitmap() render +
        // intermediate bitmap. The loop runs OFF the UI thread so the encode
        // never serialises behind composition; replaying a recorded display
        // list is thread-safe.
        val drawScope = CanvasDrawScope()
        try {
            withContext(Dispatchers.Default) {
                var started = false
                // Drift-free 60 fps pacing: schedule each frame against an
                // accumulator, so a loose delay() on one frame is absorbed by
                // the next instead of compounding into a lower average rate.
                var nextFrameNs = System.nanoTime()
                while (recording) {
                    val layerSize = graphicsLayer.size
                    if (layerSize.width > 0 && layerSize.height > 0) {
                        if (!started) {
                            started = enc.start(layerSize.width, layerSize.height)
                            if (!started) break
                        }
                        val ew = enc.encodeWidth
                        val eh = enc.encodeHeight
                        val drawn = enc.submitFrame { androidCanvas ->
                            drawScope.draw(
                                density,
                                layoutDirection,
                                androidx.compose.ui.graphics.Canvas(androidCanvas),
                                Size(ew.toFloat(), eh.toFloat())
                            ) {
                                scale(
                                    ew / layerSize.width.toFloat(),
                                    eh / layerSize.height.toFloat(),
                                    Offset.Zero
                                ) {
                                    drawLayer(graphicsLayer)
                                }
                            }
                        }
                        if (!drawn) break // the encoder failed
                    }
                    nextFrameNs += FRAME_INTERVAL_NS
                    val sleepNs = nextFrameNs - System.nanoTime()
                    when {
                        // On schedule: wait out the rest of the frame.
                        sleepNs > 0L -> delay(sleepNs / 1_000_000L)
                        // A real stall (>4 frames behind): resync, don't burst.
                        sleepNs < -4L * FRAME_INTERVAL_NS -> nextFrameNs = System.nanoTime()
                        // Slightly behind: skip the wait; the next frame catches up.
                    }
                }
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
                val saved = uri != null
                val result = snackbar.showSnackbar(
                    message = context.getString(
                        if (saved) R.string.studio_recording_saved
                        else R.string.studio_recording_failed
                    ),
                    actionLabel =
                        if (saved) context.getString(R.string.action_view) else null,
                    duration = SnackbarDuration.Long
                )
                if (saved && result == SnackbarResult.ActionPerformed) {
                    openInGallery(context, uri!!, "video/mp4")
                }
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
            // Replay snapshots honour the chosen photo format. Live
            // snapshots default to WebP -- alpha-capable like PNG, but
            // ~3-10x faster to encode and roughly half the file size for
            // typical studio composites. Force-opaque + chroma still only
            // apply on alpha-LESS formats.
            val photoFormat =
                if (replayMode) exportPrefs.photoFormat else ReplayPhotoFormat.WEBP
            val opaqueExport = !photoFormat.hasAlpha && exportPrefs.forceOpaque
            if (opaqueExport) renderForceOpaque = true
            delay(160) // let the element selection chrome clear for a clean frame
            val bmp = runCatching { graphicsLayer.toImageBitmap().asAndroidBitmap() }
                .getOrNull()
            val uri = bmp?.let { src ->
                when (photoFormat) {
                    ReplayPhotoFormat.PNG -> StudioCapture.savePng(context, src)
                    ReplayPhotoFormat.WEBP -> StudioCapture.saveWebp(context, src)
                    ReplayPhotoFormat.JPG ->
                        // JPEG has no alpha; flatten onto the chroma colour first.
                        StudioCapture.saveJpeg(
                            context, flattenOntoChroma(src, exportPrefs.chromaColor)
                        )
                }
            }
            val photoMime = when (photoFormat) {
                ReplayPhotoFormat.PNG -> "image/png"
                ReplayPhotoFormat.WEBP -> "image/webp"
                ReplayPhotoFormat.JPG -> "image/jpeg"
            }
            // Restore the chrome the instant the save is done; showSnackbar
            // suspends for the snackbar's whole lifetime, so clearing this
            // after it would freeze the studio for ~4 s every photo.
            capturing = false
            renderForceOpaque = false
            // The snackbar runs on the screen scope, not this effect: clearing
            // `capturing` above re-keys LaunchedEffect(capturing) and would
            // cancel the toast the moment it suspended if shown from here.
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = context.getString(
                        if (uri != null) R.string.studio_photo_saved
                        else R.string.studio_photo_failed
                    ),
                    actionLabel =
                        if (uri != null) context.getString(R.string.action_view) else null,
                    duration = SnackbarDuration.Long
                )
                if (uri != null && result == SnackbarResult.ActionPerformed) {
                    openInGallery(context, uri, photoMime)
                }
            }
        }
    }

    // --- Replay clip export (offline, frame-by-frame) ----------------------
    // GIF / APNG / MP4 all share the same offline frame-stepping loop; only the
    // encoder differs: GIF and APNG stream into a pending gallery image, MP4
    // drives StudioVideoEncoder which publishes its own MP4.
    LaunchedEffect(rendering) {
        if (!rendering) return@LaunchedEffect
        renderCancelRequested = false
        val trip = replayTrip
        if (trip == null || replayEndMs <= replayStartMs) {
            rendering = false
            return@LaunchedEffect
        }
        val videoFormat = exportPrefs.videoFormat
        val chroma = exportPrefs.chromaColor
        val savedPos = replayPosMs
        // Force-opaque + chroma fill only apply on alpha-less formats (MP4).
        // GIF / APNG carry alpha and keep transparency as-is regardless of
        // the toggles -- the whole reason a rider picks them.
        val opaqueExport = !videoFormat.hasAlpha && exportPrefs.forceOpaque
        if (opaqueExport) renderForceOpaque = true
        // The saved-file Uri, captured per format so the success snackbar can
        // offer a "View" action: MP4 from the encoder, GIF / APNG from the
        // pending gallery image.
        var resultUri: Uri? = null
        renderProgress = 0f
        val fps = 10
        val frameMs = 1000 / fps
        val span = replayEndMs - replayStartMs
        // The clip honours the replay speed: at 4x the trimmed span plays back
        // in a quarter of the time, so it needs a quarter of the frames, and
        // renders that much faster. Slow speeds add frames for smoother slow-mo.
        // Frames only ever sample inside the trimmed [start, end] range.
        val outputMs = (span / replaySpeed.coerceAtLeast(0.05f)).toLong()
        val maxFrames = 240
        val frameCount = ((outputMs / frameMs).toInt() + 1).coerceIn(2, maxFrames)
        val stepMs: Long = span / (frameCount - 1)

        // Frame 0: also tells us the capture dimensions.
        replayPosMs = replayStartMs
        repeat(2) { withFrameNanos {} }
        val first = runCatching { graphicsLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        if (first == null) {
            rendering = false
            return@LaunchedEffect
        }
        // Output size: the chosen Scale percentage of the studio's native
        // resolution (smaller = quicker render, smaller file).
        val pct = exportPrefs.scale.coerceIn(25, 100) / 100f
        val ew = (first.width * pct).toInt().coerceAtLeast(2)
        val eh = (first.height * pct).toInt().coerceAtLeast(2)

        var cancelled = false
        val ok: Boolean = try {
            when (videoFormat) {
                ReplayVideoFormat.MP4 -> {
                    // MP4 has no alpha; every frame is composited onto the
                    // chroma colour before it is drawn into the encoder.
                    val mp4 = StudioVideoEncoder(context, withAudio = false)
                    var encoderUri: Uri? = null
                    try {
                        if (!mp4.start(ew, eh)) {
                            false
                        } else {
                            val canvasW = mp4.encodeWidth
                            val canvasH = mp4.encodeHeight
                            val paint = android.graphics.Paint(
                                android.graphics.Paint.FILTER_BITMAP_FLAG
                            )
                            fun submit(frame: android.graphics.Bitmap): Boolean {
                                val sw = if (frame.config == android.graphics.Bitmap.Config.HARDWARE) {
                                    frame.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                } else frame
                                val drawn = mp4.submitFrame { canvas ->
                                    canvas.drawColor(chroma.toInt())
                                    if (sw != null) {
                                        val dst = android.graphics.Rect(0, 0, canvasW, canvasH)
                                        canvas.drawBitmap(sw, null, dst, paint)
                                    }
                                }
                                if (sw != null && sw !== frame) sw.recycle()
                                return drawn
                            }
                            var encOk = withContext(Dispatchers.Default) { submit(first) }
                            renderProgress = 1f / frameCount
                            var i = 1
                            while (encOk && i < frameCount) {
                                if (renderCancelRequested) {
                                    cancelled = true
                                    break
                                }
                                replayPosMs = replayStartMs + i * stepMs
                                repeat(2) { withFrameNanos {} }
                                val frame = graphicsLayer.toImageBitmap().asAndroidBitmap()
                                encOk = withContext(Dispatchers.Default) { submit(frame) }
                                renderProgress = (i + 1f) / frameCount
                                i++
                            }
                            encoderUri = withContext(Dispatchers.IO) { mp4.finish() }
                            val mp4Ok = !cancelled && encOk && encoderUri != null
                            if (mp4Ok) resultUri = encoderUri
                            mp4Ok
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("OverlayStudio", "Replay MP4 render failed", e)
                        runCatching { mp4.finish() }
                        false
                    }
                }
                else -> {
                    // GIF / APNG: stream straight into a pending gallery image.
                    val pending = withContext(Dispatchers.IO) {
                        StudioCapture.newPendingImage(
                            context,
                            "${StudioCapture.timestampedName()}." +
                                if (videoFormat == ReplayVideoFormat.GIF) "gif" else "png",
                            if (videoFormat == ReplayVideoFormat.GIF) "image/gif" else "image/png"
                        )
                    }
                    val stream = pending?.openStream()
                    if (pending == null || stream == null) {
                        false
                    } else {
                        var imgOk = false
                        try {
                            // GIF (1-bit alpha) vs APNG (full RGBA alpha).
                            val gif = if (videoFormat == ReplayVideoFormat.GIF)
                                StudioGifEncoder(stream, ew, eh, frameMs) else null
                            val apng = if (videoFormat == ReplayVideoFormat.APNG)
                                StudioApngEncoder(stream, ew, eh, frameMs, frameCount) else null
                            withContext(Dispatchers.IO) {
                                gif?.addFrame(first)
                                apng?.addFrame(first)
                            }
                            renderProgress = 1f / frameCount
                            for (i in 1 until frameCount) {
                                if (renderCancelRequested) {
                                    cancelled = true
                                    break
                                }
                                replayPosMs = replayStartMs + i * stepMs
                                repeat(2) { withFrameNanos {} }
                                val frame = graphicsLayer.toImageBitmap().asAndroidBitmap()
                                withContext(Dispatchers.IO) {
                                    gif?.addFrame(frame)
                                    apng?.addFrame(frame)
                                }
                                renderProgress = (i + 1f) / frameCount
                            }
                            if (!cancelled) {
                                withContext(Dispatchers.IO) {
                                    gif?.finish()
                                    apng?.finish()
                                }
                                imgOk = true
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("OverlayStudio", "Replay clip render failed", e)
                            imgOk = false
                        } finally {
                            runCatching { stream.close() }
                        }
                        pending.finalize(imgOk)
                        if (imgOk) resultUri = pending.uri
                        imgOk
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayStudio", "Replay clip render failed", e)
            false
        }
        replayPosMs = savedPos
        rendering = false
        renderCancelRequested = false
        renderForceOpaque = false
        val savedUri = resultUri
        // The snackbar runs on the screen scope, not this effect: clearing
        // `rendering` above re-keys LaunchedEffect(rendering), which would
        // cancel the toast the instant showSnackbar suspended if shown here.
        scope.launch {
            val result = snackbar.showSnackbar(
                message = when {
                    ok -> context.getString(R.string.studio_replay_clip_saved)
                    cancelled -> context.getString(R.string.studio_replay_render_cancelled)
                    else -> context.getString(R.string.studio_replay_export_failed)
                },
                actionLabel =
                    if (ok && savedUri != null) context.getString(R.string.action_view)
                    else null,
                duration = SnackbarDuration.Long
            )
            if (ok && savedUri != null && result == SnackbarResult.ActionPerformed) {
                val mime = when (videoFormat) {
                    ReplayVideoFormat.GIF -> "image/gif"
                    ReplayVideoFormat.APNG -> "image/png"
                    ReplayVideoFormat.MP4 -> "video/mp4"
                }
                openInGallery(context, savedUri, mime)
            }
        }
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

    // Android can quietly stop delivering sensor events while the app is
    // backgrounded; re-arm the IMU when the studio returns to the foreground.
    // Hook ON_RESUME (not ON_START) because a quick task-switch often only
    // pauses the activity; ON_START would miss the bounce. Also re-arm on
    // ON_START as a belt-and-braces for the cold-return case.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START || e == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSensors()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Track physical rotation so the control icons can counter-rotate (the
    // layout itself never rotates; that would scramble the viewport panes).
    //
    // The naive `((orientation + 45) / 90) * 90` snap flipped on the 45 deg
    // line, so a phone tilted just past portrait-into-landscape would
    // bounce the rotation back and forth as the rider's grip drifted by a
    // degree or two. We now require the orientation to land >65 deg away
    // from the CURRENT rotation before flipping -- a 20 deg hysteresis
    // band on either side of each 45 deg quadrant edge. The rider has to
    // actually rotate the phone, not just hold it slightly off-axis.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val candidate = (((orientation + 45) / 90) * 90) % 360
                if (candidate == deviceRotation) return
                // Shortest signed angular distance from `orientation` to
                // `deviceRotation`, in (-180, 180].
                val diff = ((orientation - deviceRotation + 540) % 360) - 180
                if (kotlin.math.abs(diff) > 65) {
                    deviceRotation = candidate
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    // Back while recording stops the take instead of leaving the studio.
    BackHandler(enabled = recording) { recording = false }
    // Back during a replay render asks before throwing the work away.
    BackHandler(enabled = rendering) { showCancelConfirm = true }

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

    // Everything in the studio chrome rotates to face a sideways-held phone.
    CompositionLocalProvider(
        LocalStudioRotation provides deviceRotation,
        LocalStudioHudEnabled provides viewModel.hudEnabled
    ) {
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
        // Recordable region: everything drawn here is captured into the
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
                    onDoubleTapEmpty = {
                        if (canAddElement) sheet = StudioSheet.AddElement
                    },
                    // Long-press on empty canvas is now a shortcut to the
                    // Manage Elements sheet (parallel to long-pressing the
                    // "..." button at the top). The Add button lives inside
                    // that sheet too, so the rider has a one-gesture entry
                    // to building / editing the layout from anywhere.
                    onLongPressEmpty = { sheet = StudioSheet.ManageElements }
                )
                StudioElementLayer(
                    // While an opaque export is in progress, force every element
                    // fully opaque so nothing partially-transparent blends with
                    // the chroma fill of an alpha-less format. We promote
                    // partial alpha (0 < a < 255) to 255 but leave fully
                    // transparent colours (a == 0) alone -- the rider chose
                    // "no background", and putting one back would defeat that.
                    elements = if (renderForceOpaque) {
                        fun bumpAlpha(c: Long): Long =
                            if ((c ushr 24) and 0xFFL == 0L) c
                            else c or 0xFF000000L
                        preset.elements.map {
                            it.copy(
                                opacity = 1f,
                                background = bumpAlpha(it.background),
                                foreground = bumpAlpha(it.foreground)
                            )
                        }
                    } else {
                        preset.elements
                    },
                    replayMode = replayMode,
                    data = StudioElementData(
                        wheelData = wheelData,
                        wheelName = displayWheelName,
                        connected = connected,
                        history = elementHistory,
                        cameraHub = hub,
                        speedUnit = viewModel.speedUnit,
                        distanceUnit = viewModel.distanceUnit,
                        tempUnit = viewModel.tempUnit,
                        clockTimeMs = clockTimeMs,
                        stopwatchMs = stopwatchMs,
                        // Live mode feeds the G-Force overlay its 50 Hz rolling
                        // trail (dot is just the last entry). Replay leaves
                        // this empty; the overlay falls back to wheelData /
                        // history derived from the scrubbed trip row.
                        liveGForceTrail = if (replayMode) emptyList() else liveGForceTrail,
                        riderMarkerPhotoDataUrl = riderMarkerPhoto
                    ),
                    editable = editable,
                    selectedId = selectedId,
                    snapToGrid = snapToGrid,
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

        // Recording indicator (not recorded; outside the layer region).
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

        // Bottom bar: gallery (left), photo/record/mic (centre), "..." (right).
        if (chromeVisible) {
            val iconRot = -deviceRotation.toFloat()
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                // Exit the studio, top-left corner (X since it is full-screen).
                StudioRoundButton(
                    icon = Icons.Default.Close,
                    background = MaterialTheme.appColors.surfaceVariant,
                    size = 48.dp,
                    iconRotation = iconRot,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 2.dp)
                ) { onBack() }
                // Gallery: pinned to the bottom-left corner, like a camera app.
                StudioRoundButton(
                    icon = Icons.Default.PhotoLibrary,
                    background = MaterialTheme.appColors.surfaceVariant,
                    size = 48.dp,
                    iconRotation = iconRot,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, bottom = 33.dp)
                ) { openGallery() }
                // Photo / record / mic, centred. Mic is disabled in replay.
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    StudioRoundButton(
                        icon = Icons.Default.PhotoCamera,
                        background = MaterialTheme.appColors.surfaceVariant,
                        size = 52.dp,
                        iconRotation = iconRot
                    ) { if (!capturing) capturing = true }
                    RecordButton(
                        // In replay a render needs a trip; disable it until
                        // one is picked, instead of a no-op tap.
                        enabled = !(replayMode && replayTrip == null)
                    ) {
                        if (replayMode) {
                            // Replay renders an offline clip in the chosen
                            // video format (GIF / APNG / MP4).
                            if (!rendering && replayTrip != null) rendering = true
                        } else if (encoder == null) {
                            encoder = StudioVideoEncoder(
                                context,
                                withAudio = micEnabled && hasAudioPermission
                            )
                            recordingStartMs = System.currentTimeMillis()
                            stopwatchNowMs = recordingStartMs
                            recording = true
                        }
                    }
                    StudioRoundButton(
                        icon = if (micEnabled && !replayMode) Icons.Default.Mic
                        else Icons.Default.MicOff,
                        background = MaterialTheme.appColors.surfaceVariant,
                        size = 52.dp,
                        iconTint = when {
                            replayMode -> MaterialTheme.appColors.textDisabled
                            micEnabled -> MaterialTheme.appColors.statusGood
                            else -> MaterialTheme.appColors.statusDanger
                        },
                        iconRotation = iconRot
                    ) {
                        // Mic is disabled in replay; a trip has no live audio.
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
                // "..." camera-tools flyout, bottom-right corner.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 33.dp)
                ) {
                    StudioRoundButton(
                        icon = Icons.Default.MoreHoriz,
                        background = MaterialTheme.appColors.surfaceVariant,
                        size = 48.dp,
                        iconRotation = iconRot,
                        // Long-press is the shortcut to the Manage Elements
                        // sheet (which also has its own Add button, so it
                        // covers every element action). Double-tap toggles
                        // Replay <-> Live -- the rider's other-most-common
                        // mode flip, and it has no slider sheet to clash
                        // with.
                        onLongClick = { sheet = StudioSheet.ManageElements },
                        onDoubleClick = {
                            if (replayMode) {
                                studioMode = StudioMode.LIVE
                                replayPlaying = false
                            } else {
                                studioMode = StudioMode.REPLAY
                            }
                        }
                    ) { menuOpen = true }
                    StudioToolsFlyout(
                        expanded = menuOpen,
                        hasElements = preset.elements.isNotEmpty(),
                        canAddElement = canAddElement,
                        onDismiss = { menuOpen = false },
                        onAddElement = { sheet = StudioSheet.AddElement },
                        onManageElements = { sheet = StudioSheet.ManageElements },
                        onChangeLayout = { sheet = StudioSheet.LayoutPicker },
                        onNew = { requestLayoutChange(StudioConfirm.ClearLayout) },
                        onLoadPreset = {
                            viewModel.refreshFolderState()
                            sheet = StudioSheet.LoadPreset
                        },
                        onSavePreset = {
                            viewModel.refreshFolderState()
                            sheet = StudioSheet.SavePreset
                        },
                        onReplayMode = {
                            // The flyout's Mode button toggles: while replaying
                            // it reads "Live" and returns to the live camera,
                            // dismissing the replay panel.
                            if (replayMode) {
                                studioMode = StudioMode.LIVE
                                replayPlaying = false
                            } else {
                                studioMode = StudioMode.REPLAY
                            }
                        },
                        replayMode = replayMode,
                        deviceRotation = deviceRotation
                    )
                }
            }
        }

        // Replay panel: always on while in replay mode; its X returns to live.
        if (replayMode) {
          RotatedFullScreen(deviceRotation) {
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                StudioReplayDialog(
                    trips = trips,
                    selectedTrip = replayRecord,
                    trip = replayTrip,
                    distanceUnit = viewModel.distanceUnit,
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
                    dimmed = panelsDimmed,
                    onToggleDim = { panelsDimmed = !panelsDimmed },
                    exportPrefs = exportPrefs,
                    onPhotoFormat = { viewModel.setReplayPhotoFormat(it) },
                    onVideoFormat = { viewModel.setReplayVideoFormat(it) },
                    onChromaColor = { viewModel.setReplayChromaColor(it) },
                    onForceOpaque = { viewModel.setReplayForceOpaque(it) },
                    onScale = { viewModel.setReplayScale(it) },
                    onClose = {
                        studioMode = StudioMode.LIVE
                        replayPlaying = false
                    },
                    modifier = Modifier
                        // In landscape the screen is short; centre the panel
                        // so it has the full height (no scroll) and clears the
                        // side buttons. Portrait keeps it above the bottom bar.
                        .then(
                            if (deviceRotation == 90 || deviceRotation == 270)
                                Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            else
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                        )
                        .alpha(if (panelsDimmed) 0.65f else 1f)
                )
            }
          }
        }

        // Replay clip render progress.
        if (rendering) {
          // No crossfade on the render overlay; fading its scrim mid-render
          // would blink the whole screen when the phone is rotated.
          RotatedFullScreen(deviceRotation, withFade = false) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.appColors.scrim.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(36.dp)
                ) {
                    Text(
                        stringResource(R.string.studio_rendering),
                        color = MaterialTheme.appColors.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(18.dp))
                    val barWidth = 240.dp
                    Box(
                        Modifier
                            .width(barWidth)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.appColors.gaugeTrack)
                    ) {
                        Box(
                            Modifier
                                .width(barWidth * renderProgress.coerceIn(0f, 1f))
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.appColors.primary)
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(
                        stringResource(R.string.studio_rendering_keep_open),
                        color = MaterialTheme.appColors.textPrimary.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(280.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    TextButton(onClick = { showCancelConfirm = true }) {
                        Text(stringResource(R.string.studio_rendering_cancel))
                    }
                }
            }
          }
        }

        // Confirm before throwing away an in-progress render.
        if (showCancelConfirm) {
            AlertDialog(
                onDismissRequest = { showCancelConfirm = false },
                modifier = Modifier.rotateLayout(LocalStudioRotation.current),
                title = { Text(stringResource(R.string.studio_dlg_cancel_render_title)) },
                text = { Text(stringResource(R.string.studio_dlg_cancel_render_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        showCancelConfirm = false
                        renderCancelRequested = true
                    }) { Text(stringResource(R.string.studio_dlg_cancel_render_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelConfirm = false }) {
                        Text(stringResource(R.string.studio_dlg_cancel_render_dismiss))
                    }
                }
            )
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
            canAddElement = canAddElement,
            // Panes button mirrors the tools-flyout's "Panes" entry: layout
            // change is hidden during a replay (the background is the trip's
            // checkerboard, so picking camera panes there is moot).
            canChangePanes = !replayMode,
            snapToGrid = snapToGrid,
            onMove = { from, to -> viewModel.moveElement(from, to) },
            onSelect = { id ->
                viewModel.selectElement(id)
                sheet = StudioSheet.None
            },
            onDelete = { viewModel.removeElement(it) },
            onAddElement = { sheet = StudioSheet.AddElement },
            onChangePanes = { sheet = StudioSheet.LayoutPicker },
            onSnapToGrid = { snapToGrid = it },
            dimmed = panelsDimmed,
            onToggleDim = { panelsDimmed = !panelsDimmed },
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
            dimmed = panelsDimmed,
            onToggleDim = { panelsDimmed = !panelsDimmed },
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
        StudioSheet.SavePreset -> {
            // Track a pending-overwrite confirmation: the rider tried to save
            // under a name that already matches one of the on-disk presets.
            // We hand the actual write off only after they confirm; cancelling
            // returns to the save dialog with the typed name preserved.
            var pendingOverwriteName by remember { mutableStateOf<String?>(null) }
            val existingNames = remember(savedPresets) {
                savedPresets.map { it.trim().lowercase() }.toSet()
            }
            fun doSave(name: String) {
                viewModel.savePresetAs(name) { result ->
                    val msg = when (result) {
                        PresetSaveResult.SAVED -> R.string.studio_preset_saved
                        PresetSaveResult.NO_FOLDER -> R.string.studio_no_folder
                        PresetSaveResult.FAILED -> R.string.studio_preset_failed
                    }
                    scope.launch {
                        snackbar.showSnackbar(context.getString(msg))
                    }
                }
                sheet = StudioSheet.None
            }
            SavePresetDialog(
                folderAvailable = folderAvailable,
                onSave = { name ->
                    val trimmed = name.trim()
                    if (trimmed.lowercase() in existingNames) {
                        pendingOverwriteName = trimmed
                    } else {
                        doSave(trimmed)
                    }
                },
                onOpenFolderSettings = {
                    sheet = StudioSheet.None
                    onOpenBackupSettings()
                },
                onDismiss = { sheet = StudioSheet.None }
            )
            val pending = pendingOverwriteName
            if (pending != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingOverwriteName = null },
                    title = { Text(stringResource(R.string.studio_overwrite_title)) },
                    text = { Text(stringResource(R.string.studio_overwrite_body, pending)) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            pendingOverwriteName = null
                            doSave(pending)
                        }) { Text(stringResource(R.string.studio_overwrite_confirm)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            pendingOverwriteName = null
                        }) { Text(stringResource(R.string.action_cancel)) }
                    }
                )
            }
        }
        StudioSheet.LoadPreset -> LoadPresetSheet(
            folderAvailable = folderAvailable,
            presets = savedPresets,
            bundledPresets = bundledPresets,
            bundledLandscapePresets = bundledLandscapePresets,
            onLoad = { name ->
                sheet = StudioSheet.None
                requestLayoutChange(StudioConfirm.LoadUserPreset(name))
            },
            onLoadBundled = { name ->
                sheet = StudioSheet.None
                requestLayoutChange(StudioConfirm.LoadBundledPreset(name))
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
            dimmed = panelsDimmed,
            onToggleDim = { panelsDimmed = !panelsDimmed },
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
                    inUseKeys = requestedCameras.toSet(),
                    dimmed = panelsDimmed,
                    geometryExpanded = geometrySheetExpanded,
                    cameraStyleExpanded = cameraStyleExpanded,
                    onToggleDim = { panelsDimmed = !panelsDimmed },
                    onGeometryExpandedChange = { geometrySheetExpanded = it },
                    onCameraStyleExpandedChange = { cameraStyleExpanded = it },
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
                    inUseKeys = requestedCameras.toSet(),
                    dimmed = panelsDimmed,
                    styleExpanded = styleSheetExpanded,
                    hasCustomRiderMarker = !riderMarkerPhoto.isNullOrEmpty(),
                    onToggleDim = { panelsDimmed = !panelsDimmed },
                    onStyleExpandedChange = { styleSheetExpanded = it },
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
                applyLayoutChange(pending)
                confirm = null
            },
            onDismiss = { confirm = null }
        )
    }
    }
}

// Capture-loop pacing. Aimed a touch above 60 fps: delay() only ever
// overshoots, so targeting a 62 fps interval lands the delivered video at a
// solid 60+ fps. pts are wall-clock, so a slower device still plays back at
// real speed (just with fewer frames).
private const val FRAME_INTERVAL_NS = 1_000_000_000L / 62L

/**
 * Composite [src] onto an opaque [chroma] colour. JPEG carries no alpha, so the
 * transparent replay background has to be flattened to a solid fill first.
 */
private fun flattenOntoChroma(
    src: android.graphics.Bitmap,
    chroma: Long
): android.graphics.Bitmap {
    val flat = android.graphics.Bitmap.createBitmap(
        src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(flat)
    canvas.drawColor(chroma.toInt())
    // getPixels needs a software bitmap; toImageBitmap() may hand back HARDWARE.
    val sw = if (src.config == android.graphics.Bitmap.Config.HARDWARE) {
        src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    } else src
    if (sw != null) {
        canvas.drawBitmap(sw, 0f, 0f, null)
        if (sw !== src) sw.recycle()
    }
    return flat
}

/** Open a saved photo / video in whatever gallery app handles it. */
private fun openInGallery(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { android.util.Log.w("OverlayStudio", "No viewer for $uri", it) }
}

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
    // Cascade each new element so it never lands exactly on an existing one;
    // a perfect overlap makes the covered element impossible to tap/select.
    val step = elements.size % 6
    val nx = 0.10f + step * 0.045f
    val ny = 0.12f + step * 0.060f
    if (type == OverlayElementType.G_FORCE) {
        return OverlayElement(
            type = type,
            x = nx,
            y = ny,
            width = 0.4f,
            graphWindowSec = 6,
            foreground = 0xFF4FC3F7L, // cyan trail / dot
            background = 0x66000000L,
            rotationDeg = ((360 - deviceRotation) % 360).toFloat()
        )
    }
    if (type == OverlayElementType.MAP) {
        return OverlayElement(
            type = type,
            x = nx,
            y = ny,
            width = 0.4f,
            mapZoom = 16,
            mapStyle = "STREET",
            foreground = 0xFF2196F3L, // blue border / trace / marker
            background = 0x66000000L,
            rotationDeg = ((360 - deviceRotation) % 360).toFloat()
        )
    }
    return OverlayElement(
        type = type,
        x = nx,
        y = ny,
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
    iconTint: Color = MaterialTheme.appColors.textPrimary,
    iconRotation: Float = 0f,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pointerInput(onLongClick, onDoubleClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = onLongClick?.let { cb -> { cb() } },
                    onDoubleTap = onDoubleClick?.let { cb -> { _ -> cb() } }
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
private fun RecordButton(enabled: Boolean = true, onClick: () -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(Color(0x55FFFFFF))
            .border(3.dp, if (enabled) Color.White else Color(0x55FFFFFF), CircleShape)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { currentOnClick() })
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(0xFFE53935) else Color(0x66E53935))
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
            .background(MaterialTheme.appColors.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.appColors.textPrimary,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            stringResource(R.string.studio_camera_permission),
            color = MaterialTheme.appColors.textPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(12.dp))
        androidx.compose.material3.Button(onClick = onGrant) {
            Text(stringResource(R.string.studio_grant))
        }
    }
}
