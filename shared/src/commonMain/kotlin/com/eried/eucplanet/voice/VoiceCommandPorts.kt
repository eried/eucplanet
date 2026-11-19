package com.eried.eucplanet.voice

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelData
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource

/**
 * Strings in one language, whatever the interface is set to.
 *
 * Spoken commands answer in the voice's language and listen in the
 * recognition language, and either can differ from the screens'. Android
 * reads them through a context set to that language; the iPhone reads the
 * app's own string files for it (IosLanguageStrings).
 */
interface LanguageStrings {
    fun text(res: StringResource, vararg args: Any): String
}

/**
 * What the spoken commands ask of the phone they run on. VoiceCommandController
 * is the same on both phones; these are the parts that are not.
 */
interface VoiceCommandPorts {
    /** The microphone, and on the iPhone speech recognition too, are allowed. */
    fun hasMicPermission(): Boolean

    /** A recogniser listening in [languageTag] (BCP 47, "es-ES"). */
    fun newListener(languageTag: String): VoiceListener

    /**
     * The rider's music while the app listens and answers, as the
     * voiceCommands.musicWhileListening setting asks. Returns a token for [releaseFocus].
     */
    fun holdFocus(musicWhileListening: String): Any

    /** Gives back one [holdFocus]; giving back the same token twice does nothing. */
    fun releaseFocus(token: Any)

    /** Gives back every hold, for a rider who stopped the session by hand. */
    fun releaseAllFocus()

    /** Strings in [languageTag] ("en_US" or "en-US"); blank means the app's own language. */
    fun stringsIn(languageTag: String): LanguageStrings

    /** A refusal for want of the microphone: Needs attention gets its entry. */
    fun noteMicrophoneNeeded()

    /** The rider allowed the microphone: that entry has nothing left to say. */
    fun microphoneGranted()

    /** Runs an action from the action catalog, as a Flic press would. */
    suspend fun runAction(catalogKey: String)

    /** Whether a trip is recording now. */
    fun isRecording(): Boolean

    /** Where the phone is, or was last, for the weather. */
    fun location(): GeoPoint?

    /** The last finished trip, or null when there is none. */
    suspend fun lastFinishedTrip(): TripRecord?
}

/** The voice the answers are said in: Android's VoiceService, the iPhone's IosVoice. */
interface CommandVoice {
    val isSpeaking: StateFlow<Boolean>
    fun speak(text: String)
    fun stopSpeaking()

    /** One report's sentence ("Battery 68 percent"), or null when it has nothing to say. */
    fun reportText(report: String, data: WheelData, settings: AppSettings, isRecording: Boolean = false): String?

    /** The whole on-demand report, as the dashboard's Voice button says it. */
    fun triggerReportText(data: WheelData, settings: AppSettings, isRecording: Boolean): String?
}

/** The three short sounds of a session: open, closed, not understood. */
interface CommandCues {
    suspend fun playPrompt()
    suspend fun playEndPrompt()
    suspend fun playErrorPrompt()
}
