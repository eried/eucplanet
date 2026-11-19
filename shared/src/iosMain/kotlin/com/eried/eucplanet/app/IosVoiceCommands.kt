package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.VoiceCommandSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.flic.VoiceListening
import com.eried.eucplanet.service.ToneSynth
import com.eried.eucplanet.ui.dashboard.DashboardVoiceCommands
import com.eried.eucplanet.voice.CommandCues
import com.eried.eucplanet.voice.CommandVoice
import com.eried.eucplanet.voice.LanguageStrings
import com.eried.eucplanet.voice.VoiceCommandController
import com.eried.eucplanet.voice.VoiceCommandPorts
import com.eried.eucplanet.voice.VoiceCommandUiState
import com.eried.eucplanet.voice.VoiceCueFigures
import com.eried.eucplanet.voice.VoiceListener
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Spoken commands on the iPhone: the shared VoiceCommandController, as
 * Android runs it, over Apple's speech recognition (IosSpeechListener), the
 * audio session for the music while it listens, and strings in the voice's
 * language (IosLanguageStrings).
 */
internal object IosVoiceCommands : DashboardVoiceCommands, VoiceListening {

    val controller: VoiceCommandController by lazy {
        VoiceCommandController(
            ports = Ports,
            settingsRepository = IosAppGraph.settings,
            wheelRepository = IosAppGraph.wheel,
            voiceService = Voice,
            tonePlayer = Cues,
            weatherRepository = IosAppGraph.weather,
            navigationEngine = IosAppGraph.navigation,
        ).also { c ->
            // iOS asks for the microphone and speech recognition from
            // anywhere, and answers at once when the rider refused before.
            c.micPrompter = {
                IosSpeechListener.ask { granted -> c.micPermissionResult(granted) }
            }
        }
    }

    override val state: StateFlow<VoiceCommandUiState> get() = controller.state
    override val showVocabulary: SharedFlow<Unit> get() = controller.showVocabulary
    override fun listen() = controller.listen()
    override fun ask() = controller.ask()
    override fun showingListeningCue(): () -> Unit = controller.showingListeningCue()
    override fun hasMicPermission(): Boolean = controller.hasMicPermission()

    /** What the voice commands ask of the iPhone. */
    private object Ports : VoiceCommandPorts {
        override fun hasMicPermission(): Boolean = IosSpeechListener.permitted()

        override fun newListener(languageTag: String): VoiceListener = IosSpeechListener(languageTag)

        // One hold at a time, as Android's ListeningFocus: a new session's
        // hold replaces the last one, and a stale release does nothing.
        private var held: Pair<Int, IosAudioSession.Others>? = null
        private var generation = 0

        override fun holdFocus(musicWhileListening: String): Any {
            generation++
            val want = when (musicWhileListening) {
                VoiceCommandSettings.MUSIC_PAUSE -> IosAudioSession.Others.PAUSE
                VoiceCommandSettings.MUSIC_DUCK -> IosAudioSession.Others.DUCK
                else -> IosAudioSession.Others.LEAVE
            }
            IosAudioSession.acquire(want)
            held?.let { IosAudioSession.release(it.second) }
            held = generation to want
            return generation
        }

        override fun releaseFocus(token: Any) {
            val h = held ?: return
            if (h.first != token) return
            held = null
            IosAudioSession.release(h.second)
        }

        override fun releaseAllFocus() {
            generation++
            held?.let { IosAudioSession.release(it.second) }
            held = null
        }

        override fun stringsIn(languageTag: String): LanguageStrings = IosLanguageStrings.forTag(languageTag)

        override fun noteMicrophoneNeeded() = IosAppHealth.noteMicrophoneNeeded()

        override fun microphoneGranted() = IosAppHealth.microphoneGranted()

        override suspend fun runAction(catalogKey: String) =
            IosAppGraph.actions.executeAction(catalogKey, IosAppGraph.settings.get())

        override fun isRecording(): Boolean = IosTrips.recording.value

        override fun location(): GeoPoint? =
            (IosLocation.fix.value ?: IosLocation.lastKnown.value)?.let { GeoPoint(it.latitude, it.longitude) }

        override suspend fun lastFinishedTrip(): TripRecord? =
            IosAppGraph.database.tripDao().observeAll().first()
                .filter { it.endTime != null }.maxByOrNull { it.startTime }
    }

    /** The answers in the iPhone's voice, at the rider's voice settings. */
    private object Voice : CommandVoice {
        private val voice get() = IosAppGraph.voice
        override val isSpeaking: StateFlow<Boolean> get() = voice.isSpeaking
        override fun speak(text: String) = voice.speak(text, IosAppGraph.settings.current.value)
        override fun stopSpeaking() = voice.stopSpeaking()
        override fun reportText(report: String, data: WheelData, settings: AppSettings, isRecording: Boolean) =
            voice.reportText(report, data, settings, isRecording)
        override fun triggerReportText(data: WheelData, settings: AppSettings, isRecording: Boolean) =
            voice.reportText(null, data, settings, isRecording)
    }

    /** The three short sounds, the shared figures through the app's tone player. */
    private object Cues : CommandCues {
        private suspend fun play(f: VoiceCueFigures.Figure) {
            val r = ToneSynth.renderNotes(f.notes, f.gapMs, f.leadPadMs, f.volumePct, minFrames = 0) ?: return
            IosTonePlayer.play(r)
        }
        override suspend fun playPrompt() = play(VoiceCueFigures.PROMPT)
        override suspend fun playEndPrompt() = play(VoiceCueFigures.END)
        override suspend fun playErrorPrompt() = play(VoiceCueFigures.ERROR)
    }
}
