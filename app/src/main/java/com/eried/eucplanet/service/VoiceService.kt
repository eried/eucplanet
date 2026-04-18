package com.eried.eucplanet.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceOption(val locale: Locale, val displayName: String)

@Singleton
class VoiceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "VoiceService"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentRate: Float = 1.0f
    private var currentLocaleTag: String = "en-US"
    @Volatile private var currentAudioFocus: String = "DUCK"

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val ttsAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private var activeFocusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var pendingUtterances = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _availableVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

    @Volatile private var welcomedThisProcess = false
    @Volatile private var welcomePending = false

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(utteranceListener)
                // Route TTS output through the same attributes we use to request focus,
                // so ducking/pausing other audio is consistent with where TTS actually plays.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { tts?.setAudioAttributes(ttsAudioAttributes) } catch (_: Exception) {}
                }
                isReady = true
                Log.i(TAG, "TTS initialized")
                loadAvailableVoices()
                observeSettings()
                if (welcomePending) {
                    welcomePending = false
                    speakWelcomeIfEnabled()
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { onUtteranceFinished() }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { onUtteranceFinished() }
        override fun onError(utteranceId: String?, errorCode: Int) { onUtteranceFinished() }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { onUtteranceFinished() }
    }

    @Synchronized
    private fun onUtteranceFinished() {
        pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0)
        if (pendingUtterances == 0) abandonAudioFocus()
    }

    @Synchronized
    private fun requestAudioFocus() {
        if (currentAudioFocus == "OFF") return
        if (focusHeld) return

        val focusGain = when (currentAudioFocus) {
            "DUCK" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            "PAUSE" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> return
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(ttsAudioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val r = audioManager.requestAudioFocus(req)
            if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) activeFocusRequest = req
            r
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, focusGain)
        }
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized
    private fun abandonAudioFocus() {
        if (!focusHeld) return
        val req = activeFocusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
            audioManager.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        activeFocusRequest = null
        focusHeld = false
    }

    /**
     * Called once per app launch. Fires the welcome message if enabled and TTS is ready;
     * otherwise schedules it to fire as soon as TTS becomes ready.
     */
    fun welcomeOnce() {
        if (welcomedThisProcess) return
        welcomedThisProcess = true
        if (isReady) speakWelcomeIfEnabled() else welcomePending = true
    }

    private fun speakWelcomeIfEnabled() {
        scope.launch {
            val s = settingsRepository.get()
            // Settings observer may not have emitted yet when welcome fires right after init.
            // Seed the current values so the locale and rate are applied on this first speak.
            currentRate = s.voiceSpeechRate
            currentLocaleTag = s.voiceLocale
            currentAudioFocus = s.voiceAudioFocus
            if (s.announceWelcome) {
                speak(context.getString(R.string.voice_welcome))
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepository.settings.collect { s ->
                currentRate = s.voiceSpeechRate
                currentLocaleTag = s.voiceLocale
                currentAudioFocus = s.voiceAudioFocus
            }
        }
    }

    private fun loadAvailableVoices() {
        val engine = tts ?: return
        val voices = try {
            engine.availableLanguages?.map { locale ->
                VoiceOption(locale, "${locale.displayLanguage} (${locale.displayCountry})")
            }?.sortedBy { it.displayName } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load voices", e)
            emptyList()
        }
        _availableVoices.value = voices
        Log.i(TAG, "Available TTS voices: ${voices.size}")
    }

    fun setVoiceLocale(localeTag: String) {
        val locale = Locale.forLanguageTag(localeTag.replace("_", "-"))
        tts?.language = locale
        Log.i(TAG, "TTS voice set to: $locale")
    }

    fun currentVoiceLanguage(): String {
        return try {
            tts?.voice?.locale?.language ?: tts?.defaultVoice?.locale?.language ?: "en"
        } catch (e: Exception) {
            "en"
        }
    }

    fun pickVoiceForLanguage(langCode: String): String? {
        val engine = tts ?: return null
        val target = langCode.lowercase()
        return try {
            val locales = engine.availableLanguages ?: return null
            val match = locales.firstOrNull { it.language.equals(target, ignoreCase = true) }
            match?.toLanguageTag()
        } catch (e: Exception) {
            Log.w(TAG, "pickVoiceForLanguage failed", e)
            null
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        abandonAudioFocus()
        pendingUtterances = 0
    }

    fun announceStatus(data: WheelData, settings: AppSettings, isRecording: Boolean = false) {
        if (!isReady) return
        tts?.setSpeechRate(settings.voiceSpeechRate)
        applyLocale(settings.voiceLocale)

        val parts = buildReportParts(data, settings, isRecording, periodic = true)
        if (parts.isNotEmpty()) {
            speak(parts.joinToString(", "))
        }
    }

    fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean = false) {
        if (!isReady) return
        tts?.setSpeechRate(settings.voiceSpeechRate)
        applyLocale(settings.voiceLocale)

        val parts = buildReportParts(data, settings, isRecording, periodic = false)
        if (parts.isNotEmpty()) {
            speak(parts.joinToString(", "))
        }
    }

    private fun buildReportParts(
        data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean
    ): List<String> {
        val order = settings.voiceReportOrder.split(",").map { it.trim() }
        val parts = mutableListOf<String>()
        for (item in order) {
            val enabled = if (periodic) when (item) {
                "Speed" -> settings.voiceReportSpeed
                "Battery" -> settings.voiceReportBattery
                "Temp" -> settings.voiceReportTemp
                "PWM" -> settings.voiceReportPwm
                "Distance" -> settings.voiceReportDistance
                "Recording" -> settings.voiceReportRecording
                else -> false
            } else when (item) {
                "Speed" -> settings.triggerReportSpeed
                "Battery" -> settings.triggerReportBattery
                "Temp" -> settings.triggerReportTemp
                "PWM" -> settings.triggerReportPwm
                "Distance" -> settings.triggerReportDistance
                "Recording" -> settings.triggerReportRecording
                else -> false
            }
            if (enabled) {
                when (item) {
                    "Speed" -> parts.add(context.getString(R.string.voice_speed_fmt, "%.0f".format(data.speed)))
                    "Battery" -> parts.add(context.getString(R.string.voice_battery_fmt, data.batteryPercent))
                    "Temp" -> parts.add(context.getString(R.string.voice_temp_fmt, "%.0f".format(data.maxTemperature)))
                    "PWM" -> parts.add(context.getString(R.string.voice_load_fmt, "%.0f".format(data.pwm)))
                    "Distance" -> parts.add(context.getString(R.string.voice_trip_fmt, "%.1f".format(data.tripDistance)))
                    "Recording" -> parts.add(context.getString(if (isRecording) R.string.voice_recording_on else R.string.voice_recording_off))
                }
            }
        }
        return parts
    }

    fun announceAlarm(type: String, value: Float) {
        if (!isReady) return
        speak(context.getString(R.string.voice_warning_fmt, type, "%.0f".format(value)))
    }

    fun announceEvent(text: String) {
        if (!isReady) return
        speak(text)
    }

    fun speak(text: String) {
        if (!isReady) return
        tts?.setSpeechRate(currentRate)
        applyLocale(currentLocaleTag)
        val utteranceId = "edb_${System.currentTimeMillis()}"
        synchronized(this) { pendingUtterances++ }
        requestAudioFocus()
        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak returned ERROR; releasing focus")
            onUtteranceFinished()
        }
    }

    fun testSpeak(text: String, speechRate: Float, localeTag: String) {
        if (!isReady) return
        tts?.setSpeechRate(speechRate)
        applyLocale(localeTag)
        speak(text)
    }

    private fun applyLocale(localeTag: String) {
        val locale = Locale.forLanguageTag(localeTag.replace("_", "-"))
        tts?.language = locale
    }
}
