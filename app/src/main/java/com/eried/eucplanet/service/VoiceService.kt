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
import com.eried.eucplanet.data.repository.EventAnnouncer
import com.eried.eucplanet.platform.getString
import com.eried.eucplanet.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceService(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val appNotifier: com.eried.eucplanet.util.AppNotifier,
    private val legalLockdown: com.eried.eucplanet.data.repository.LegalLockdownController,
    // Lazy: WheelRepository speaks through this service, so a direct
    // dependency back would be a cycle. Only the stat pills read it.
    private val wheelRepositoryLazy: Lazy<com.eried.eucplanet.data.repository.WheelRepository>,
    // Lazy for the same reason: it reaches WheelRepository.
    private val metricsResetLazy: Lazy<com.eried.eucplanet.data.repository.MetricsReset>,
    private val tonePlayer: TonePlayer,
) : EventAnnouncer {
    companion object {
        private const val TAG = "VoiceService"
    }

    /** The rider's voice volume, scaling the speech alone (KEY_PARAM_VOLUME is
     *  per utterance and relative to the stream, so nothing else moves). */
    @Volatile private var currentVolume = 1f

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private var currentRate: Float = 1.0f
    private var currentLocaleTag: String = "en-US"
    @Volatile private var currentVoiceName: String = ""
    @Volatile private var currentAudioFocus: String = "DUCK"
    @Volatile private var currentOutputChannel: String = "MEDIA"

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun buildTtsAudioAttributes(channel: String): AudioAttributes {
        val usage = when (channel) {
            "NOTIFICATION" -> AudioAttributes.USAGE_NOTIFICATION
            "ALARM" -> AudioAttributes.USAGE_ALARM
            else -> AudioAttributes.USAGE_MEDIA
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    @Volatile private var ttsAudioAttributes: AudioAttributes =
        buildTtsAudioAttributes("MEDIA")
    private var activeFocusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var pendingUtterances = 0

    /**
     * True while any TTS utterance is in flight. Engine sound observes this to
     * duck/pause itself while the user is being spoken to.
     */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The engine's voices and languages, read once when it is ready. Asking
    // the engine means the whole list over binder, which every spoken line
    // with a picked voice used to do just to find that one voice.
    @Volatile private var voicesByName: Map<String, Voice> = emptyMap()
    @Volatile private var languages: List<Locale> = emptyList()

    /** The voice called [name], from the list read at start; the engine only before that list is in. */
    private fun voiceNamed(name: String): Voice? {
        val known = voicesByName
        if (known.isNotEmpty()) return known[name]
        return try { tts?.voices?.firstOrNull { it.name == name } } catch (_: Exception) { null }
    }

    private val _availableVoices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<VoiceOption>> = _availableVoices.asStateFlow()

    private val _availableVoiceChoices = MutableStateFlow<List<VoiceChoice>>(emptyList())
    val availableVoiceChoices: StateFlow<List<VoiceChoice>> = _availableVoiceChoices.asStateFlow()

    @Volatile private var welcomedThisProcess = false
    @Volatile private var welcomePending = false

    // At most one "trigger" message may be queued/playing at a time. Presses that arrive while
    // one is already in flight are dropped silently. Alerts ignore this flag and queue normally.
    @Volatile private var triggerInFlight = false

    // Buffer for speak calls that arrive before TTS async init completes.
    private data class DeferredUtterance(
        val text: String, val isTrigger: Boolean, val rate: Float,
        val localeTag: String, val voiceName: String
    )
    private val pendingBeforeReady = mutableListOf<DeferredUtterance>()

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(utteranceListener)
                // Route TTS output through the same attributes we use to request focus,
                // so ducking/pausing other audio is consistent with where TTS actually plays.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { tts?.setAudioAttributes(ttsAudioAttributes) } catch (_: Exception) {}
                }
                Log.i(TAG, "TTS initialized")
                // onInit runs on the main thread, and the voice lists are a few
                // hundred voices over binder (Google TTS has 442), so they load
                // off it. Lines asked for meanwhile wait in pendingBeforeReady
                // and go out in order once the lists are in.
                val engine = tts ?: return@TextToSpeech
                scope.launch {
                    val loaded = withContext(Dispatchers.Default) { loadAvailableVoices(engine) }
                    // Shut down (or replaced) while loading: this engine's lists
                    // must not land on the next one.
                    if (tts !== engine) return@launch
                    loaded.publish()
                    isReady = true
                    autoSyncVoiceToLanguageIfNeeded()
                    observeSettings()
                    flushPendingBeforeReady()
                    if (welcomePending) {
                        welcomePending = false
                        speakWelcomeIfEnabled()
                    }
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    private fun flushPendingBeforeReady() {
        val items = synchronized(pendingBeforeReady) {
            val copy = pendingBeforeReady.toList()
            pendingBeforeReady.clear()
            copy
        }
        // Flag was set when trigger was deferred; clear so speakInternal re-sets it for real.
        if (items.any { it.isTrigger }) triggerInFlight = false
        items.forEach { speakInternal(it.text, it.isTrigger, it.rate, it.localeTag, it.voiceName) }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
        override fun onDone(utteranceId: String?) { onUtteranceFinished(utteranceId) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { onUtteranceFinished(utteranceId) }
        override fun onError(utteranceId: String?, errorCode: Int) { onUtteranceFinished(utteranceId) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { onUtteranceFinished(utteranceId) }
    }

    @Synchronized
    private fun onUtteranceFinished(utteranceId: String?) {
        pendingUtterances = (pendingUtterances - 1).coerceAtLeast(0)
        if (utteranceId != null && utteranceId.startsWith("trig_")) {
            triggerInFlight = false
        }
        if (pendingUtterances == 0) {
            abandonAudioFocus()
            _isSpeaking.value = false
        }
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

    /**
     * Speak the welcome message right now, ignoring the once-per-process gate.
     * Used after a manual voice-language switch to give the user audible
     * confirmation that the new voice works.
     */
    fun speakWelcomeNow() {
        scope.launch {
            val s = settingsRepository.get()
            val welcome = context.getString(R.string.voice_welcome)
            speakInternal(welcome, isTrigger = false,
                rate = s.voiceSpeechRate, localeTag = s.voiceLocale, voiceName = s.voiceName)
        }
    }

    private fun speakWelcomeIfEnabled() {
        scope.launch {
            val s = settingsRepository.get()
            // Settings observer may not have emitted yet when welcome fires right after init.
            // Seed the current values so the locale and rate are applied on this first speak.
            currentRate = s.voiceSpeechRate
            currentVolume = s.voiceVolumePercent / 100f
            currentLocaleTag = s.voiceLocale
            currentVoiceName = s.voiceName
            currentAudioFocus = s.voiceAudioFocus
            if (s.voiceOutputChannel != currentOutputChannel) {
                currentOutputChannel = s.voiceOutputChannel
                applyOutputChannel()
            }
            if (s.announceWelcome) {
                speak(context.getString(R.string.voice_welcome))
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            settingsRepository.settings.collect { s ->
                currentRate = s.voiceSpeechRate
                currentVolume = s.voiceVolumePercent / 100f
                currentLocaleTag = s.voiceLocale
                currentVoiceName = s.voiceName
                currentAudioFocus = s.voiceAudioFocus
                if (s.voiceOutputChannel != currentOutputChannel) {
                    currentOutputChannel = s.voiceOutputChannel
                    applyOutputChannel()
                }
            }
        }
    }

    private fun applyOutputChannel() {
        ttsAudioAttributes = buildTtsAudioAttributes(currentOutputChannel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { tts?.setAudioAttributes(ttsAudioAttributes) } catch (_: Exception) {}
        }
        // Drop any focus held under the old attributes; next speak will re-request with new ones.
        abandonAudioFocus()
    }

    /** What [loadAvailableVoices] read from one engine, published on the main thread. */
    private inner class LoadedVoices(
        val languages: List<Locale>,
        val voices: List<VoiceOption>,
        val byName: Map<String, Voice>,
        val choices: List<VoiceChoice>,
    ) {
        fun publish() {
            this@VoiceService.languages = languages
            voicesByName = byName
            _availableVoices.value = voices
            _availableVoiceChoices.value = choices
            Log.i(TAG, "Available TTS languages: ${voices.size}, voice choices: ${choices.size}")
        }
    }

    private fun loadAvailableVoices(engine: TextToSpeech): LoadedVoices {
        var langs: List<Locale> = emptyList()
        var byName: Map<String, Voice> = emptyMap()
        val voices = try {
            engine.availableLanguages?.also { langs = it.toList() }?.map { locale ->
                // Render each voice in its own language (e.g. "Español
                // (España)") rather than translating into the current UI
                // locale. Stable across app-language changes and matches
                // the same native-script policy as the language picker.
                // displayCountry can be empty for locales that aren't bound
                // to a region (e.g. plain "ar" Arabic), skip the parens so
                // we don't render "العربية ()".
                val lang = locale.getDisplayLanguage(locale)
                val country = locale.getDisplayCountry(locale)
                val label = if (country.isNullOrBlank()) lang else "$lang ($country)"
                VoiceOption(locale.toString(), label)
            }?.sortedBy { it.displayName } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load voices", e)
            emptyList()
        }

        // Voice-level list: the individual voices behind each language. Grouped
        // by locale and numbered so the UI can offer "Voice 1 / Voice 2 / ..."
        // for languages that expose more than one.
        val choices = try {
            val all = engine.voices
            byName = all?.associateBy { it.name } ?: emptyMap()
            val usable = all?.filter { v ->
                // Skip voices whose data is not on the device (can't be used
                // without a download) and any the engine marks unsupported.
                val notInstalled = v.features?.contains(
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                ) ?: false
                // Keep online voices (just flagged); only drop ones needing a download.
                !notInstalled
            } ?: emptyList()
            usable.groupBy { it.locale.toString() }
                .flatMap { (localeTag, group) ->
                    // Stable order: offline before online, higher quality first,
                    // then name, so "Voice N" numbering does not jump around.
                    val ordered = group.sortedWith(
                        compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name })
                    )
                    ordered.mapIndexed { i, v ->
                        VoiceChoice(
                            name = v.name,
                            localeTag = localeTag,
                            index = i + 1,
                            networkRequired = v.isNetworkConnectionRequired,
                            quality = v.quality
                        )
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load voice choices", e)
            emptyList()
        }
        return LoadedVoices(langs, voices, byName, choices)
    }

    fun setVoiceLocale(localeTag: String) {
        val locale = Locale.forLanguageTag(localeTag.replace("_", "-"))
        tts?.language = locale
        Log.i(TAG, "TTS voice set to: $locale")
    }

    /**
     * Apply a specific voice within the current language (empty = engine
     * default for the locale). Also remembers it so ambient [speak] calls use
     * it. The per-utterance [applyLocale] re-applies it anyway, guarding against
     * a stale name after a language switch.
     */
    fun setVoiceName(name: String) {
        currentVoiceName = name
        if (name.isBlank()) return
        val v = voiceNamed(name)
        if (v != null) try { tts?.voice = v } catch (_: Exception) {}
    }

    /**
     * Called once after TTS becomes ready and the available-voices list is
     * populated. When the rider has never explicitly picked a voice
     * (voiceLocaleOverridden=false), align the voice with the UI language
     * picked at first launch. Does nothing if a matching voice is unavailable
     * on the device.
     *
     * This is the first-launch path: detectSystemLanguage() runs in
     * MainActivity and saves [AppSettings.language] before TTS is even
     * constructed, so we can't pick the voice there. This hook closes the
     * loop once the engine is ready, without requiring a Settings round-trip
     * from the rider.
     */
    private fun autoSyncVoiceToLanguageIfNeeded() {
        scope.launch {
            val s = settingsRepository.get()
            if (s.voiceLocaleOverridden) return@launch
            val lang = s.language.ifBlank { return@launch }
            val tag = pickVoiceForLanguage(lang) ?: return@launch
            if (tag == s.voiceLocale) return@launch
            Log.i(TAG, "Auto-syncing TTS voice to UI language: lang=$lang -> $tag")
            settingsRepository.update(s.copy(voiceLocale = tag))
            setVoiceLocale(tag)
        }
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
        // Strip region (e.g. "es-419" -> "es", "pt-BR" -> "pt") since we match
        // by primary language. TTS engines don't always carry the region.
        val primary = langCode.substringBefore('-').lowercase()
        // Norwegian on Android TTS is exposed as Bokmål (nb) or Nynorsk (nn);
        // the macro-language code "no" is rarely a direct match. Accept either.
        val acceptable = when (primary) {
            "no" -> setOf("no", "nb", "nn")
            else -> setOf(primary)
        }
        return try {
            val locales = languages.ifEmpty { engine.availableLanguages?.toList() } ?: return null
            val match = locales.firstOrNull { it.language.lowercase() in acceptable }
            // Return Locale.toString() form ("nb_NO") not toLanguageTag()
            // ("nb-NO") so the saved voiceLocale matches what the voice
            // picker compares against (`it.localeId`).
            match?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "pickVoiceForLanguage failed", e)
            null
        }
    }

    /**
     * Stop talking now, keeping the engine alive.
     *
     * The microphone cannot politely wait for a report to finish: a rider who
     * presses listen while the app is mid-announcement wants to ask something,
     * and leaving the speech running means the recogniser hears the app rather
     * than the rider, which is the one way this feature reliably fails.
     * Shutting down would have worked too and would have cost a re-init before
     * the answer could be spoken.
     */
    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        synchronized(pendingBeforeReady) { pendingBeforeReady.clear() }
        synchronized(this) {
            pendingUtterances = 0
            triggerInFlight = false
        }
        abandonAudioFocus()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        voicesByName = emptyMap()
        languages = emptyList()
        abandonAudioFocus()
        pendingUtterances = 0
        triggerInFlight = false
        synchronized(pendingBeforeReady) { pendingBeforeReady.clear() }
    }

    /** What each report says; shared with the iOS voice. */
    private val reports = VoiceReports(
        fullHistory = { wheelRepositoryLazy.value.fullHistory.value },
        phoneBattery = ::readPhoneBatteryPercent,
    )

    /**
     * Current spoken navigation cue, kept up to date by [NavigationEngine] as
     * it pushes navState changes. See [VoiceReports.currentNavCue].
     */
    var currentNavCue: String?
        get() = reports.currentNavCue
        set(value) { reports.currentNavCue = value }

    /** Feed one telemetry frame; see [VoiceReports.recordTelemetry]. */
    fun recordTelemetry(data: WheelData) = reports.recordTelemetry(data)

    /** Clears the rolling window, e.g. when the wheel disconnects. */
    fun clearTelemetryHistory() = reports.clearTelemetryHistory()

    private fun buildReportParts(
        data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean,
        only: String? = null,
        preview: Boolean = false,
    ): List<String> = reports.buildReportParts(
        data, settings, isRecording, periodic, AndroidSpokenStrings(spokenContext(settings)), only, preview,
    )

    fun announceStatus(data: WheelData, settings: AppSettings, isRecording: Boolean = false) {
        // Legal Mode Lockdown silences app chatter, including anything that
        // would say "legal mode" or "recording". AlarmEngine speaks through
        // speak() instead, so the rider's own alarms still fire.
        if (legalLockdown.isEngaged()) return
        val parts = buildReportParts(data, settings, isRecording, periodic = true)
        if (parts.isEmpty()) return
        // What the announcement actually said. The spoken-question path has
        // logged this all along and this one never did, so "it announced the
        // wrong thing" left us guessing between a report being off, a value
        // being wrong and a sentence being built wrong.
        announced(parts, periodic = true)
        speakInternal(parts.joinToString(", "), isTrigger = false,
            rate = settings.voiceSpeechRate, localeTag = settings.voiceLocale, voiceName = settings.voiceName)
    }

    fun announceTrigger(data: WheelData, settings: AppSettings, isRecording: Boolean = false) {
        // Legal Mode Lockdown silences app chatter, including anything that
        // would say "legal mode" or "recording". AlarmEngine speaks through
        // speak() instead, so the rider's own alarms still fire.
        if (legalLockdown.isEngaged()) return
        warnIfLowVolume(settings.voiceOutputChannel)
        // Drop immediately if a trigger is already in flight/queued; never queue more than one.
        if (triggerInFlight) return
        val parts = buildReportParts(data, settings, isRecording, periodic = false)
        if (parts.isEmpty()) return
        announced(parts, periodic = false)
        speakInternal(parts.joinToString(", "), isTrigger = true,
            rate = settings.voiceSpeechRate, localeTag = settings.voiceLocale, voiceName = settings.voiceName)
        // The sentence already holds the numbers, so clearing now loses
        // nothing the rider is about to hear. Here rather than in each caller
        // so the tile, a Flic, a headset and the volume keys all agree.
        if (settings.voiceReports.triggerResetAfter) {
            scope.launch { metricsResetLazy.value.resetAll() }
        }
    }

    /**
     * Strings in the language the voice speaks, whatever the app is set to.
     *
     * Blank or matching the interface language costs nothing: the same
     * Context comes straight back, so the common case allocates nothing.
     */
    private fun spokenContext(settings: AppSettings): Context {
        val tag = settings.voiceLocale
        if (tag.isBlank()) return context
        val locale = Locale.forLanguageTag(tag.replace("_", "-"))
        if (locale.language == context.resources.configuration.locales[0].language) return context
        val cfg = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(locale) }
        return context.createConfigurationContext(cfg)
    }

    /** One line per announcement, for the log and the diagnostics panel. */
    private fun announced(parts: List<String>, periodic: Boolean) {
        val what = parts.joinToString(", ")
        val kind = if (periodic) "periodic" else "trigger"
        Log.i(TAG, "announced ($kind): \"$what\"")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("Voice: announced ($kind) \"$what\"")
    }

    private fun streamTypeFor(channel: String): Int = when (channel) {
        "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
        "ALARM" -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_MUSIC
    }

    fun warnIfLowVolume(channel: String) {
        try {
            val stream = streamTypeFor(channel)
            val max = audioManager.getStreamMaxVolume(stream)
            if (max <= 0) return
            val cur = audioManager.getStreamVolume(stream)
            val pct = cur.toFloat() / max.toFloat()
            if (pct <= 0.20f) {
                appNotifier.post(context.getString(R.string.voice_volume_low_toast))
            }
        } catch (_: Exception) {
        }
    }

    /**
     * The announcement as the rider configured it, spoken now, for the
     * settings Play button. Rule 10: the real pills over the real values.
     * Where a statistic has no history yet it says so instead of going
     * silent, which a real announcement would do.
     */
    fun previewReport(data: WheelData, settings: AppSettings, isRecording: Boolean, periodic: Boolean) {
        val parts = buildReportParts(data, settings, isRecording, periodic = periodic, preview = true)
        if (parts.isEmpty()) return
        Log.i(TAG, "preview (${if (periodic) "periodic" else "trigger"}): \"${parts.joinToString(", ")}\"")
        speakInternal(parts.joinToString(", "), isTrigger = true,
            rate = settings.voiceSpeechRate, localeTag = settings.voiceLocale, voiceName = settings.voiceName)
    }

    /**
     * What a report would say right now, or null when it has nothing to say,
     * for a rider who asked for it by name.
     *
     * Separate from [announceTrigger] in one way that matters: it ignores the
     * per-report enable flags. Those say what a periodic announcement or a
     * button press should contain, which is a different question from what a
     * rider just asked for. Someone who says "PWM" wants PWM, whether or not
     * they wanted it read out every two minutes.
     *
     * Returns the sentence rather than speaking it because a spoken answer
     * needs the sentence, not just the fact that one was spoken: the rider
     * sees it in the transcript, and the controller has to be able to tell
     * "the wheel has no reading for this" from "the report said it" before it
     * decides what to speak.
     */
    fun reportText(
        report: String,
        data: WheelData,
        settings: AppSettings,
        isRecording: Boolean = false,
    ): String? {
        if (legalLockdown.isEngaged()) return null
        val parts = buildReportParts(data, settings, isRecording, periodic = false, only = report)
        if (parts.isEmpty()) return null
        return parts.joinToString(", ")
    }

    /**
     * The whole on-demand report as one sentence, for a rider who asked.
     *
     * The trigger set, not the periodic one. Those are configured separately
     * on purpose: a periodic announcement says only what matters while moving
     * and a triggered one tends to say everything, and a rider who asks out
     * loud has asked, the same as pressing the dashboard button.
     *
     * Returns the text rather than speaking it, so the spoken-question path
     * can put it on the tile and log it the way it does every other answer,
     * instead of a second voice starting underneath the first.
     */
    fun triggerReportText(
        data: WheelData,
        settings: AppSettings,
        isRecording: Boolean = false,
    ): String? {
        if (legalLockdown.isEngaged()) return null
        val parts = buildReportParts(data, settings, isRecording, periodic = false)
        if (parts.isEmpty()) return null
        return parts.joinToString(", ")
    }

    /** The phone's own battery level (0-100) for the "phone" voice report item. */
    private fun readPhoneBatteryPercent(): Int {
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE)
            as? android.os.BatteryManager
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { lvl ->
            if (lvl in 0..100) return lvl
        }
        val intent = context.registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }

    override fun announceEvent(text: String) {
        // Legal Mode Lockdown silences app chatter, including anything that
        // would say "legal mode" or "recording". AlarmEngine speaks through
        // speak() instead, so the rider's own alarms still fire.
        if (legalLockdown.isEngaged()) return
        speak(text)
    }

    fun speak(text: String) {
        speakInternal(text, isTrigger = false, rate = currentRate,
            localeTag = currentLocaleTag, voiceName = currentVoiceName)
    }

    private fun speakInternal(
        text: String, isTrigger: Boolean, rate: Float, localeTag: String, voiceName: String,
        flush: Boolean = false,
    ) {
        // Session "silence" toggle: swallow every spoken utterance without
        // touching audio focus or TTS state.
        if (cheatState.silence.value) return
        // Buffer calls that arrive before TTS async init completes.
        if (!isReady) {
            synchronized(pendingBeforeReady) {
                if (isTrigger) {
                    if (triggerInFlight) return
                    triggerInFlight = true
                }
                pendingBeforeReady.add(DeferredUtterance(text, isTrigger, rate, localeTag, voiceName))
            }
            return
        }
        if (isTrigger) {
            // Double-check after ready; this guards concurrent presses.
            synchronized(this) {
                if (triggerInFlight) return
                triggerInFlight = true
            }
        }
        tts?.setSpeechRate(rate)
        applyLocale(localeTag, voiceName)
        val prefix = if (isTrigger) "trig_" else "alert_"
        val utteranceId = "$prefix${System.currentTimeMillis()}"
        synchronized(this) { pendingUtterances++ }
        tonePlayer.noteSound()  // speech wants the warm route as much as a beep
        requestAudioFocus()
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
        }
        // Previews flush (stop whatever is speaking and play this one now);
        // live announcements queue so they never talk over each other.
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts?.speak(text, queueMode, params, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak returned ERROR; releasing focus")
            onUtteranceFinished(utteranceId)
        }
    }

    fun testSpeak(text: String, speechRate: Float, localeTag: String, voiceName: String = "") {
        warnIfLowVolume(currentOutputChannel)
        speakInternal(text, isTrigger = false, rate = speechRate,
            localeTag = localeTag, voiceName = voiceName, flush = true)
    }

    private fun applyLocale(localeTag: String, voiceName: String) {
        val locale = Locale.forLanguageTag(localeTag.replace("_", "-"))
        tts?.language = locale
        if (voiceName.isNotBlank()) {
            val v = voiceNamed(voiceName)
            // Only apply when it matches the target language, so a stale name from
            // a previous language does not override the freshly set locale.
            if (v != null && v.locale.language == locale.language) {
                try { tts?.voice = v } catch (_: Exception) {}
            }
        }
    }
}
