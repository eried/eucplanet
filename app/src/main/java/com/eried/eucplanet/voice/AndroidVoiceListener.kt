package com.eried.eucplanet.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real microphone, behind [VoiceListener].
 *
 * Android's own recogniser rather than an embedded model, for one reason that
 * decides the feature: it takes whichever microphone the system has routed. A
 * rider talking to the phone and a rider talking through earbuds are the same
 * code path, and pulling the earbuds out mid-ride changes nothing. An embedded
 * model would mean choosing the microphone ourselves and shipping tens of
 * megabytes per language for the privilege.
 *
 * On API 31 and later it runs on-device, which matters because riders lose
 * signal constantly. Below that there is no on-device recogniser and it falls
 * back to the networked one, which needs data: the settings row says so rather
 * than letting a rider find out halfway up a hill.
 *
 * Everything here is the Android edge of the feature. The part worth testing
 * lives in [VoiceCommandSession] and is reachable with [FakeVoiceListener].
 */
class AndroidVoiceListener(
    private val context: Context,
    /** BCP 47 tag for what the rider speaks, from their voice setting. */
    private val languageTag: String,
) : VoiceListener {

    private companion object {
        const val TAG = "VoiceListener"
    }

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * True once the on-device recogniser has been tried and turned out to have
     * no language pack for this rider's language.
     *
     * The device says it has an on-device recogniser and then fails the moment
     * it is asked, because the pack is a separate download the rider may never
     * have made. Nothing in the API distinguishes "installed" from "available
     * to install", so the only way to find out is to ask, and the only sensible
     * answer to the failure is to try the networked one rather than tell a
     * rider their wheel went quiet.
     */
    private var onDeviceFailed = false

    /** True when this device has an on-device recogniser at all. */
    val isOnDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start() {
        if (_state.value is ListenState.Listening ||
            _state.value is ListenState.Preparing ||
            _state.value is ListenState.Partial
        ) return
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !isOnDevice) {
            _state.value = ListenState.Failed("no recogniser on this device")
            return
        }
        val useOnDevice = isOnDevice && !onDeviceFailed
        val r = try {
            if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            // A device can advertise a recogniser and still refuse to build one.
            Log.w(TAG, "could not create a recogniser", e)
            _state.value = ListenState.Failed("recogniser unavailable")
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // The source Android tunes for speech in noise, which is the whole
            // problem on a wheel at speed.
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // Drives the live transcript, so the rider sees it working before
            // the answer arrives.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        // Not Listening yet: onReadyForSpeech decides that, and the prompt
        // waits for it so the beep means "speak now" rather than "soon".
        _state.value = ListenState.Preparing
        val via = if (useOnDevice) "on-device" else "network"
        Log.i(TAG, "listening via $via recogniser, $languageTag")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
            "Voice: listening via $via recogniser, $languageTag"
        )
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening threw", e)
            _state.value = ListenState.Failed("could not start listening")
            release()
        }
    }

    override fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        release()
        if (_state.value !is ListenState.Final) _state.value = ListenState.Idle
    }

    private fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = ListenState.Listening
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) _state.value = ListenState.Partial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            Log.i(TAG, if (text.isBlank()) "heard nothing" else "heard \"$text\"")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                if (text.isBlank()) "Voice: heard nothing" else "Voice: heard \"$text\""
            )
            _state.value =
                if (text.isBlank()) ListenState.Failed("heard nothing")
                else ListenState.Final(text)
            release()
        }

        override fun onError(error: Int) {
            // A missing language pack is the on-device recogniser saying it
            // cannot help with this language, not the rider saying nothing.
            // Remember that and take the networked route from here, which is
            // what a rider who has never downloaded a pack will always hit.
            if (!onDeviceFailed && isOnDevice && isLanguageUnavailable(error)) {
                Log.i(TAG, "no on-device language pack (${errorName(error)}), trying the network one")
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                    "Voice: no on-device language pack (${errorName(error)}), trying the network one"
                )
                onDeviceFailed = true
                release()
                // start() refuses while the state still says Listening, which
                // it does right up to this error. Without clearing it the
                // fallback logs its intention and then returns without ever
                // opening the microphone again, which is a wheel that has gone
                // quiet by a longer route.
                _state.value = ListenState.Idle
                start()
                return
            }
            // The rider never sees this string; it is for the diagnostics log.
            // What they hear is the spoken "I did not catch that", which the
            // controller says for every failure so silence is never the answer.
            Log.i(TAG, "gave up, ${errorName(error)}")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                "Voice: gave up, ${errorName(error)}"
            )
            _state.value = ListenState.Failed(errorName(error), micUnavailable(error))
            release()
        }

        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Errors that mean the microphone was never ours to use.
     *
     * The Studio records with an AudioRecord while it is running, and two
     * things cannot hold the microphone at once, so a rider asking a question
     * mid-recording gets one of these rather than silence or a bad transcript.
     */
    private fun micUnavailable(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_AUDIO ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    // Not the missing permission: that is the rider having never granted it,
    // which wants "this needs setting up" and a way to fix it, not "something
    // else is using the microphone". The controller refuses before opening
    // anything, so it does not reach here.

    /** Errors that mean "not in this language", rather than "heard nothing". */
    private fun isLanguageUnavailable(error: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
            )

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "heard nothing"
        // Both of these mean the recogniser itself is not usable, rather than
        // anything about what the rider said. Worth naming: a diagnostics log
        // reading "error 11" tells whoever reads it nothing, and this is the
        // one a device with a recognition service it cannot actually run
        // reports, which is exactly the case that looks like a broken feature.
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recogniser unavailable on this device"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "recogniser busy, too many requests"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "no language pack"
        else -> "error $error"
    }
}
