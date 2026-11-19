package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.voice.ListenState
import com.eried.eucplanet.voice.VoiceListener
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * A spoken phrase heard through the iPhone's own speech recognition, the
 * counterpart of Android's AndroidVoiceListener.
 *
 * Android's recogniser ends the phrase on silence by itself. Apple's keeps
 * listening, so this ends it the same way: once the words have stood still
 * for [SILENCE_MS], the audio is closed and the recogniser gives its final
 * text.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSpeechListener(private val languageTag: String) : VoiceListener {

    private val _state = MutableStateFlow<ListenState>(ListenState.Idle)
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var silence: Job? = null
    private var recording = false

    override fun start() {
        if (engine != null) return
        _state.value = ListenState.Preparing
        val supported = SFSpeechRecognizer.supportedLocales().mapNotNull { (it as? NSLocale)?.localeIdentifier }
        val id = recognizerLocale(languageTag, supported, NSLocale.currentLocale.countryCode)
        val recognizer = id?.let { SFSpeechRecognizer(locale = NSLocale(localeIdentifier = it)) }
        if (recognizer == null || !recognizer.isAvailable()) {
            fail("recognizer for $languageTag not available")
            return
        }
        IosAudioSession.acquireRecording()
        recording = true
        val req = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
        }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) {
            // No input route: another app holds the microphone (a call, a recording).
            fail("no microphone input", micUnavailable = true)
            return
        }
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            buffer?.let { req.appendAudioPCMBuffer(it) }
        }
        eng.prepare()
        if (!eng.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            fail("audio engine did not start", micUnavailable = true)
            return
        }
        engine = eng
        request = req
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            dispatch_async(dispatch_get_main_queue()) {
                when {
                    result != null && result.isFinal() -> {
                        _state.value = ListenState.Final(result.bestTranscription.formattedString)
                        close()
                    }
                    result != null -> {
                        val text = result.bestTranscription.formattedString
                        if (text.isNotBlank()) {
                            _state.value = ListenState.Partial(text)
                            endAfterSilence()
                        }
                    }
                    error != null && _state.value !is ListenState.Final -> {
                        // Ended with nothing heard, or cancelled by stop().
                        if (_state.value !is ListenState.Idle) {
                            fail(error.localizedDescription)
                        }
                    }
                }
            }
        }
        _state.value = ListenState.Listening
    }

    override fun stop() {
        task?.cancel()
        close()
        if (_state.value !is ListenState.Final && _state.value !is ListenState.Failed) {
            _state.value = ListenState.Idle
        }
    }

    /** The words stood still: close the audio so the recogniser gives its final text. */
    private fun endAfterSilence() {
        silence?.cancel()
        silence = scope.launch {
            delay(SILENCE_MS)
            request?.endAudio()
        }
    }

    private fun fail(reason: String, micUnavailable: Boolean = false) {
        Log.w(TAG, "failed: $reason")
        close()
        _state.value = ListenState.Failed(reason, micUnavailable)
    }

    private fun close() {
        silence?.cancel()
        silence = null
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
        request?.endAudio()
        request = null
        task = null
        if (recording) {
            recording = false
            IosAudioSession.releaseRecording()
        }
    }

    companion object {
        private const val TAG = "SpeechListener"

        /** How long the words must stand still before the phrase counts as said. */
        private const val SILENCE_MS = 1200L

        /**
         * The recogniser locale for [languageTag] among [supported] (Apple's
         * "es-MX", "nb-NO" form, either separator). Apple names regions, not
         * "es-419" or a bare "no": an exact match first, then the same
         * language in the phone's own [region], then the first of that language.
         */
        fun recognizerLocale(languageTag: String, supported: List<String>, region: String?): String? {
            val want = languageTag.replace('_', '-')
            val norm = supported.map { it.replace('_', '-') }
            norm.firstOrNull { it.equals(want, ignoreCase = true) }?.let { return it }
            val language = when (val l = want.substringBefore('-').lowercase()) {
                "no", "nn" -> "nb"
                "in" -> "id"
                else -> l
            }
            val sameLanguage = norm.filter { it.substringBefore('-').equals(language, ignoreCase = true) }
            // Traditional Chinese stays traditional: Taiwan, then Hong Kong.
            if (language == "zh" && (want.contains("TW", true) || want.contains("Hant", true) || want.contains("HK", true))) {
                sameLanguage.firstOrNull { it.endsWith("TW", true) }?.let { return it }
                sameLanguage.firstOrNull { it.endsWith("HK", true) }?.let { return it }
            }
            val wantRegion = want.substringAfter('-', "").takeIf { it.length == 2 }
            return sameLanguage.firstOrNull { wantRegion != null && it.endsWith("-$wantRegion", true) }
                ?: sameLanguage.firstOrNull { region != null && it.endsWith("-$region", true) }
                ?: sameLanguage.firstOrNull { language == "es" && want.endsWith("419") && it.endsWith("-MX", true) }
                ?: sameLanguage.firstOrNull { language == "en" && it.endsWith("-US", true) }
                ?: sameLanguage.firstOrNull()
        }

        /** The microphone and speech recognition are both allowed. */
        fun permitted(): Boolean =
            AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted &&
                SFSpeechRecognizer.authorizationStatus() ==
                SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized

        /**
         * Asks for whichever of the two is not yet answered, then reports on
         * the main thread whether both are allowed. A refusal made earlier is
         * not asked again (iOS shows no prompt for it), so [done] hears false
         * at once and the rider is pointed to Settings.
         */
        fun ask(done: (Boolean) -> Unit) {
            val session = AVAudioSession.sharedInstance()
            fun speech() {
                SFSpeechRecognizer.requestAuthorization { _ ->
                    dispatch_async(dispatch_get_main_queue()) { done(permitted()) }
                }
            }
            if (session.recordPermission == AVAudioSessionRecordPermissionGranted ||
                session.recordPermission == AVAudioSessionRecordPermissionDenied
            ) {
                speech()
            } else {
                session.requestRecordPermission { _ -> speech() }
            }
        }
    }
}
