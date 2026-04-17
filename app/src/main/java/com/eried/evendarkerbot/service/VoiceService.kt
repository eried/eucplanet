package com.eried.evendarkerbot.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.eried.evendarkerbot.R
import com.eried.evendarkerbot.data.model.AppSettings
import com.eried.evendarkerbot.data.model.WheelData
import com.eried.evendarkerbot.data.repository.SettingsRepository
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _availableVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

    @Volatile private var welcomedThisProcess = false
    @Volatile private var welcomePending = false

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
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
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "edb_${System.currentTimeMillis()}")
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
