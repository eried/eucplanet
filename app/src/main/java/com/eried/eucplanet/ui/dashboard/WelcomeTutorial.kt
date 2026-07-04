package com.eried.eucplanet.ui.dashboard

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import com.eried.eucplanet.ui.settings.LeftAlignedScanButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eried.eucplanet.R
import com.eried.eucplanet.ui.common.HintText
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.ui.theme.themedSwitchColors
import java.nio.ByteBuffer

/**
 * First-launch coach-mark tour of the dashboard. A self-contained spotlight
 * overlay: dashboard elements register their on-screen bounds via
 * [Modifier.coachmarkTarget] / [Modifier.coachmarkTargetUnion]; the overlay
 * dims everything except the current target. The spotlight glides + zooms onto
 * each element and gently breathes; the tooltip card slides to the opposite
 * half and crossfades. Shown once, gated by the persisted welcomeTutorialSeen
 * flag in the hosting screen.
 */
enum class TutorialTarget {
    BLUETOOTH, SPEED_DIAL, METRICS, ACTIONS, MAP_BUTTON, GPS_BUTTON, CAMERA_BUTTON, PARK_DRIVE, VERSION
}

/** Holds the live on-screen bounds of each target and the current step. */
class CoachmarkState {
    val bounds = mutableStateMapOf<TutorialTarget, Rect>()
    var stepIndex by mutableIntStateOf(0)
}

@Composable
fun rememberCoachmarkState(): CoachmarkState = remember { CoachmarkState() }

/** Registers this element's root-relative bounds (replacing any prior value). */
fun Modifier.coachmarkTarget(state: CoachmarkState, target: TutorialTarget): Modifier =
    this.onGloballyPositioned { state.bounds[target] = it.boundsInRoot() }

/** Like [coachmarkTarget] but UNIONS with any bounds already registered for the
 *  same target -- so a region made of several rows (metrics, actions) reports a
 *  single rect spanning them all. */
fun Modifier.coachmarkTargetUnion(state: CoachmarkState, target: TutorialTarget): Modifier =
    this.onGloballyPositioned {
        val r = it.boundsInRoot()
        val cur = state.bounds[target]
        state.bounds[target] = if (cur == null) r else Rect(
            minOf(cur.left, r.left), minOf(cur.top, r.top),
            maxOf(cur.right, r.right), maxOf(cur.bottom, r.bottom)
        )
    }

private data class TutorialStep(
    val target: TutorialTarget?,
    val text: String,
    val showClip: Boolean = false,
    /** True for the "save & share" step that renders its own title + two
     *  link-bearing bullets (eucviewer + eucstats). Mutually exclusive with
     *  [showClip]; the body [text] is ignored when this is true. */
    val showShare: Boolean = false,
)

/**
 * The ordered steps. Copy is composed from the EXISTING Settings label string
 * resources (the trailing arguments) so the names shown here always match the
 * real UI and follow the same translations. The final step bundles the backup,
 * extra-actions and sign-off tips and carries the looping welcome clip.
 */
@Composable
private fun tutorialSteps(): List<TutorialStep> = listOf(
    TutorialStep(null, stringResource(R.string.welcome_tut_welcome)),
    TutorialStep(TutorialTarget.BLUETOOTH, stringResource(R.string.welcome_tut_bt)),
    TutorialStep(
        TutorialTarget.SPEED_DIAL,
        stringResource(R.string.welcome_tut_speed, stringResource(R.string.tab_display))
    ),
    TutorialStep(
        TutorialTarget.METRICS,
        stringResource(R.string.welcome_tut_metrics, stringResource(R.string.tab_dashboard))
    ),
    TutorialStep(
        TutorialTarget.ACTIONS,
        stringResource(
            R.string.welcome_tut_actions,
            stringResource(R.string.tab_dashboard),
            stringResource(R.string.dashboard_section_actions)
        )
    ),
    TutorialStep(TutorialTarget.MAP_BUTTON, stringResource(R.string.welcome_tut_map)),
    TutorialStep(
        TutorialTarget.GPS_BUTTON,
        stringResource(R.string.welcome_tut_gps, stringResource(R.string.section_speed_calibration))
    ),
    TutorialStep(TutorialTarget.CAMERA_BUTTON, stringResource(R.string.welcome_tut_camera)),
    TutorialStep(
        TutorialTarget.PARK_DRIVE,
        stringResource(R.string.welcome_tut_pd, stringResource(R.string.section_speed_limits))
    ),
    TutorialStep(TutorialTarget.VERSION, stringResource(R.string.welcome_tut_version)),
    // Share step: links to the trip-viewer (folder sync) and the
    // leaderboards (eucstats). No spotlight, two link-bearing bullets.
    TutorialStep(null, "", showShare = true),
    // Outro: no single text -- the card renders a title + bulleted tips + clip.
    TutorialStep(null, "", showClip = true)
)

/** One bulleted tip line in the outro card. */
@Composable
private fun OutroBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Turn any of the known `*.ried.no` markers in [text] into tappable Url
 *  links. The marker strings are constant across every locale, so splitting
 *  on them keeps the links working in every translation. Passes the text
 *  through unchanged when no marker is present. */
@Composable
private fun annotateRiedLinks(text: String): androidx.compose.ui.text.AnnotatedString {
    val link = MaterialTheme.appColors.link
    val markers = listOf("eucviewer.ried.no", "eucstats.ried.no")
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val hit = markers
                .mapNotNull { m -> text.indexOf(m, cursor).takeIf { it >= 0 }?.let { it to m } }
                .minByOrNull { it.first }
            if (hit == null) {
                append(text.substring(cursor)); break
            }
            val (idx, marker) = hit
            append(text.substring(cursor, idx))
            withLink(
                LinkAnnotation.Url(
                    "https://$marker",
                    styles = TextLinkStyles(
                        style = SpanStyle(color = link, textDecoration = TextDecoration.Underline)
                    )
                )
            ) { append(marker) }
            cursor = idx + marker.length
        }
    }
}

/** A small looping, soundless animated clip decoded from an asset. */
@Composable
private fun LoopingClip(assetName: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                runCatching {
                    val bytes = ctx.assets.open(assetName).use { it.readBytes() }
                    val drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                    )
                    setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.apply {
                        repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        start()
                    }
                }
            }
        }
    )
}

/**
 * Full-screen tour overlay. [onFinish] is called on confirmed Skip or after the
 * last step's Done.
 */
@Composable
fun WelcomeTutorialOverlay(
    state: CoachmarkState,
    onVoiceToggle: (Boolean) -> Unit = {},
    // The voice switch must reflect the *actual* persisted state, not a hardcoded
    // off. Otherwise the wizard can show the switch off while voice announcements
    // are really on (e.g. the rider flipped it on earlier), so they "see it
    // disabled but still hear GPS / wheel connected".
    voiceCurrentlyOn: Boolean = false,
    // Dev-only quick tools, surfaced on step 0 of branch builds (BuildConfig.IS_DEV).
    // "Set backup folder" shows first; once a folder is set, Join and Sync appear,
    // and Restore appears only when the folder actually holds a settings backup.
    isDev: Boolean = false,
    onSetBackupFolder: () -> Unit = {},
    dropboxLinked: Boolean = false,
    onLinkDropbox: () -> Unit = {},
    backupFolderSet: Boolean = false,
    hasSettingsBackup: Boolean = false,
    onRestoreSettings: () -> Unit = {},
    onJoinLeaderboards: () -> Unit = {},
    // Once joined (online upload enabled), the button greys out and stays that
    // way, the same disabled-after-action treatment Sync trips gets.
    leaderboardsJoined: Boolean = false,
    onSyncTrips: () -> Unit = {},
    syncRunning: Boolean = false,
    onFinish: () -> Unit,
) {
    val steps = tutorialSteps()
    val idx = state.stepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[idx]
    val isLast = idx == steps.lastIndex
    val targetRect = step.target?.let { state.bounds[it] }
    val accent = MaterialTheme.colorScheme.primary
    // Scrim base captured here (composable scope) so the Canvas DrawScope below,
    // which can't read MaterialTheme, can dim with the theme's scrim color.
    val scrimColor = MaterialTheme.appColors.scrim

    var showSkipConfirm by remember { mutableStateOf(false) }
    // Voice opt-in switch on step 0. Flipping it instantly applies (so the
    // rider gets a spoken welcome as live confirmation) and persists, so
    // closing the wizard with the switch on leaves voice enabled and the
    // switch off leaves it silent -- nothing else to apply on Next/Skip.
    var voicesOptIn by rememberSaveable { mutableStateOf(voiceCurrentlyOn) }
    fun advance() { if (isLast) onFinish() else state.stepIndex = idx + 1 }
    fun back() { if (idx > 0) state.stepIndex = idx - 1 }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        val screenH = constraints.maxHeight.toFloat()
        val centerRect = Rect(constraints.maxWidth / 2f, screenH / 2f, constraints.maxWidth / 2f, screenH / 2f)
        var lastRect by remember { mutableStateOf<Rect?>(null) }
        if (targetRect != null) lastRect = targetRect
        val baseRect = targetRect ?: lastRect ?: centerRect

        // --- spotlight animations -------------------------------------------
        val glide = tween<Float>(durationMillis = 360, easing = FastOutSlowInEasing)
        val al by animateFloatAsState(baseRect.left, glide, label = "l")
        val at by animateFloatAsState(baseRect.top, glide, label = "t")
        val ar by animateFloatAsState(baseRect.right, glide, label = "r")
        val ab by animateFloatAsState(baseRect.bottom, glide, label = "b")
        val cut by animateFloatAsState(if (targetRect != null) 1f else 0f, tween(280), label = "cut")
        // Breathing: a slow in/out pulse on the border only (the spotlight size
        // changes only when it glides between targets, never on its own).
        val infinite = rememberInfiniteTransition(label = "breathe")
        val breathe by infinite.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val pad = 8.dp.toPx()
            val l = (al - pad).coerceAtLeast(0f)
            val t = (at - pad).coerceAtLeast(0f)
            val rt = (ar + pad).coerceAtMost(size.width)
            val b = (ab + pad).coerceAtMost(size.height)
            val scrim = scrimColor.copy(alpha = 0.74f)
            val cornerRpx = 12.dp.toPx()
            // Scrim cut as a single even-odd path: outer rect minus the
            // rounded spotlight, so the hole has the same corner radius as
            // the accent border stroke. The previous 4-rect outer cut left
            // a rectangular hole with the rounded stroke sitting inside it,
            // and the rectangular corners poked out past the stroke.
            if (b - t > 0f && rt - l > 0f) {
                val cutoutPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        RoundRect(
                            left = l, top = t, right = rt, bottom = b,
                            cornerRadius = CornerRadius(cornerRpx),
                        )
                    )
                }
                drawPath(cutoutPath, color = scrim)
                if (cut < 1f) {
                    drawRoundRect(
                        color = scrimColor.copy(alpha = 0.74f * (1f - cut)),
                        topLeft = Offset(l, t),
                        size = Size(rt - l, b - t),
                        cornerRadius = CornerRadius(cornerRpx),
                    )
                }
                if (cut > 0.01f) {
                    drawRoundRect(
                        color = accent.copy(alpha = cut * (0.55f + 0.45f * breathe)),
                        topLeft = Offset(l, t),
                        size = Size(rt - l, b - t),
                        cornerRadius = CornerRadius(cornerRpx),
                        style = Stroke(width = (2f + 1.5f * breathe).dp.toPx())
                    )
                }
            } else {
                // No spotlight target yet (intro / outro): cover the whole screen.
                drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, size.height))
            }
        }

        // --- tooltip card: glides to the opposite half from the target -------
        val targetBias = when {
            targetRect == null -> 0f
            targetRect.center.y < screenH / 2f -> 1f   // target up top -> card bottom
            else -> -1f                                 // target low -> card top
        }
        val bias by animateFloatAsState(targetBias, tween(380, easing = FastOutSlowInEasing), label = "bias")

        Surface(
            modifier = Modifier
                .align(BiasAlignment(0f, bias))
                // Keep the card off every edge. The overlay lives inside a
                // Scaffold that CONSUMES system-bar insets, so
                // windowInsetsPadding(safeDrawing) would be 0 here. Reading
                // asPaddingValues() ignores consumption and returns the full
                // status-bar / display-cutout / gesture-nav insets; we then add
                // a generous fixed margin so the card is clearly inset.
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 18.dp, vertical = 28.dp)
                .widthIn(max = 420.dp)
                .heightIn(max = maxHeight * 0.82f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Crossfade(targetState = idx, animationSpec = tween(260), label = "tutorial-card") { i ->
                    val s = steps[i]
                    Column {
                        Text(
                            stringResource(R.string.welcome_tut_step, i + 1, steps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (s.showShare) {
                            // Backup + leaderboards mention: plain bodyLarge
                            // line (same chrome as every other tour step), no
                            // title, no bullets. The eucviewer.ried.no and
                            // eucstats.ried.no markers in the localised
                            // string become tappable links via
                            // annotateRiedLinks.
                            Spacer(Modifier.height(8.dp))
                            Text(
                                annotateRiedLinks(
                                    stringResource(
                                        R.string.welcome_tut_share,
                                        stringResource(R.string.tab_cloud),
                                        stringResource(R.string.recording_title),
                                    )
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else if (s.showClip) {
                            // Outro: clip + a tidy title and bulleted tips.
                            // The "save your trips" line moved out into the
                            // dedicated share step above so the outro can
                            // focus on the long-tail tips and the sign-off.
                            Spacer(Modifier.height(12.dp))
                            LoopingClip(
                                assetName = "welcome_clip.webp",
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(230.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.welcome_tut_outro_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(10.dp))
                            OutroBullet(stringResource(R.string.welcome_tut_outro_buttons))
                            Spacer(Modifier.height(8.dp))
                            OutroBullet(
                                stringResource(
                                    R.string.welcome_tut_outro_alarms,
                                    stringResource(R.string.tab_alarms)
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            OutroBullet(stringResource(R.string.welcome_tut_outro_integrations))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.welcome_tut_outro_end),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(s.text, style = MaterialTheme.typography.bodyLarge)
                            // Step 0: voice opt-in. Same row pattern as every other
                            // SwitchSetting in Settings (label on the left, themed
                            // Switch on the right) + a HintText below, so this card
                            // matches the rest of the app one-for-one. Flipping ON
                            // applies all 8 announce flags and speaks the welcome
                            // line as live confirmation; OFF reverts.
                            if (i == 0) {
                                Spacer(Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.welcome_tut_voice_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Switch(
                                        checked = voicesOptIn,
                                        onCheckedChange = {
                                            voicesOptIn = it
                                            onVoiceToggle(it)
                                        },
                                        colors = themedSwitchColors(),
                                    )
                                }
                                HintText(stringResource(R.string.welcome_tut_voice_desc))

                                // Dev-only quick tools (branch builds only). Set the
                                // backup folder first; the rest depend on it and appear
                                // once it is set.
                                if (isDev) {
                                    Spacer(Modifier.height(16.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.welcome_tut_dev_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    // Same button style as the Cloud settings actions
                                    // (LeftAlignedScanButton: a filled button on the left
                                    // half of the row).
                                    // Same actions and order as the Cloud settings. Set
                                    // backup folder shows only until a folder is set; every
                                    // other action (Dropbox included) needs a folder, so they
                                    // appear only once one is set.
                                    if (!backupFolderSet) {
                                        Spacer(Modifier.height(8.dp))
                                        LeftAlignedScanButton(
                                            label = stringResource(R.string.welcome_tut_dev_set_folder),
                                            onClick = onSetBackupFolder,
                                        )
                                    } else {
                                        // Link Dropbox, until Dropbox is linked.
                                        if (!dropboxLinked) {
                                            Spacer(Modifier.height(8.dp))
                                            LeftAlignedScanButton(
                                                label = stringResource(R.string.dropbox_link),
                                                onClick = onLinkDropbox,
                                            )
                                        }
                                        // Restore, only when a settings backup exists.
                                        // Disabled while a trip sync is running: restore
                                        // rewrites the whole settings blob and both touch
                                        // the same backup folder, so letting them overlap
                                        // can lose settings or read a half-written file.
                                        if (hasSettingsBackup) {
                                            Spacer(Modifier.height(8.dp))
                                            LeftAlignedScanButton(
                                                label = stringResource(R.string.welcome_tut_dev_restore),
                                                onClick = onRestoreSettings,
                                                enabled = !syncRunning,
                                            )
                                        }
                                        // Sync and Join just trigger the Cloud-settings
                                        // action and return; the work runs in the
                                        // background, so the wizard shows no progress.
                                        Spacer(Modifier.height(8.dp))
                                        LeftAlignedScanButton(
                                            label = stringResource(R.string.welcome_tut_dev_sync_trips),
                                            onClick = onSyncTrips,
                                            enabled = !syncRunning,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        LeftAlignedScanButton(
                                            label = stringResource(R.string.welcome_tut_dev_join),
                                            onClick = onJoinLeaderboards,
                                            enabled = !leaderboardsJoined,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The last step is the sign-off: no Skip there.
                    if (!isLast) {
                        TextButton(onClick = {
                            // Dev builds skip straight out: a developer already knows the
                            // tour, so don't make them confirm. Normal builds still ask.
                            if (isDev) onFinish() else showSkipConfirm = true
                        }) {
                            Text(stringResource(R.string.welcome_tut_skip))
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (idx > 0) {
                            TextButton(onClick = { back() }) {
                                Text(stringResource(R.string.welcome_tut_prev))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Button(onClick = { advance() }) {
                            Text(
                                stringResource(
                                    if (isLast) R.string.welcome_tut_done else R.string.welcome_tut_next
                                )
                            )
                        }
                    }
                }
            }
        }

        if (showSkipConfirm) {
            AlertDialog(
                onDismissRequest = { showSkipConfirm = false },
                title = { Text(stringResource(R.string.welcome_tut_skip_title)) },
                text = { Text(stringResource(R.string.welcome_tut_skip_body)) },
                confirmButton = {
                    TextButton(onClick = { showSkipConfirm = false; onFinish() }) {
                        Text(stringResource(R.string.welcome_tut_skip_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}
