package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.service.RideVoice
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.service.SpokenStrings
import com.eried.eucplanet.service.VoiceReports
import com.eried.eucplanet.ui.dashboard.DashboardVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.ObjCSignatureOverride
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.currentRoute
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.darwin.NSObject

/**
 * The iOS voice: the shared [VoiceReports] spoken through AVSpeechSynthesizer.
 * The counterpart of Android's VoiceService for the announcements; the
 * periodic loop and the load window's telemetry are the shared RideSession's,
 * as on Android.
 *
 * While it speaks it holds the audio session through [IosAudioSession] with
 * the rider's "While speaking" choice (voiceAudioFocus), as VoiceService asks
 * for audio focus: DUCK lowers other audio, PAUSE pauses it and lets it
 * resume, OFF leaves it alone. The hold goes once the last line is spoken.
 */
internal class IosVoice(
    private val settings: SettingsRepository,
    private val wheel: () -> WheelRepository,
) : DashboardVoice, RideVoice {
    private val speech = AVSpeechSynthesizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var welcomed = false

    // The session hold, touched on the main thread only. [pending] counts the
    // lines queued and not yet finished or cancelled; [held] is what the hold
    // asked of the others, null when none is taken.
    private var pending = 0
    private var held: IosAudioSession.Others? = null
    private var releaseJob: Job? = null
    private var watchdog: Job? = null

    /** Tells the hold when a line ends. Kept here: the synthesizer's delegate is weak. */
    private inner class SpeechEnd : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            scope.launch { lineEnded() }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            scope.launch { lineEnded() }
        }
    }
    private val speechEnd = SpeechEnd()

    init {
        speech.delegate = speechEnd
    }

    val reports = VoiceReports(
        fullHistory = { wheel().fullHistory.value },
        phoneBattery = { IosPhoneDevice.batteryPercent() },
    )

    /** Strings in the phone's language: Compose resources have no per-call locale on iOS. */
    private object Strings : SpokenStrings {
        override fun text(res: StringResource, vararg args: Any): String {
            val raw = runBlocking { getString(res) }
            return if (args.isEmpty()) raw else raw.format(*args)
        }

        override fun timeNow(): String = NSDateFormatter().apply {
            dateStyle = NSDateFormatterNoStyle
            timeStyle = NSDateFormatterShortStyle
        }.stringFromDate(NSDate())
    }

    /**
     * The voice picked in Settings when it speaks the voice language, else the
     * language's default voice, as Android's VoiceService.applyLocale: a name
     * left over from another language never overrides the language.
     */
    fun voiceFor(localeTag: String, voiceName: String): AVSpeechSynthesisVoice? {
        val language = localeTag.replace('_', '-')
        val named = voiceName.takeIf { it.isNotBlank() }?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
        if (named != null &&
            named.language.substringBefore('-').equals(language.substringBefore('-'), ignoreCase = true)
        ) return named
        return language.takeIf { it.isNotBlank() }?.let { AVSpeechSynthesisVoice.voiceWithLanguage(it) }
    }

    fun speak(text: String, s: AppSettings? = null) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        if (s != null) {
            // Android's 1.0 is the engine's normal pace; iOS's normal is its default rate.
            utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * s.voiceSpeechRate).coerceIn(0.1f, 1f)
            utterance.volume = s.voiceVolumePercent / 100f
            voiceFor(s.voiceLocale, s.voiceName)?.let { utterance.voice = it }
        }
        utter(utterance, s?.voiceAudioFocus)
    }

    /**
     * Speaks [utterance] under the session hold, taking it for the first line
     * with [focus] (voiceAudioFocus; the saved one when null).
     */
    private fun utter(utterance: AVSpeechUtterance, focus: String?) {
        scope.launch {
            val want = IosAudioSession.Others.ofVoiceFocus(focus ?: settings.currentOrLoad().voiceAudioFocus)
            releaseJob?.cancel()
            releaseJob = null
            val had = held
            if (had != want && (had == null || pending == 0)) {
                // Take the new hold before dropping the old one, so the
                // session is not let go and taken again in between.
                IosAudioSession.acquire(want)
                had?.let { IosAudioSession.release(it) }
                held = want
            }
            pending++
            speech.speakUtterance(utterance)
            watch()
        }
    }

    private fun lineEnded() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) scheduleRelease()
    }

    /** Lets the hold go a moment after the last line, so back-to-back lines keep it. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(RELEASE_DELAY_MS)
            if (pending > 0 || speech.speaking) return@launch
            held?.let { IosAudioSession.release(it) }
            held = null
            watchdog?.cancel()
            watchdog = null
        }
    }

    /**
     * A backstop for the delegate: if the synthesizer has gone quiet with lines
     * still counted (a callback that never came), the hold goes anyway, so the
     * rider's music is never left lowered.
     */
    private fun watch() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive && held != null) {
                delay(WATCHDOG_MS)
                if (pending > 0 && !speech.speaking) {
                    Log.w("Voice", "speech ended without a callback; releasing the session")
                    pending = 0
                    scheduleRelease()
                }
            }
        }
    }

    override fun announceEvent(text: String) {
        // Legal Mode Lockdown silences app chatter, including anything that
        // would say "legal mode" or "recording". Alarms speak through
        // [speakAlarm] instead, so the rider's own alarms still fire.
        if (IosLockdown.controller.isEngaged()) return
        scope.launch { speak(text, settings.currentOrLoad()) }
    }

    /** An alarm's voice text, which lockdown leaves alone (Android's VoiceService.speak). */
    fun speakAlarm(text: String) {
        scope.launch { speak(text, settings.currentOrLoad()) }
    }

    // AVSpeechSynthesizer needs no warm-up, and the periodic loop is the
    // RideSession's, started with the app.
    override fun initialize() = Unit

    override fun recordTelemetry(data: WheelData) = reports.recordTelemetry(data)

    override fun clearTelemetryHistory() = reports.clearTelemetryHistory()

    override fun announceStatus(data: WheelData, settings: AppSettings, isRecording: Boolean) =
        announce(data, settings, periodic = true)

    /** Headphones, Bluetooth or CarPlay audio, not the phone's own speaker. */
    override fun externalOutputActive(): Boolean =
        AVAudioSession.sharedInstance().currentRoute.outputs.any {
            val type = (it as AVAudioSessionPortDescription).portType
            type != AVAudioSessionPortBuiltInSpeaker && type != AVAudioSessionPortBuiltInReceiver
        }

    private fun announce(data: WheelData, s: AppSettings, periodic: Boolean) {
        // Lockdown silences the periodic and on-demand reports, as on Android.
        if (IosLockdown.controller.isEngaged()) return
        val parts = reports.buildReportParts(data, s, isRecording = IosTrips.recording.value, periodic = periodic, vctx = Strings)
        if (parts.isEmpty()) return
        Log.i("Voice", "announced (${if (periodic) "periodic" else "trigger"}): \"${parts.joinToString(", ")}\"")
        speak(parts.joinToString(", "), s)
    }

    /** The settings preview: the configured announcement over the live values. */
    fun previewReport(data: WheelData, s: AppSettings, isRecording: Boolean, periodic: Boolean) {
        val parts = reports.buildReportParts(data, s, isRecording, periodic = periodic, vctx = Strings, preview = true)
        if (parts.isEmpty()) return
        Log.i("Voice", "preview (${if (periodic) "periodic" else "trigger"}): \"${parts.joinToString(", ")}\"")
        speak(parts.joinToString(", "), s)
    }

    /** A sentence already set up with its own voice and pace (the settings test). */
    fun speakNow(utterance: AVSpeechUtterance) = utter(utterance, focus = null)

    override fun welcomeOnce() {
        if (welcomed) return
        welcomed = true
        scope.launch {
            val s = settings.currentOrLoad()
            if (s.announceWelcome) speak(Strings.text(Res.string.voice_welcome), s)
        }
    }

    override fun speakWelcomeNow() {
        scope.launch { speak(Strings.text(Res.string.voice_welcome), settings.currentOrLoad()) }
    }

    override fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean) =
        announce(data, settings, periodic = false)

    override fun shutdown() = Unit

    private companion object {
        const val RELEASE_DELAY_MS = 300L
        const val WATCHDOG_MS = 1000L
    }
}
