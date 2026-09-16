package com.eried.eucplanet.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a spoken phrase comes from.
 *
 * An interface rather than a direct call into `SpeechRecognizer` for two
 * reasons. It lets the whole chain be tested without a microphone, and it lets
 * the listening UI be driven on an emulator, which has no microphone to speak
 * into. The fake is the same fixture idea as the virtual wheels: real enough to
 * exercise the code that matters, and gated behind Service Mode.
 */
interface VoiceListener {

    val state: StateFlow<ListenState>

    /** Open the microphone. Idempotent: starting while listening does nothing. */
    fun start()

    /** Close it early. The window closing on its own does the same. */
    fun stop()
}

/** Where a listening session has got to. */
sealed interface ListenState {
    /** Nothing happening. */
    data object Idle : ListenState

    /**
     * Asked for, not open yet.
     *
     * A recogniser takes a moment to start, and anything said before it is
     * ready is simply lost. That moment is why a rider who pressed the button
     * and immediately said "help" was not heard: the cue had already played.
     */
    data object Preparing : ListenState

    /** Microphone open, nothing heard yet. */
    data object Listening : ListenState

    /** Heard so far, still going. Drives the live transcript. */
    data class Partial(val text: String) : ListenState

    /** Finished, this is what it heard. */
    data class Final(val text: String) : ListenState

    /**
     * Gave up. [reason] is for the log, not for the rider.
     *
     * [micUnavailable] is the one distinction the rider does need: something
     * else holds the microphone, which on this app means the Studio recording
     * a video, and it owns it through an AudioRecord that will not share.
     * Telling them "I did not catch that" there sends them chasing a problem
     * with their voice that does not exist.
     */
    data class Failed(
        val reason: String,
        val micUnavailable: Boolean = false,
    ) : ListenState
}

/**
 * A listener that needs no microphone: it replays a scripted phrase with the
 * same state sequence a real one produces.
 *
 * Debug fixture. It exists so the listening UI can be seen and tested on an
 * emulator, and so the matcher and the answers can be exercised end to end in a
 * unit test without Android in the way.
 */
class FakeVoiceListener(
    private val phrase: String,
    private val onAdvance: (ListenState) -> Unit = {},
) : VoiceListener {

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    /** Every state a real session would pass through, in order. */
    fun script(): List<ListenState> {
        val words = phrase.trim().split(" ").filter { it.isNotBlank() }
        val partials = words.indices.map { i ->
            ListenState.Partial(words.take(i + 1).joinToString(" "))
        }
        return listOf(ListenState.Listening) + partials + listOf(ListenState.Final(phrase.trim()))
    }

    override fun start() {
        for (step in script()) {
            _state.value = step
            onAdvance(step)
        }
    }

    override fun stop() {
        _state.value = ListenState.Idle
    }
}
