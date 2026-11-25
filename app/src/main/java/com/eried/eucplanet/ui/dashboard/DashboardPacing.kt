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

/**
 * The dial, frame by frame: it follows the wheel's speed and moves on the
 * steps of the dashboard's clock.
 *
 * A new speed starts a new glide from where the dial is, in the frame it
 * arrives in. The first version spent a frame of its own on that, to read the
 * glide's start time, and whenever that frame was the one due for a step the
 * step slipped to the next: with a wheel reporting twenty times a second about
 * every third repaint came a frame late, which is a dial that stutters.
 *
 * Main thread only (frame callbacks).
 */
class DialGlide(private val clock: StepClock, start: Float) {
    var shown: Float = start
        private set
    private var glide: Glide? = null
    private var glideTo = start
    private var movedAtMs = Long.MIN_VALUE / 2

    /** One display frame. False once the dial has arrived and can rest. */
    fun frame(frameMs: Long, target: Float): Boolean {
        if (glide == null || target != glideTo) {
            glideTo = target
            // A dial on the move keeps moving: the new glide counts from the
            // step it last moved on, so the frame the new speed arrives in
            // already carries it on. Counted from this frame it would stand
            // still for that one, which looks like a missed frame.
            val since = if (frameMs - movedAtMs <= 2 * StepClock.STEP_MS) movedAtMs else frameMs
            glide = if (target == shown) null else Glide(shown, target, startMs = since)
        }
        val g = glide ?: return false
        if (!clock.isStep(frameMs)) return true
        movedAtMs = frameMs
        shown = g.valueAt(frameMs)
        // The glide ends exactly on the wheel's value, on a step.
        if (g.isOver(frameMs)) {
            glide = null
            return false
        }
        return true
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
            // one. The value is written inside the frame callback, so it is
            // composed and drawn in that same frame; written after it, it
            // only reached the screen one frame later.
            flow.collectLatest {
                var handed = false
                while (!handed) {
                    withFrameNanos { nanos ->
                        if (clock.isStep(nanos / 1_000_000L)) {
                            shown.value = flow.value
                            handed = true
                        }
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
    val dial = remember(clock) { DialGlide(clock, target) }
    LaunchedEffect(dial) {
        // A new speed only wakes the loop. The dial itself carries on from
        // where it is, see DialGlide, and is moved inside the frame callback
        // so the move is drawn in that same frame.
        snapshotFlow { latest.value }.collectLatest {
            var moving = true
            while (moving) {
                withFrameNanos { nanos ->
                    moving = dial.frame(nanos / 1_000_000L, latest.value)
                    shown.floatValue = dial.shown
                }
            }
        }
    }
    return shown
}
