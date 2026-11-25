package com.eried.eucplanet.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Which display frames the dashboard repaints on while a wheel is talking.
 *
 * The dashboard has two things that change many times a second: the wheel's
 * data, which arrives twenty-odd times a second at no particular moment, and
 * the dial, which glides between those values. Each used to repaint the screen
 * whenever it had something new, and between them they filled every frame the
 * display has: sixty a second on most phones, twice that on fast ones, for the
 * whole ride.
 *
 * Both now wait for a step of this clock, thirty a second, so what they have
 * lands in the same frame. A step is the first frame at least [stepMs] after
 * the last one, so after a pause the very next frame is a step and nothing
 * waits. Every caller in one frame gets the same answer for that frame.
 *
 * Called from the main thread only (frame callbacks).
 */
class StepClock(
    private val stepMs: Long = STEP_MS,
    private val slackMs: Long = STEP_SLACK_MS,
) {
    private var lastStepMs = Long.MIN_VALUE / 2
    private var lastAskedMs = Long.MIN_VALUE / 2

    fun isStep(frameMs: Long): Boolean {
        if (frameMs == lastStepMs) return true
        // A clock that goes back starts over, it must not hold steps off.
        if (frameMs < lastAskedMs) lastStepMs = Long.MIN_VALUE / 2
        lastAskedMs = frameMs
        // A frame a few milliseconds early still counts: frames do not land
        // exactly on the step, and waiting for the next would halve the rate.
        if (frameMs - lastStepMs < stepMs - slackMs) return false
        lastStepMs = frameMs
        return true
    }

    companion object {
        /** 30 steps a second: every second frame at 60 Hz, every fourth at 120. */
        const val STEP_MS = 33L
        const val STEP_SLACK_MS = 4L
    }
}

/**
 * One glide of the dial from one speed to the next: linear over [durationMs],
 * about one wheel update, so the dial tracks the wheel with no lag.
 */
class Glide(
    private val from: Float,
    private val to: Float,
    private val startMs: Long,
    private val durationMs: Long = GLIDE_MS,
) {
    fun isOver(nowMs: Long): Boolean = from == to || nowMs - startMs >= durationMs

    fun valueAt(nowMs: Long): Float {
        if (isOver(nowMs)) return to
        val p = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * p
    }

    companion object {
        const val GLIDE_MS = 250L
    }
}

/** The dashboard's clock, one for the screen. */
@Composable
fun rememberStepClock(): StepClock = remember { StepClock() }

/**
 * [flow]'s latest value, handed to the screen on a step of [clock]. Collected
 * only while the screen is started, like collectAsStateWithLifecycle.
 */
@Composable
fun <T> rememberPaced(flow: StateFlow<T>, clock: StepClock): State<T> {
    val shown = remember(flow) { mutableStateOf(flow.value) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(flow, clock, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // collectLatest: a newer value takes over the wait of an older
            // one, so what is shown on the step is always the newest.
            flow.collectLatest { value ->
                while (true) {
                    val frameMs = withFrameNanos { it } / 1_000_000L
                    if (clock.isStep(frameMs)) {
                        shown.value = value
                        break
                    }
                }
            }
        }
    }
    return shown
}

/**
 * The speed the dial shows, gliding to [target] whenever it changes, on the
 * steps of [clock]. Nothing runs while the speed stands still.
 */
@Composable
fun rememberGlidingSpeed(target: Float, clock: StepClock): State<Float> {
    val shown = remember { mutableFloatStateOf(target) }
    val latest = rememberUpdatedState(target)
    LaunchedEffect(clock) {
        snapshotFlow { latest.value }.collectLatest { to ->
            val from = shown.floatValue
            if (from == to) return@collectLatest
            val glide = Glide(from, to, startMs = withFrameNanos { it } / 1_000_000L)
            while (true) {
                val frameMs = withFrameNanos { it } / 1_000_000L
                if (!clock.isStep(frameMs)) continue
                shown.floatValue = glide.valueAt(frameMs)
                // The glide ends exactly on the wheel's value, on a step.
                if (glide.isOver(frameMs)) break
            }
        }
    }
    return shown
}
