package com.eried.eucplanet.ui.charging

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.repository.MetricSample
import com.eried.eucplanet.ui.dashboard.MetricGraph
import com.eried.eucplanet.ui.theme.appColors
import com.eried.eucplanet.util.GraphBounds
import com.eried.eucplanet.util.GraphScale
import java.util.Date
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

// How far the animated % may run ahead of the last reading, and its easing.
private const val MAX_LEAD = 1.2f
private const val EASE_TAU = 0.6f
private const val FLUID_COLS = 40

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingMonitorScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: ChargingMonitorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val ctx = LocalContext.current
    val charging = state.status == ChargeStatus.Charging || state.status == ChargeStatus.Full

    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val pct = rememberAnimatedPercent(state.percent, state.ratePctPerMin, charging, full = state.status == ChargeStatus.Full)
    // Live clock so the ETA counts down in real time between telemetry frames.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val clockAt: (Long) -> String = { ms ->
        android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ms))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.charging_monitor)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.charging_back),
                        )
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.charging_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.charging_estimate_to_full)) },
                            onClick = {
                                viewModel.setEstimateToFull(!state.estimateToFull)
                                menuOpen = false
                            },
                            trailingIcon = {
                                if (state.estimateToFull) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.battery_history)) },
                            onClick = {
                                menuOpen = false
                                onOpenHistory()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = {
                                menuOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.appColors.topBar,
                ),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BatteryFillGraphic(
                percentProvider = { pct.value },
                startPercent = state.startPercent,
                charging = charging,
                connected = state.connected,
                estimateToFull = state.estimateToFull,
                // Deep blue main liquid + a more neon green for the freshly-added band.
                base = lerp(MaterialTheme.appColors.metricVoltage, Color.Black, 0.28f),
                added = lerp(MaterialTheme.appColors.metricBattery, Color.Green, 0.4f),
                outlineColor = MaterialTheme.appColors.textPrimary,
                bubbleColor = MaterialTheme.appColors.onPrimary,
                modifier = Modifier.fillMaxSize(),
            )

            // Show the % whenever a wheel is connected; the prediction only while
            // charging (idle = just the % over the battery).
            // Big % sits on the 80 % line ("-" when no wheel); prediction on the 60 % line.
            Box(
                modifier = Modifier
                    .align(BiasAlignment(0f, -0.5f))
                    .padding(horizontal = 24.dp),
            ) {
                PercentReadout(
                    pct,
                    // No decimals at 100 % — it reads a clean "100%".
                    decimalsVisible = charging && state.warmedUp && state.status != ChargeStatus.Full && state.percent < 99.5f,
                    connected = state.connected,
                )
            }
            if (state.connected && charging) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(BiasAlignment(0f, -0.1f))
                        .padding(horizontal = 24.dp),
                ) {
                    PredictionText(state, nowMs, clockAt)
                }
            }

            // Single entry to the details flyout.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showSheet = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val detailsColor = MaterialTheme.appColors.textPrimary
                val detailsGlow = TextStyle(
                    shadow = Shadow(
                        color = if (MaterialTheme.appColors.isLight) Color.White else Color.Black,
                        blurRadius = 14f,
                    ),
                )
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = detailsColor,
                )
                Text(
                    stringResource(R.string.charging_details),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = detailsColor,
                    style = detailsGlow,
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.appColors.sheetBackground,
        ) {
            InfoTabs(state)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Big percentage with a smooth grow/shrink and a soft pink glow. */
@Composable
private fun PercentReadout(pctState: State<Float>, decimalsVisible: Boolean, connected: Boolean = true) {
    val mainSp by animateFloatAsState(
        if (decimalsVisible) 112f else 144f,
        animationSpec = tween(700, easing = FastOutSlowInEasing), label = "mainSp",
    )
    val decSp by animateFloatAsState(
        if (decimalsVisible) 46f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing), label = "decSp",
    )
    val decAlpha by animateFloatAsState(
        if (decimalsVisible) 1f else 0f,
        animationSpec = tween(700, easing = FastOutSlowInEasing), label = "decAlpha",
    )

    val value = pctState.value
    val whole = value.toInt().coerceIn(0, 100)
    val frac = ((value - whole) * 1000).toInt().coerceIn(0, 999)
    // Dark theme: white text + black shadow; light theme: black text + white
    // shadow. Keeps the number legible over the gradient in either theme.
    val glow = TextStyle(
        shadow = Shadow(
            color = if (MaterialTheme.appColors.isLight) Color.White else Color.Black,
            offset = Offset(0f, 0f),
            blurRadius = 18f,
        ),
    )

    // Baseline-align the whole number, the decimals and the "%" so they sit on
    // one line instead of being nudged with per-element bottom padding.
    Row {
        if (connected) {
            Text("$whole", fontSize = mainSp.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.appColors.textPrimary, style = glow, modifier = Modifier.alignByBaseline())
            if (decAlpha > 0.01f) {
                Text(
                    ".%03d".format(frac),
                    fontSize = decSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.appColors.textPrimary,
                    style = glow,
                    modifier = Modifier.alignByBaseline().alpha(decAlpha),
                )
            }
        } else {
            Text("--", fontSize = mainSp.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.appColors.textPrimary, style = glow, modifier = Modifier.alignByBaseline())
        }
        Text(
            "%",
            fontSize = (mainSp * 0.45f).sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.appColors.textPrimary,
            style = glow,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun PredictionText(state: ChargingUiState, nowMs: Long, clockAt: (Long) -> String) {
    val color = MaterialTheme.appColors.textPrimary
    val glow = TextStyle(
        shadow = Shadow(
            color = if (MaterialTheme.appColors.isLight) Color.White else Color.Black,
            offset = Offset(0f, 0f),
            blurRadius = 16f,
        ),
    )
    // Countdown: "<1 min", "44 min" up to "99 min", then "1h 40m".
    fun mmss(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        if (sec < 60L) return "<1 min"
        val totalMin = kotlin.math.ceil(sec / 60.0).toLong()
        if (totalMin < 100L) return "$totalMin min"
        val h = totalMin / 60L
        val m = totalMin % 60L
        return if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
    // At 100 % there's nothing to say — the big number means full (the wheel may
    // even stop reporting "charging"; we just keep showing 100 %). The countdown is
    // dropped here too, so it never reaches 0:00 / goes negative.
    if (state.status == ChargeStatus.Full || state.percent >= 99.5f) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            // Show ONLY the relevant estimate — to the target until reached, then to
            // 100 %. Hidden once the countdown elapses, or while still warming up.
            !state.estimateToFull && state.targetEtaMs != null && state.targetEtaMs!! - nowMs > 0L -> {
                val eta = state.targetEtaMs!!
                Text(
                    stringResource(R.string.charging_eta_to_target, mmss(eta - nowMs), state.targetPercent.roundToInt()),
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color, style = glow,
                )
                Text(stringResource(R.string.charging_eta_at, clockAt(eta)), fontSize = 26.sp, color = color, style = glow)
            }
            state.fullEtaMs != null && state.fullEtaMs!! - nowMs > 0L -> {
                val eta = state.fullEtaMs!!
                Text(
                    stringResource(R.string.charging_eta_to_full, mmss(eta - nowMs)),
                    fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color, style = glow,
                )
                Text(stringResource(R.string.charging_eta_at, clockAt(eta)), fontSize = 26.sp, color = color, style = glow)
            }
            // Charging but not warmed up yet, or the countdown already elapsed.
            else ->
                Text(stringResource(R.string.charging_charging), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color, style = glow)
        }
    }
}

/**
 * A smoothly-animated battery percentage — eases toward a projection of the true
 * value (last reading + rate × elapsed, capped at +[MAX_LEAD]) so it ticks at the
 * charge rate, catches up to fresh readings, never reverses, never stalls. Snaps
 * to the reading when not charging.
 */
@Composable
private fun rememberAnimatedPercent(measured: Float, ratePerMin: Float, charging: Boolean, full: Boolean): State<Float> {
    val measuredState = rememberUpdatedState(measured)
    val rateState = rememberUpdatedState(ratePerMin)
    val chargingState = rememberUpdatedState(charging)
    val fullState = rememberUpdatedState(full)
    val display = remember { mutableFloatStateOf(measured) }

    LaunchedEffect(Unit) {
        var prevFrame = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (prevFrame == 0L) 0f else ((now - prevFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
                prevFrame = now
                val m = measuredState.value
                val d = display.floatValue
                when {
                    // End of charge — rush hard to 100 % (fast, but not an instant jump).
                    fullState.value -> {
                        if (dt > 0f) {
                            val v = ((100f - d) * 1.8f).coerceAtLeast(0.8f)
                            display.floatValue = (d + v * dt).coerceAtMost(100f)
                        }
                    }
                    !chargingState.value -> display.floatValue = m
                    // Snap a big jump (first real value after the 0 default, or reconnect).
                    kotlin.math.abs(m - d) > 3f -> display.floatValue = m
                    dt > 0f -> {
                        // Climb at the charge rate plus a gentle pull toward the true %,
                        // with the TOTAL speed capped at ~1.6× the rate — so it never
                        // lurches on a stepped reading and eases as it nears the value.
                        val rps = (rateState.value / 60f).coerceAtLeast(0f)
                        val maxV = (rps * 1.6f).coerceAtLeast(0.02f)
                        val v = (rps + (m - d) * 0.25f).coerceIn(0f, maxV)
                        display.floatValue = (d + v * dt)
                            .coerceAtMost(m + MAX_LEAD).coerceAtLeast(d).coerceIn(0f, 100f)
                    }
                }
            }
        }
    }
    return display
}

/** Battery % → fill fraction (0..1) — linear and precise (46 % = 46 % height). */
private fun fillFraction(pct: Float): Float = (pct / 100f).coerceIn(0f, 1f)

/**
 * The battery: a lightly-rounded tall rectangle (no terminal nub) with a faint
 * rounded-cell texture. While charging the fill rises to the current % with a
 * pink→green vertical gradient (green leading the top like fresh charge; the pink
 * fades toward green as the pack nears 80–100 %). When idle the whole battery is
 * pink and the bubbles just float. Rising bubbles ease in/out with the charge.
 */
@Composable
private fun BatteryFillGraphic(
    percentProvider: () -> Float,
    startPercent: Float,
    charging: Boolean,
    connected: Boolean,
    estimateToFull: Boolean,
    base: Color,
    added: Color,
    outlineColor: Color,
    bubbleColor: Color,
    modifier: Modifier = Modifier,
) {
    // Green amount: eases in (~4 s) when charging starts and fades out slowly
    // (~60 s) when it stops — a brief pause keeps the green, a long stop merges
    // back to all blue. Drives the fill colour and the dot rise.
    val g = animateFloatAsState(
        if (charging) 1f else 0f,
        animationSpec = tween(durationMillis = if (charging) 4000 else 60000, easing = FastOutSlowInEasing),
        label = "g",
    )
    // The neon glow stops quickly when charging ends.
    val glowAmt = animateFloatAsState(
        if (charging) 1f else 0f,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "glow",
    )
    // Frozen session-start level so the boundary doesn't jump when the VM zeroes
    // startPercent off-charge; it just fades out with g.
    val frozenStart = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(charging, startPercent) {
        if (charging) frozenStart.floatValue = (startPercent / 100f).coerceIn(0f, 1f)
    }
    val rise = remember { mutableFloatStateOf(0f) }
    val clock = remember { mutableFloatStateOf(0f) }

    // --- Fluid surface: the top of the liquid sloshes with the phone's tilt
    // (accelerometer gravity) and rotation (gyroscope). ---
    val ctx = LocalContext.current
    val surfH = remember { FloatArray(FLUID_COLS + 1) }
    val surfV = remember { FloatArray(FLUID_COLS + 1) }
    val tiltX = remember { mutableFloatStateOf(0f) }
    val gyroKick = remember { mutableFloatStateOf(0f) }
    // Splash droplets thrown off fast crests; gravity (from tilt) pulls them back.
    val maxDrops = 220
    val dpx = remember { FloatArray(maxDrops) }
    val dpy = remember { FloatArray(maxDrops) }
    val dvx = remember { FloatArray(maxDrops) }
    val dvy = remember { FloatArray(maxDrops) }
    val dlife = remember { FloatArray(maxDrops) }
    val geo = remember { FloatArray(3) }   // [w, h, yTop] published by the Canvas
    val rnd = remember { java.util.Random() }
    // Faint bubbles drifting up through the liquid.
    val maxBubbles = 140
    val bubX = remember { FloatArray(maxBubbles) }
    val bubY = remember { FloatArray(maxBubbles) { -1f } }
    val bubR = remember { FloatArray(maxBubbles) }
    val bubS = remember { FloatArray(maxBubbles) }
    val bubA = remember { FloatArray(maxBubbles) }
    val bubF = remember { FloatArray(maxBubbles) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                when (e.sensor.type) {
                    android.hardware.Sensor.TYPE_ACCELEROMETER ->
                        tiltX.floatValue = (e.values[0] / 9.81f).coerceIn(-1f, 1f)
                    android.hardware.Sensor.TYPE_GYROSCOPE ->
                        gyroKick.floatValue = (gyroKick.floatValue - e.values[1]).coerceIn(-12f, 12f)
                }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm?.unregisterListener(listener) }
    }

    LaunchedEffect(Unit) {
        var prev = 0L
        while (true) {
            withFrameNanos { now ->
                if (prev != 0L) {
                    val dt = ((now - prev) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    rise.floatValue = (rise.floatValue + g.value * 0.04f * dt) % 1f
                    val tilt = tiltX.floatValue
                    val kick = gyroKick.floatValue
                    gyroKick.floatValue = kick * 0.86f
                    val cx = FLUID_COLS / 2f
                    // Surface: spring toward the gravity-tilt line + neighbour
                    // coupling (travelling waves) + damping; the gyro injects slosh.
                    for (i in 0..FLUID_COLS) {
                        val pos = (i - cx) / cx
                        val target = tilt * pos * 100f
                        val left = surfH[if (i > 0) i - 1 else i]
                        val right = surfH[if (i < FLUID_COLS) i + 1 else i]
                        // Softer spring + stronger neighbour coupling + lighter
                        // damping = a looser, wavier, more fluid surface.
                        val accel = (target - surfH[i]) * 9f +
                            (left + right - 2f * surfH[i]) * 22f +
                            kick * pos * 80f
                        surfV[i] = (surfV[i] + accel * dt) * 0.975f
                    }
                    for (i in 0..FLUID_COLS) {
                        surfH[i] = (surfH[i] + surfV[i] * dt).coerceIn(-220f, 220f)
                    }
                    // Splash droplets: fast-rising crests throw drops, gravity (tilt)
                    // pulls them back; they ripple the surface where they land.
                    val ww = geo[0]
                    val topY = geo[2]
                    if (ww > 1f) {
                        val gxA = tilt * 1700f
                        val gyA = 1700f
                        var spawned = 0
                        for (i in 0..FLUID_COLS) {
                            if (spawned >= 8) break
                            if (surfV[i] < -160f && rnd.nextFloat() < 0.85f) {
                                var s = -1
                                for (k in 0 until maxDrops) if (dlife[k] <= 0f) { s = k; break }
                                if (s >= 0) {
                                    dpx[s] = ww * i / FLUID_COLS
                                    dpy[s] = topY + surfH[i]
                                    dvy[s] = surfV[i] * 0.85f - rnd.nextFloat() * 80f
                                    dvx[s] = (rnd.nextFloat() - 0.5f) * 200f + tilt * 140f
                                    dlife[s] = 0.6f + rnd.nextFloat() * 0.7f
                                    spawned++
                                }
                            }
                        }
                        for (k in 0 until maxDrops) {
                            if (dlife[k] <= 0f) continue
                            dvx[k] += gxA * dt
                            dvy[k] += gyA * dt
                            dpx[k] += dvx[k] * dt
                            dpy[k] += dvy[k] * dt
                            dlife[k] -= dt
                            val ci = ((dpx[k] / ww) * FLUID_COLS).toInt().coerceIn(0, FLUID_COLS)
                            if (dpy[k] >= topY + surfH[ci] && dvy[k] > 0f) {
                                surfV[ci] += 40f
                                dlife[k] = 0f
                            }
                            if (dpx[k] < -30f || dpx[k] > ww + 30f) dlife[k] = 0f
                        }
                        // Bubble animation disabled for now (kept for easy re-enable).
                    }
                    // Energy gate: only advance the clock (and therefore keep
                    // forcing Canvas redraws / withFrameNanos wake-ups) while
                    // there is actually something visible animating. Once the
                    // surface settles and there are no active droplets, the
                    // loop pauses; a sensor event re-invalidates the Canvas
                    // (it reads tiltX / gyroKick) and the loop resumes.
                    var active = false
                    for (i in 0..FLUID_COLS) {
                        if (kotlin.math.abs(surfV[i]) > 1f || kotlin.math.abs(surfH[i]) > 0.5f) {
                            active = true; break
                        }
                    }
                    if (!active) {
                        for (k in 0 until maxDrops) if (dlife[k] > 0f) { active = true; break }
                    }
                    if (!active && kotlin.math.abs(kick) > 0.05f) active = true
                    if (active || g.value > 0.01f) clock.floatValue += dt
                }
                prev = now
            }
        }
    }

    Canvas(modifier) {
        val percent = percentProvider()
        // Subscribe to clock + the two sensor channels so:
        //  - while the simulation is active the per-frame clock tick invalidates
        //    this Canvas, requesting another frame (loop self-sustains);
        //  - while the surface is settled the loop pauses (no clock advance, no
        //    redraw, no CPU) -- but a fresh sensor event mutates tiltX /
        //    gyroKick, which invalidates Canvas, requests a frame, and wakes
        //    the LaunchedEffect's withFrameNanos again.
        @Suppress("UNUSED_VARIABLE") val tick = clock.floatValue + tiltX.floatValue + gyroKick.floatValue
        val w = size.width
        val h = size.height
        // Whole-screen battery: the fill IS the background — no frame, edge to edge.
        val fillLeft = 0f
        val fillRight = w
        val fillTop = 0f
        val fillBottom = h
        val fillW = w
        val fillH = h
        fun yFor(frac: Float) = fillBottom - frac.coerceIn(0f, 1f) * fillH

        val gv = g.value
        val idleAmount = 1f - gv
        // Linear, precise fill to the actual battery level.
        val curFrac = fillFraction(percent)
        val yTop = yFor(curFrac)
        val regionH = (fillBottom - yTop).coerceAtLeast(1f)
        geo[0] = w; geo[1] = h; geo[2] = yTop
        // Session-start level — frozen so it doesn't jump when charging stops; it
        // just fades out with gv. Clamped to the current level.
        val startFrac = frozenStart.floatValue.coerceIn(0f, curFrac)
        val yStart = yFor(startFrac)

        // Long three-zone gradient, all fading to navy as gv → 0:
        //   surface → start level : bright (neon) green
        //   start level → N        : bright → darker green → navy
        //   N → bottom             : navy
        // N sits between the start level and 0; its spread widens with SOC (about
        // half-way down near 100 %, almost no spread around 50 %).
        val greenC = lerp(base, added, gv)
        // Slightly darker green at the start of the transition (just below the
        // previous-SOC line); it then blends down to navy.
        val darkerG = lerp(base, lerp(added, Color.Black, 0.25f), gv)
        val soc = curFrac
        // Transition bottom (N) moves with SOC: tiny near 0 %, giant (almost the
        // whole region below the start level) near 100 %. Never 0.
        val spread = (startFrac * soc).coerceAtLeast(0.04f)
        val transBottom = (startFrac - spread).coerceAtLeast(0f)
        val yTransBottom = yFor(transBottom)

        val fillClip = Path().apply { addRect(Rect(0f, 0f, w, h)) }
        clipPath(fillClip) {
            // Liquid only when a wheel is connected (else just the empty gauge).
            if (connected) {
            // Fill — one gradient (no seam): solid green to the start level, a
            // narrow green→blue band, then solid blue.
            val denom = (fillBottom - yTop).coerceAtLeast(1f)
            val fStart = ((yStart - yTop) / denom).coerceIn(0.001f, 0.98f)
            val fTransB = ((yTransBottom - yTop) / denom).coerceIn(fStart + 0.005f, 0.999f)
            // Neon band down to the start level, then slightly-darker-green → navy.
            val dGStart = (fStart + 0.02f).coerceAtMost(fTransB - 0.001f)
            val fillBrush = Brush.verticalGradient(
                0f to greenC.copy(alpha = 0.95f),
                fStart to greenC.copy(alpha = 0.95f),
                dGStart to darkerG.copy(alpha = 0.93f),
                fTransB to base.copy(alpha = 0.9f),
                1f to base.copy(alpha = 0.9f),
                startY = yTop, endY = fillBottom,
            )
            // Smooth, sensor-driven curvy surface: a quadratic spline through the
            // wave points (control = each point, ends at the midpoints) so the top
            // reads as a flowing liquid rather than straight segments.
            val crest = Path()
            crest.moveTo(fillLeft, yTop + surfH[0])
            for (i in 0 until FLUID_COLS) {
                val x1 = fillLeft + fillW * i / FLUID_COLS
                val y1 = yTop + surfH[i]
                val x2 = fillLeft + fillW * (i + 1) / FLUID_COLS
                val y2 = yTop + surfH[i + 1]
                crest.quadraticBezierTo(x1, y1, (x1 + x2) / 2f, (y1 + y2) / 2f)
            }
            crest.lineTo(fillRight, yTop + surfH[FLUID_COLS])
            val liquid = Path()
            liquid.addPath(crest)
            liquid.lineTo(fillRight, fillBottom)
            liquid.lineTo(fillLeft, fillBottom)
            liquid.close()
            drawPath(liquid, brush = fillBrush)
            // Foam crest highlight along the surface.
            drawPath(crest, color = lerp(greenC, Color.White, 0.4f).copy(alpha = 0.55f), style = Stroke(width = 2.5f))
            }

            // --- Scale ticks: short marks on the LEFT and RIGHT edges only; the
            // 10 % ticks are longer and a touch thicker. ---
            for (p in 1..99) {
                val y = yFor(p / 100f)
                val major = p % 10 == 0
                val len = fillW * (if (major) 0.12f else 0.05f)
                val lw = (if (major) 2.5f else 1.5f).dp.toPx()
                val onFill = y >= yTop
                val col = if (onFill) Color.White.copy(alpha = if (major) 0.55f else 0.30f)
                          else outlineColor.copy(alpha = if (major) 0.22f else 0.10f)
                val bm = if (onFill) BlendMode.Difference else BlendMode.SrcOver
                drawRect(color = col, topLeft = Offset(fillLeft, y - lw / 2f), size = Size(len, lw), blendMode = bm)
                drawRect(color = col, topLeft = Offset(fillRight - len, y - lw / 2f), size = Size(len, lw), blendMode = bm)
            }
            // Dotted line at the 80 % target (joins the side ticks); hidden when
            // estimating straight to 100 %.
            if (connected && !estimateToFull) {
                val y80 = yFor(0.8f)
                // Inset past the side ticks so the dashes sit in the middle with a gap.
                val inset = fillW * 0.14f
                drawLine(
                    color = outlineColor.copy(alpha = 0.4f),
                    start = Offset(fillLeft + inset, y80),
                    end = Offset(fillRight - inset, y80),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                )
            }
            /* Previous centred full-width scale (kept for reference):
            val cxFill = (fillLeft + fillRight) / 2f
            for (p in 0..100) {
                if (p == 0 || p == 100) continue
                val y = yFor(p / 100f)
                val major = p % 10 == 0
                val lw = (if (major) 1.5f else 1f).dp.toPx()
                val ww = fillW * (if (major) 0.7f else 0.5f)
                val tl = Offset(cxFill - ww / 2f, y - lw / 2f)
                val sz = Size(ww, lw)
                if (y < yTop) drawRect(outlineColor.copy(alpha = if (major) 0.18f else 0.07f), tl, sz)
                else drawRect(Color.White.copy(alpha = if (major) 0.5f else 0.28f), tl, sz, blendMode = BlendMode.Difference)
            }
            */
            // Subtle pulsing glow inside the neon (added) segment only — starts at
            // the surface and fades downward; never extends above the SOC line.
            if (glowAmt.value > 0.01f && yStart > yTop) {
                val pulse = 0.5f + 0.5f * sin(clock.floatValue * 2.5f)
                val ga = glowAmt.value * (0.12f + 0.22f * pulse)
                val gDown = (yStart - yTop).coerceAtMost(40.dp.toPx())
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(added.copy(alpha = ga), Color.Transparent),
                        startY = yTop, endY = yTop + gDown,
                    ),
                    topLeft = Offset(fillLeft, yTop),
                    size = Size(fillW, gDown),
                )
            }

            // Bubble animation disabled for now.
        }
        // Splash droplets -- only when connected.
        if (connected) {
            for (k in 0 until maxDrops) {
                if (dlife[k] <= 0f) continue
                val a = dlife[k].coerceIn(0f, 1f)
                drawCircle(greenC.copy(alpha = 0.85f * a), radius = 2.5f + 2.5f * a, center = Offset(dpx[k], dpy[k]))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoTabs(state: ChargingUiState) {
    // NOTE (future): we deliberately don't embed the full MetricDetail battery
    // history as a tab here. The Charge tab already reuses the same MetricGraph,
    // but the dashboard battery widget's windowed min/max/avg (over the
    // personalization time range) has no equivalent in this connection-scoped
    // session — revisit if we add a dedicated History tab.
    // Dynamic tabs — only show what this wheel actually reports.
    val tabs = buildList {
        add(stringResource(R.string.charging_tab_charge) to "charge")
        if (state.hasPacks) add(stringResource(R.string.charging_tab_packs) to "packs")
        if (state.hasRealCurrent) add(stringResource(R.string.charging_tab_power) to "power")
        // Smart-BMS wheels (Lynx / Sherman L / Oryx / NOSFET / Patton-with-BMS)
        // report per-cell voltages; everyone else has empty packs and skips it.
        if (state.bms.hasCells) add(stringResource(R.string.charging_tab_cells) to "cells")
    }
    var selected by remember { mutableIntStateOf(0) }
    if (selected >= tabs.size) selected = 0

    // Cells tab opens taller than the other tabs (128 cells need room on V14
    // 4-pack rigs and Veteran-family 42-cell packs); content scrolls vertically
    // inside that height. Other tabs keep the fixed 280 dp so flicking between
    // them doesn't jiggle the sheet height.
    val configuration = LocalConfiguration.current
    val screenH = configuration.screenHeightDp.dp
    val cellsH = (screenH * 0.75f).coerceIn(420.dp, (screenH * 0.9f).coerceAtLeast(500.dp))
    val isCellsTab = tabs.getOrNull(selected)?.second == "cells"
    val contentH = if (isCellsTab) cellsH else 280.dp

    // Hoisted so the count survives Cells <-> Packs tab switches. Initialize
    // from the already-cached BmsState so opening the bottom sheet on a wheel
    // that's been connected a while doesn't briefly collapse to the 2-pack
    // telemetry fallback while the debounce climbs back up to 4. The debounce
    // only delays GROWTH past the initial value (when new packs arrive during
    // this sheet session), so a 4-pack wheel opens straight at 4.
    val bmsPacksReady = state.bms.packs.count { it.knownCells.isNotEmpty() }
    var stableBmsCount by remember { mutableIntStateOf(bmsPacksReady) }
    LaunchedEffect(bmsPacksReady) {
        if (bmsPacksReady > stableBmsCount) {
            kotlinx.coroutines.delay(2500)
            stableBmsCount = bmsPacksReady
        } else if (bmsPacksReady < stableBmsCount) {
            // Wheel disconnect / swap: drop immediately.
            stableBmsCount = bmsPacksReady
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PrimaryTabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = selected == i, onClick = { selected = i }, text = { Text(t.first) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(contentH)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (tabs.getOrNull(selected)?.second) {
                    "charge" -> {
                        // Charge % (blue, like the battery fill, left scale) +
                        // voltage (green, own scale).
                        // Two prediction markers — one at the 80 % target and
                        // one at 100 % — taken from the MOST RECENT prediction
                        // snapshot. Earlier snapshots were also being plotted
                        // (one dot per snapshot at each level) but the cluster
                        // got dense fast on long sessions and obscured the
                        // chart instead of helping the rider. The single
                        // current-prediction dot is the actionable read: "if
                        // I keep charging at this rate, here's when it lands."
                        val predictionMarkers = remember(state.predictionHistory) {
                            val latest = state.predictionHistory.lastOrNull()
                            buildList {
                                latest?.targetEtaMs?.let {
                                    add(com.eried.eucplanet.ui.dashboard.PredictionMarker(it, 80f))
                                }
                                latest?.fullEtaMs?.let {
                                    add(com.eried.eucplanet.ui.dashboard.PredictionMarker(it, 100f))
                                }
                            }
                        }
                        ChargingChart(
                            state.chargeHistory,
                            MaterialTheme.appColors.metricVoltage,
                            "%",
                            baselineValue = state.startPercent,
                            baselineColor = MaterialTheme.appColors.hint,
                            series2 = state.voltageHistory,
                            color2 = MaterialTheme.appColors.metricBattery,
                            unit2 = "V",
                            predictionMarkers = predictionMarkers.takeIf { it.isNotEmpty() },
                        ) { _, _ -> GraphScale.fixed(0f, 100f) }
                        Spacer(Modifier.height(8.dp))
                        StatRow(stringResource(R.string.charging_stat_added), "%+.1f%%".format(state.addedPercent))
                        // Rate: prefer W (EV/charger convention) when the wheel reports a
                        // real current; %/min becomes the secondary read either way so the
                        // sign of a discharging slope is still visible.
                        val rateText = buildString {
                            state.powerW?.takeIf { it > 0 }?.let { append("%d W  ·  ".format(it)) }
                            append("%+.2f %%/min".format(state.ratePctPerMin))
                        }
                        StatRow(stringResource(R.string.charging_stat_rate), rateText)
                        // Energy integrated this session, sign matches V*I (charging +,
                        // discharging −). Only shown when the wheel reports a real
                        // current -- on V14-class wheels (~0 A while charging) we'd
                        // accumulate ~0 Wh which would be misleading.
                        if (state.hasRealCurrent) {
                            val wh = state.energyWh
                            val energyText = if (kotlin.math.abs(wh) >= 1000f) "%+.2f kWh".format(wh / 1000f)
                                              else "%+.0f Wh".format(wh)
                            StatRow(stringResource(R.string.charging_stat_energy), energyText)
                        }
                        StatRow(stringResource(R.string.charging_stat_voltage), "%.1f V".format(state.voltage))
                    }
                    "packs" -> {
                        // Per-pack BMS data takes a few seconds to arrive (one
                        // pack query per ~4.5 s stats tick), so the tile count
                        // would otherwise tick up 1 → 2 → 3 → 4 and reflow the
                        // grid every time. stableBmsCount is debounced one
                        // level up (in InfoTabs) so the value also survives
                        // Cells <-> Packs tab switches.
                        val bmsPacks = state.bms.packs.filter { it.knownCells.isNotEmpty() }
                        val packs = if (stableBmsCount > 0 && bmsPacks.size >= stableBmsCount) {
                            bmsPacks.take(stableBmsCount).map { pack ->
                                val avgCellV = pack.knownCells.map { it.second }.average().toFloat()
                                // Linear interp 3.0V (empty) -> 4.20V (full)
                                ((avgCellV - 3.0f) / 1.2f * 100f).coerceIn(0f, 100f)
                            }
                        } else {
                            buildList {
                                if (state.battery1 > 0f) add(state.battery1)
                                if (state.battery2 > 0f) add(state.battery2)
                            }
                        }
                        PacksGrid(packs)
                        Spacer(Modifier.height(8.dp))
                        if (packs.size >= 2) {
                            // 2 decimals + explicit sign so a fully balanced
                            // pack reads "+0.00%" rather than dropping to an
                            // empty-looking line. Imbalance is max-min so it's
                            // never negative; the + keeps the row stable.
                            StatRow(stringResource(R.string.charging_stat_balance), "%+.2f%%".format(packs.max() - packs.min()))
                        }
                        StatRow(stringResource(R.string.charging_stat_temp), "%.0f°C".format(state.maxTemp))
                    }
                    "power" -> {
                        StatRow(stringResource(R.string.charging_stat_power), "${state.powerW ?: 0} W")
                        StatRow(stringResource(R.string.charging_stat_current), "%.1f A".format(abs(state.current)))
                        StatRow(stringResource(R.string.charging_stat_voltage), "%.1f V".format(state.voltage))
                    }
                    "cells" -> {
                        CellsTabContent(state.bms)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargingChart(
    samples: List<MetricSample>,
    color: Color,
    unit: String,
    baselineValue: Float? = null,
    baselineColor: Color = color,
    series2: List<MetricSample>? = null,
    color2: Color = color,
    unit2: String = "",
    predictionMarkers: List<com.eried.eucplanet.ui.dashboard.PredictionMarker>? = null,
    boundsFor: (Float, Float) -> GraphBounds,
) {
    if (samples.size >= 2) {
        // Reuse the app's interactive history chart — units, time axis, and
        // hold-to-scrub, same as the metric graphs elsewhere in the app.
        // Battery charts always run in Clock mode so the 15-min wall-clock
        // gridlines line up with the rider's mental model of "when did this
        // start, when will it end".
        MetricGraph(
            samples = samples,
            color = color,
            boundsFor = boundsFor,
            unitLabel = unit,
            baselineValue = baselineValue,
            baselineColor = baselineColor,
            series2 = series2,
            color2 = color2,
            unit2 = unit2,
            timeAxisFormat = com.eried.eucplanet.ui.dashboard.TimeAxisFormat.Clock,
            predictionMarkers = predictionMarkers,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.appColors.tileBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.charging_chart_empty), color = MaterialTheme.appColors.hint, fontSize = 13.sp)
        }
    }
}

/**
 * Per-cell view for smart-BMS wheels (Lynx / Sherman L / Oryx / NOSFET /
 * smart-BMS Patton). For each pack: top stat row with the cell count, min /
 * max / delta in mV, then a grid of small cell-voltage chips. Each chip is
 * tinted by its deviation from the pack average so cells that have drifted
 * out of balance pop visually (red = lowest, blue = highest). Scrolls when
 * a pack has many cells (the Lynx S has 42 cells per pack, so the grid is
 * long).
 */
@Composable
private fun CellsTabContent(bms: com.eried.eucplanet.data.model.BmsState) {
    val colors = MaterialTheme.appColors
    val packs = bms.packs.filter { it.knownCells.isNotEmpty() }
    if (packs.isEmpty()) {
        // Smart-BMS wheel just connected and pages 1+2+3 haven't all landed
        // yet — show the hint instead of an empty surface.
        Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.charging_cells_waiting),
                color = colors.hint,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        packs.forEach { pack ->
            val cells = pack.knownCells
            val mn = pack.minCellV ?: 0f
            val mx = pack.maxCellV ?: 0f
            val deltaMv = pack.cellDeltaMv ?: 0
            // Per-pack header: pack name (if multi-pack), cell count, min / max / Δ.
            // Δ > 50 mV is the conventional "needs balance" threshold on Li-ion EUCs.
            val deltaColor = when {
                deltaMv >= 100 -> colors.statusDanger
                deltaMv >= 50 -> colors.statusWarn
                else -> colors.statusGood
            }
            if (packs.size > 1) {
                Text(
                    stringResource(R.string.charging_cells_pack_n, pack.packIndex + 1),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.metricVoltage,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CellHeaderStat(stringResource(R.string.charging_cells_count), "${cells.size}", modifier = Modifier.weight(1f))
                CellHeaderStat(stringResource(R.string.charging_cells_min), "%.3f V".format(mn), modifier = Modifier.weight(1f))
                CellHeaderStat(stringResource(R.string.charging_cells_max), "%.3f V".format(mx), modifier = Modifier.weight(1f))
                CellHeaderStat(stringResource(R.string.charging_cells_delta), "$deltaMv mV", color = deltaColor, modifier = Modifier.weight(1f))
            }
            // Cell grid: 8 columns so a 32-cell V14 pack lands as a clean
            // 4x8 block; larger Veteran-family packs (42 cells) get 5-6 rows.
            // 1 dp gaps so chips look like one continuous block per pack.
            val cols = 8
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                cells.chunked(cols).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        row.forEach { (idx, v) ->
                            CellChip(
                                cellNumber = idx + 1,
                                voltage = v,
                                min = mn,
                                max = mx,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellHeaderStat(label: String, value: String, color: Color = MaterialTheme.appColors.metricVoltage, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.appColors.hint)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun CellChip(cellNumber: Int, voltage: Float, min: Float, max: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    // Position the voltage on the pack's [min..max] band: lowest cell → red,
    // highest → blue, middle → neutral hint. Within a single-volt-wide band
    // even tiny imbalances are visually obvious.
    val span = (max - min).coerceAtLeast(0.001f)
    val pos = ((voltage - min) / span).coerceIn(0f, 1f)
    val chipColor = when {
        pos < 0.15f -> colors.statusDanger
        pos < 0.35f -> colors.statusWarn
        pos > 0.85f -> colors.metricVoltage
        else -> colors.metricBattery
    }
    Column(
        modifier = modifier
            .background(chipColor.copy(alpha = 0.18f))
            .padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("#$cellNumber", fontSize = 8.sp, color = colors.hint)
        Text("%.3f".format(voltage), fontSize = 10.sp, fontWeight = FontWeight.Medium, color = chipColor)
    }
}

/**
 * Packs laid out in a grid that adapts to the count (2 → 2 cols, 4 → 2×2,
 * 3 → 3, 6 → 3×2, …). Each tile is a mini fill with "#N" and its %.
 */
@Composable
private fun PacksGrid(packs: List<Float>) {
    if (packs.isEmpty()) return
    val n = packs.size
    val avg = packs.average().toFloat()
    val mn = packs.min()
    val mx = packs.max()
    val span = (mx - mn).coerceAtLeast(0.01f)
    // Always render in a single row. Tile width = 1/max(n, 2), so a single
    // pack takes half the row (instead of stretching to a giant square)
    // and the layout never reflows as new BMS packs arrive: when only one
    // pack is up, the right half is just a placeholder spacer that the
    // additional packs slot into. Three or more packs spread evenly.
    val effectiveCols = maxOf(2, n)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        packs.forEachIndexed { idx, pct ->
            val pos = ((pct - mn) / span).coerceIn(0f, 1f)
            // Same red-low / blue-high palette as the cell chips, so a quick
            // glance lines up the worst pack across both tabs visually.
            val tileColor = when {
                pos < 0.25f -> MaterialTheme.appColors.statusDanger
                pos > 0.75f -> MaterialTheme.appColors.metricVoltage
                else -> MaterialTheme.appColors.metricBattery
            }
            PackTile(
                index = idx + 1,
                percent = pct,
                avg = avg,
                fillColor = tileColor,
                modifier = Modifier.weight(1f),
            )
        }
        // Pad to the effective column count so a 1-pack render still shows
        // a half-width tile (placeholder weight on the right keeps the tile
        // sized as if 2 packs were on screen).
        repeat(effectiveCols - n) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun PackTile(
    index: Int,
    percent: Float,
    avg: Float,
    fillColor: Color = MaterialTheme.appColors.metricVoltage,
    modifier: Modifier = Modifier,
) {
    val frac = (percent / 100f).coerceIn(0f, 1f)
    val delta = percent - avg
    val hatchColor = MaterialTheme.appColors.hint
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.appColors.tileBackground),
    ) {
        // Pack-level fill from the bottom: backing color tinted by the pack's
        // imbalance position (red = lowest, blue = highest, green = middle —
        // matches the cell chips' color language).
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(frac)
                .align(Alignment.BottomCenter),
        ) {
            val w = size.width
            val h = size.height
            if (h < 1f) return@Canvas
            drawRect(color = fillColor.copy(alpha = 0.20f))
            val spacing = 26f
            val stripe = hatchColor.copy(alpha = 0.30f)
            var x = 0f
            while (x < w + h) {
                drawLine(
                    stripe,
                    Offset(x, 0f),
                    Offset(x - h, h),
                    strokeWidth = 1f,
                )
                x += spacing
            }
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("#$index", fontSize = 12.sp, color = MaterialTheme.appColors.textSecondary)
            Text(
                "%.1f%%".format(percent),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.appColors.textPrimary,
            )
            // Deviation from the pack average. Always shown so a balanced
            // pack reads "+0.00%" instead of an empty line — keeps the 4-pack
            // row visually uniform. 2 decimals preserve the real sign even
            // for sub-tenth values (a -0.04 % delta shows as "-0.04%" instead
            // of getting rounded down to a confusing "-0.0%").
            Text(
                "%+.2f%%".format(delta),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    delta > 0.005f -> MaterialTheme.appColors.metricBattery
                    delta < -0.005f -> MaterialTheme.appColors.metricVoltage
                    else -> MaterialTheme.appColors.hint
                },
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.appColors.textSecondary, fontSize = 14.sp)
        Text(value, color = MaterialTheme.appColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

