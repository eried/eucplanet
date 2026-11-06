package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.EventAnnouncer
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
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.currentRoute
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle

/**
 * The iOS voice: the shared [VoiceReports] spoken through AVSpeechSynthesizer.
 * The counterpart of Android's VoiceService for the announcements; the
 * periodic loop follows WheelService's (interval, "announce when", the
 * external-output condition).
 */
internal class IosVoice(
    private val settings: SettingsRepository,
    private val wheel: () -> WheelRepository,
) : DashboardVoice, EventAnnouncer {
    private val speech = AVSpeechSynthesizer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var welcomed = false

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

    fun speak(text: String, s: AppSettings? = null) {
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        if (s != null) {
            // Android's 1.0 is the engine's normal pace; iOS's normal is its default rate.
            utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * s.voiceSpeechRate).coerceIn(0.1f, 1f)
            utterance.volume = s.voiceVolumePercent / 100f
            if (s.voiceLocale.isNotBlank()) {
                AVSpeechSynthesisVoice.voiceWithLanguage(s.voiceLocale.replace("_", "-"))?.let { utterance.voice = it }
            }
        }
        speech.speakUtterance(utterance)
    }

    override fun announceEvent(text: String) {
        scope.launch { speak(text, settings.currentOrLoad()) }
    }

    override fun initialize() {
        if (loop != null) return
        // Telemetry for the averaged load reports, as WheelService feeds VoiceService.
        scope.launch { wheel().wheelData.collect { reports.recordTelemetry(it) } }
        loop = scope.launch {
            while (true) {
                delay(settings.currentOrLoad().voiceIntervalSeconds * 1000L)
                val s = settings.currentOrLoad()
                if (!s.voiceEnabled) continue
                val w = wheel()
                val connected = w.connectionState.value == ConnectionState.CONNECTED
                val data = w.wheelData.value
                val moving = connected && kotlin.math.abs(data.speed) > 0.1f
                val allowed = when (s.voiceAnnounceWhen) {
                    "ALWAYS" -> true
                    "RIDING" -> moving
                    else -> connected
                }
                if (!allowed) continue
                if (s.voiceAnnounceRequireExternal && !externalOutput()) continue
                announce(data, s, periodic = true)
            }
        }
    }

    /** Headphones, Bluetooth or CarPlay audio, not the phone's own speaker. */
    private fun externalOutput(): Boolean =
        AVAudioSession.sharedInstance().currentRoute.outputs.any {
            val type = (it as AVAudioSessionPortDescription).portType
            type != AVAudioSessionPortBuiltInSpeaker && type != AVAudioSessionPortBuiltInReceiver
        }

    private fun announce(data: WheelData, s: AppSettings, periodic: Boolean) {
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
    fun speakNow(utterance: AVSpeechUtterance) {
        speech.speakUtterance(utterance)
    }

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
}
