package com.eried.eucplanet.voice

import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.MetricCatalog
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.service.TonePlayer
import com.eried.eucplanet.service.VoiceService
import com.eried.eucplanet.voice.VoiceAnswer.Answer
import com.eried.eucplanet.voice.VoiceVocabulary.Kind
import com.eried.eucplanet.voice.VoiceVocabulary.SpokenTerm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One listening session, end to end: tone, microphone, match, spoken answer.
 *
 * The rules that decide what gets said live in [VoiceCommandSession] and are
 * unit tested. This is the part that cannot be: it holds the Android pieces,
 * turns the catalog into names in the rider's language, and reads the values.
 *
 * It never goes quiet. Every path out of here speaks something, because a
 * rider at speed who hears nothing cannot tell a misheard word from a broken
 * feature, and the blank CONSUMPTION tile is what that costs.
 */
@Singleton
class VoiceCommandController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val voiceService: VoiceService,
    private val tonePlayer: TonePlayer,
    private val appNotifier: com.eried.eucplanet.util.AppNotifier,
    private val appHealth: com.eried.eucplanet.data.repository.AppHealthRepository,
    private val weatherRepository: com.eried.eucplanet.weather.WeatherRepository,
    private val navigationEngine: com.eried.eucplanet.nav.NavigationEngine,
    private val tripRepository: com.eried.eucplanet.data.repository.TripRepository,
    // A Provider, not the manager itself: FlicManager injects this
    // controller, and Dagger cannot build a cycle of two constructors.
    private val flicManager: javax.inject.Provider<com.eried.eucplanet.flic.FlicManager>,
) {

    private companion object {
        const val TAG = "VoiceCommand"
        /** Short and high, so it carries over wind and is over before the
         *  rider starts speaking. */
        /**
         * How long to wait for the recogniser to open before cueing anyway.
         *
         * It is normally ready in well under this. The cap is there so a
         * device that never reports ready still gets a cue and a window,
         * rather than a rider holding a button that does nothing.
         */
        const val READY_WAIT_MS = 1500L

        /** How still the words must be before the transcript shows them. */
        const val PARTIAL_SETTLE_MS = 500L

        /**
         * How long the system recogniser needs to let go.
         *
         * Only paid when replacing a session already listening, so a first
         * press is as quick as it ever was. Measured rather than guessed: a
         * replacement 50 milliseconds after the stop was still refused.
         */
        const val RECOGNISER_RELEASE_MS = 300L

        /**
         * How long the audio route needs after the microphone closes.
         *
         * Paid once per answer, and it buys the difference between a clean
         * first word and a scratch in front of it.
         */
        const val ROUTE_SETTLE_MS = 220L

        /**
         * How long the answer stays up after the speech has finished.
         *
         * Enough that the tile does not blink out mid-syllable on a device
         * that reports the end early, short enough that it reads as done.
         */
        const val ANSWER_LINGER_MS = 600L

        /** How long to wait for the speech to begin before giving up on it. */
        const val SPEECH_START_WAIT_MS = 1500L

        /** And for it to end. Generous: a report can be a long sentence. */
        const val SPEECH_END_WAIT_MS = 20000L

        /** How long to wait for a yes. Short: it is one word. */
        const val CONFIRM_WINDOW_MS = 4000L

        /**
         * How long a rider waits for a forecast before being told there is
         * none.
         *
         * Generous, because the alternative answer is "no weather yet" and
         * they have already been told a check is happening. Capped all the
         * same: a session that never ends is a microphone button that looks
         * broken.
         */
        const val WEATHER_FETCH_MS = 8000L

        const val PROMPT_HZ = 1320
        const val PROMPT_MS = 90
    }

    /** What the dashboard renders while a session runs. */
    sealed interface UiState {
        data object Idle : UiState
        data object Listening : UiState
        data class Heard(val text: String) : UiState
        data class Spoke(val text: String) : UiState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _showVocabulary = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emitted when the rider asks what they can say.
     *
     * Three spoken examples is what fits in an answer at speed; the whole list
     * is what they actually asked for, and a screen can hold it. Whichever
     * surface is in front of them opens it.
     */
    val showVocabulary: SharedFlow<Unit> = _showVocabulary.asSharedFlow()

    private var session: Job? = null

    /**
     * The microphone currently open, if any.
     *
     * Cancelling the session coroutine does not close a recogniser: it is an
     * Android object with its own lifetime, and the stop call lives in code
     * the cancellation skips. Held here so a second press can close the first
     * one before opening its own, which is the difference between "listening
     * again" and ERROR_RECOGNIZER_BUSY dressed up as another app stealing the
     * microphone.
     */
    private var activeMic: VoiceListener? = null

    /** The last finished ride, read before a match so `read` need not suspend. */
    private var lastTripSnapshot: com.eried.eucplanet.data.model.TripRecord? = null

    /** True when the rider has granted the microphone. */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Open the microphone. Safe to call again while listening: the rider
     * pressing twice should not start a second recogniser on top of the first.
     */
    /**
     * @param notify post a transient saying it is listening. The default,
     *   because most surfaces that start this - a Flic, a volume key, the
     *   watch, the HUD - show the rider nothing at all, and a microphone that
     *   opened silently is indistinguishable from a button that did nothing.
     *   The dashboard tile passes false: it lights up, and two cues for one
     *   press is noise.
     */
    fun listen(listener: VoiceListener? = null, notify: Boolean = true) {
        // Pressing again means "forget that, listen to this". It used to mean
        // nothing at all: the second press was swallowed while the first
        // session ran out its window, so a rider who fumbled the first
        // question had to wait for the app to finish not understanding it.
        // A press while it is listening means stop. It used to mean start
        // again, which looked the same from the outside for the first moment
        // and then was not: the rider got a fresh window they had not asked
        // for, and the only way out was to wait it out.
        if (session?.isActive == true) {
            session?.cancel()
            activeMic?.stop()
            activeMic = null
            _state.value = UiState.Idle
            scope.launch { closingCue(settingsRepository.get()) }
            Log.i(TAG, "stopped by a second press")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("Voice: stopped by a second press")
            return
        }
        // Nothing should be holding the microphone here, but a session that
        // ended badly can leave one behind, and the recogniser does not hand
        // it back the instant stop() returns.
        val handingOver = activeMic != null
        activeMic?.stop()
        activeMic = null
        _state.value = UiState.Idle
        session = scope.launch {
            val settings = settingsRepository.get()
            applyLocales(settings)
            // Every surface that can start listening goes through here, so the
            // check belongs here rather than in each of them. A rider pressing
            // a watch stem or a HUD button with the permission never granted
            // was getting the microphone opened and then a recogniser error
            // dressed up as something else holding it.
            if (listener == null && !hasMicPermission()) {
                // Name the thing that is missing. "Voice commands needs
                // setting up first" described a setup step that does not
                // exist, and left a rider with nothing to act on.
                appHealth.noteMicrophoneNeeded()
                val text = voiceCtx.getString(R.string.voice_answer_no_mic_permission)
                _state.value = UiState.Spoke(text)
                voiceService.speak(text)
                Log.i(TAG, "refused, no microphone permission")
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                    "Voice: refused, no microphone permission"
                )
                delay(ANSWER_LINGER_MS)
                _state.value = UiState.Idle
                return@launch
            }
            val mic = listener ?: AndroidVoiceListener(
                context = context,
                languageTag = VoiceLocaleTag.tag(recognitionTag(settings)),
            )
            _state.value = UiState.Listening
            // Whatever the app was saying, it stops now. Otherwise the
            // recogniser spends the window listening to our own voice.
            voiceService.stopSpeaking()
            if (notify) appNotifier.post(context.getString(R.string.voice_listening))

            // Open the microphone before cueing the rider. The cue used to
            // come first, so a rider who answered it promptly spoke into a
            // recogniser that had not finished starting and lost the first
            // word, which is why a word as short as "help" rarely landed.
            // A spoken cue has to finish before the microphone opens, or it
            // lands in the recording as part of the question. That is the
            // reason the tone exists at all, so the word takes the slot the
            // tone cannot use rather than the other way round.
            if (settings.voiceCommands.promptCue == com.eried.eucplanet.data.model.VoiceCommandSettings.CUE_VOICE) {
                voiceService.speak(voiceCtx.getString(R.string.voice_cue_listening))
                withTimeoutOrNull(SPEECH_START_WAIT_MS) { voiceService.isSpeaking.first { it } }
                withTimeoutOrNull(SPEECH_END_WAIT_MS) { voiceService.isSpeaking.first { !it } }
                delay(ROUTE_SETTLE_MS)
            }
            if (handingOver) delay(RECOGNISER_RELEASE_MS)
            activeMic = mic
            mic.start()
            val readyBy = System.currentTimeMillis() + READY_WAIT_MS
            while (System.currentTimeMillis() < readyBy &&
                mic.state.value is ListenState.Preparing
            ) {
                delay(20)
            }
            // The tone, if the rider kept it. A headset with a cue of its
            // own does not need a second one, and the spoken alternative has
            // already played by now: it cannot play here, because the
            // microphone is open and it would be heard as the first words of
            // the question.
            openingCue(settings)

            // The window starts now, not at the press: the seconds a rider
            // sets are seconds they get to speak.
            val deadline = System.currentTimeMillis() +
                settings.advanced.voiceListenWindowSec * 1000L
            var heard: String? = null
            var micBusy = false
            // Partials arrive a word at a time. Showing each one made the
            // transcript flicker through "what's", "what's the" and so on,
            // which is a lot of movement for a rider to read at speed. Wait
            // until the words stop changing, so what appears is a phrase.
            var pending: String? = null
            var pendingSince = 0L
            while (System.currentTimeMillis() < deadline) {
                when (val s = mic.state.value) {
                    is ListenState.Partial -> {
                        val now = System.currentTimeMillis()
                        if (s.text != pending) {
                            pending = s.text
                            pendingSince = now
                        } else if (now - pendingSince >= PARTIAL_SETTLE_MS &&
                            _state.value != UiState.Heard(s.text)
                        ) {
                            _state.value = UiState.Heard(s.text)
                        }
                    }
                    is ListenState.Final -> { heard = s.text; break }
                    is ListenState.Failed -> { micBusy = s.micUnavailable; break }
                    else -> {}
                }
                delay(60)
            }
            mic.stop()
            if (activeMic === mic) activeMic = null
            // Closing the microphone takes the device back out of its
            // communication audio mode, and speech started during that switch
            // arrives with the switch audible under its first syllable. The
            // chirp had the same problem at the other end of the session; this
            // is the same wait, for the same reason.
            delay(ROUTE_SETTLE_MS)
            // A rider recording a video did not mumble, they are being told
            // the microphone is spoken for. Saying "I did not catch that"
            // there is the app blaming them for its own conflict.
            val spoke = if (micBusy) sayMicUnavailable() else answer(heard, settings)

            // The falling cue closes every session, after whatever was said
            // rather than over it. It is the question ending, not the answer
            // beginning, so it waits for the speech to finish: the listener
            // tells us when it starts and when it stops, and both waits are
            // capped so a speech engine that never reports back cannot leave
            // the session hanging.
            // Only wait for speech there is going to be. A rider who asked
            // for the low "no" tone instead of the sentence would otherwise
            // sit through a second and a half of waiting for a voice that is
            // never coming, and then get a closing chirp on top of the tone
            // that already said the same thing.
            if (spoke) {
                withTimeoutOrNull(SPEECH_START_WAIT_MS) { voiceService.isSpeaking.first { it } }
                withTimeoutOrNull(SPEECH_END_WAIT_MS) { voiceService.isSpeaking.first { !it } }
                closingCue(settings)
            }

            // A short tail, not four seconds. That number was chosen when the
            // session ended the moment the speech started, so it was the only
            // thing keeping the answer on screen while it was being said. The
            // session now waits for the speech to finish, so four more
            // seconds on top is four seconds of a tile that looks busy and is
            // not.
            delay(ANSWER_LINGER_MS)
            _state.value = UiState.Idle
        }
    }

    /**
     * The things the app knows that are not wheel readings.
     *
     * Each returns a finished sentence or null, and null means "nothing to
     * say yet" rather than an error: no route, no trips, no forecast.
     */
    private fun special(key: String, settings: com.eried.eucplanet.data.model.AppSettings): String? = when (key) {
        VoiceVocabulary.Special.CONNECTED -> {
            val name = wheelRepository.connectedDeviceName.value
            if (wheelRepository.connectionState.value ==
                com.eried.eucplanet.ble.ConnectionState.CONNECTED
            ) {
                voiceCtx.getString(R.string.voice_special_connected, name.orEmpty())
            } else {
                voiceCtx.getString(R.string.voice_special_not_connected)
            }
        }

        VoiceVocabulary.Special.UPTIME -> {
            val since = wheelRepository.connectedSinceMs.value
            if (since <= 0L) voiceCtx.getString(R.string.voice_special_not_connected)
            else voiceCtx.getString(
                R.string.voice_special_uptime,
                spokenDuration(System.currentTimeMillis() - since),
            )
        }

        VoiceVocabulary.Special.WEATHER -> weatherVerdict(settings)

        VoiceVocabulary.Special.AIR_TEMP -> currentHour()?.let { h ->
            voiceCtx.getString(R.string.voice_sp_air_temp_answer, spokenTemp(h.tempC, settings))
        }

        VoiceVocabulary.Special.WIND -> currentHour()?.let { h ->
            // The gust only when it is worth saying. A gust equal to the wind
            // is not a gust, and reading both every time makes the answer
            // twice as long for nothing.
            val wind = spokenWind(h.windMs, settings)
            if (h.gustMs > h.windMs * 1.3f) {
                voiceCtx.getString(
                    R.string.voice_sp_wind_gust_answer, wind, spokenWind(h.gustMs, settings),
                )
            } else {
                voiceCtx.getString(R.string.voice_sp_wind_answer, wind)
            }
        }

        // Zero is the documented "the provider did not send it", not dry air.
        VoiceVocabulary.Special.HUMIDITY -> currentHour()
            ?.takeIf { it.humidityPct > 0f }
            ?.let {
                voiceCtx.getString(
                    R.string.voice_sp_humidity_answer, "%.0f".format(it.humidityPct),
                )
            }

        VoiceVocabulary.Special.DAYLIGHT -> daylightLeft()

        VoiceVocabulary.Special.NAV_NEXT -> navNext()

        VoiceVocabulary.Special.LAST_TRIP -> lastTrip(settings)

        // The on-demand report, the same one the dashboard's Voice button
        // speaks and configured by the Trigger column, not the Periodic one.
        //
        // This used to return null under a comment saying it was handled
        // earlier. It was not handled anywhere, so every "report", "status"
        // and "voice report" answered "No report yet" and did nothing at all.
        VoiceVocabulary.Special.REPORT -> voiceService.triggerReportText(
            wheelRepository.wheelData.value,
            settings,
            isRecording = tripRepository.recording.value,
        )

        else -> null
    }

    /**
     * Is it a good day to ride.
     *
     * The same RidabilityScore the dashboard panel and the widgets use, via
     * the one helper that reads the rider's own thresholds, so the spoken
     * verdict cannot disagree with the number on screen.
     */
    /**
     * Whether this phrase is about the weather, decided before answering it.
     *
     * The matcher runs here and again inside the session. It is pure and
     * cheap, and the alternative is a read path that can suspend, which would
     * mean every reading in the app paying for the one that needs a network.
     */
    private fun weatherWanted(
        phrase: String,
        vocabulary: List<SpokenTerm>,
        onDashboard: Set<String>,
    ): Boolean {
        val hit = VoiceCommandMatcher.match(phrase, vocabulary, onDashboard)
        return hit is VoiceCommandMatcher.VoiceMatch.Hit && hit.term.key in WEATHER_KEYS
    }

    /**
     * Bring the forecast up to date, saying so if it is going to take a while.
     *
     * The spoken line only happens when there is really nothing to say yet.
     * A forecast under half an hour old comes straight back out of
     * ensureFresh without touching the network, and announcing a check that
     * takes no time is worse than saying nothing.
     */
    private suspend fun fetchWeather(settings: com.eried.eucplanet.data.model.AppSettings) {
        val loc = tripRepository.currentLocation.value
            ?: tripRepository.lastKnownLocation.value
            ?: return
        val cold = currentHour() == null
        if (cold) {
            val wait = voiceCtx.getString(R.string.voice_weather_checking)
            _state.value = UiState.Spoke(wait)
            voiceService.speak(wait)
            withTimeoutOrNull(SPEECH_START_WAIT_MS) { voiceService.isSpeaking.first { it } }
            withTimeoutOrNull(SPEECH_END_WAIT_MS) { voiceService.isSpeaking.first { !it } }
        }
        runCatching {
            withTimeoutOrNull(WEATHER_FETCH_MS) {
                weatherRepository.ensureFresh(
                    loc.latitude, loc.longitude,
                    com.eried.eucplanet.weather.WeatherSource.byId(settings.weather.source),
                    force = false,
                )
            }
        }.onFailure { Log.w(TAG, "weather fetch failed", it) }
    }

    /** The forecast hour closest to now, or null when there is no forecast. */
    private fun currentHour(): com.eried.eucplanet.weather.HourForecast? {
        val hours = weatherRepository.forecast.value?.hours ?: return null
        val now = System.currentTimeMillis()
        return hours.minByOrNull { kotlin.math.abs(it.timeMs - now) }
    }

    /** Outside air in the rider's own unit, the way a report says it. */
    private fun spokenTemp(
        tempC: Float,
        settings: com.eried.eucplanet.data.model.AppSettings,
    ): String = "%.0f".format(
        com.eried.eucplanet.util.Units.temperature(
            tempC, com.eried.eucplanet.util.Units.effectiveTempUnit(settings),
        )
    )

    /**
     * Wind in the rider's speed unit.
     *
     * The forecast carries metres per second, which is the one unit nobody
     * rides in. Converted the same way the weather widget converts it, so the
     * spoken number matches the one on the home screen.
     */
    private fun spokenWind(
        ms: Float,
        settings: com.eried.eucplanet.data.model.AppSettings,
    ): String {
        val unit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
        return "%.0f %s".format(
            com.eried.eucplanet.util.Units.speed(ms * 3.6f, unit),
            com.eried.eucplanet.util.Units.speedUnit(voiceCtx, unit),
        )
    }

    private fun weatherVerdict(settings: com.eried.eucplanet.data.model.AppSettings): String? {
        val hour = currentHour() ?: return null
        val b = com.eried.eucplanet.weather.WeatherScoring.scoreOf(hour, settings)
        val verdict = voiceCtx.getString(
            when {
                b.score >= 3f -> R.string.voice_weather_great
                b.score >= 1f -> R.string.voice_weather_good
                b.score >= -1f -> R.string.voice_weather_ok
                b.score >= -3f -> R.string.voice_weather_poor
                else -> R.string.voice_weather_bad
            }
        )
        // One reason, the worst one. A list of everything wrong with the
        // afternoon is not what a rider standing at the door asked for.
        val reason = when {
            b.snow -> R.string.voice_weather_snow
            b.rain -> R.string.voice_weather_rain
            b.wind -> R.string.voice_weather_wind
            b.cold -> R.string.voice_weather_cold
            b.hot -> R.string.voice_weather_hot
            b.night -> R.string.voice_weather_night
            else -> null
        }
        // The numbers behind the verdict, because "good to ride" on its own
        // is a judgement a rider cannot check. The score is signed on purpose:
        // it is the same number the weather panel draws, and plus or minus is
        // the half of it that says which side of neutral the day is on.
        val full = voiceCtx.getString(
            R.string.voice_special_weather_full,
            verdict,
            "%+.0f".format(b.score),
            spokenTemp(hour.tempC, settings),
            spokenWind(hour.windMs, settings),
            "%.0f".format(hour.humidityPct),
        )
        // One reason, the worst one, and only when there is something wrong.
        return if (reason == null) full
        else voiceCtx.getString(R.string.voice_special_weather, full, voiceCtx.getString(reason))
    }

    /**
     * How long until the sun goes down, stepped rather than solved.
     *
     * SunCalc gives an elevation for a moment, so this walks forward in ten
     * minute steps until the sun is under the horizon. Ten minutes is finer
     * than anyone speaks a time to, and a whole day of steps is 144 cheap
     * trigonometric calls.
     */
    private fun daylightLeft(): String? {
        val f = weatherRepository.forecast.value ?: return null
        val now = System.currentTimeMillis()
        val sun = com.eried.eucplanet.weather.SunCalc
        if (sun.elevationDeg(now, f.lat, f.lon) <= 0.0) {
            return voiceCtx.getString(R.string.voice_special_dark)
        }
        var t = now
        val end = now + 24L * 60 * 60 * 1000
        while (t < end) {
            t += 10L * 60 * 1000
            if (sun.elevationDeg(t, f.lat, f.lon) <= 0.0) {
                return voiceCtx.getString(R.string.voice_special_daylight, spokenDuration(t - now))
            }
        }
        // The sun never sets here today, which happens where this app is used.
        return voiceCtx.getString(R.string.voice_special_daylight_all_day)
    }

    /** What the navigation would say next, or that there is no route. */
    private fun navNext(): String {
        val nav = navigationEngine.navState.value
        if (!nav.active) return voiceCtx.getString(R.string.voice_special_nav_none)
        val main = nav.primaryText.ifBlank { return voiceCtx.getString(R.string.voice_special_nav_none) }
        val distance = nav.distanceText
        return if (distance.isBlank()) main
        else voiceCtx.getString(R.string.voice_special_nav, main, distance)
    }

    /** The last finished ride, fetched before the match. */
    private fun lastTrip(settings: com.eried.eucplanet.data.model.AppSettings): String? {
        val trip = lastTripSnapshot ?: return voiceCtx.getString(R.string.voice_special_no_trips)
        val unit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings)
        val distance = "%.1f %s".format(
            com.eried.eucplanet.util.Units.distance(trip.distanceKm, unit),
            com.eried.eucplanet.util.Units.distanceUnit(unit),
        )
        val duration = spokenDuration((trip.endTime ?: trip.startTime) - trip.startTime)
        return voiceCtx.getString(R.string.voice_special_last_trip, distance, duration)
    }

    /** Hours and minutes, spoken the way a rider would say them. */
    private fun spokenDuration(ms: Long): String {
        val totalMinutes = (ms / 60000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> voiceCtx.getString(
                R.string.voice_duration_h_m, hours.toString(), minutes.toString()
            )
            else -> voiceCtx.getString(R.string.voice_duration_m, minutes.toString())
        }
    }

    /**
     * Carry out a spoken action.
     *
     * The state-aware pair is the reason this is not a straight key lookup:
     * a rider saying "lights on" means on, not "flip whatever they are", and
     * a toggle would turn them off when they were already lit. The wheel has
     * no separate on and off commands, so the state decides whether to send
     * anything at all.
     */
    private suspend fun runAction(key: String) {
        // Not a wheel command and not in the action catalog: the periodic
        // announcements are a setting, and the widget button writes the same
        // field. Handled before the catalog lookup because there is nothing
        // to look up.
        if (key == VoiceAction.ANNOUNCE_ON || key == VoiceAction.ANNOUNCE_OFF) {
            val on = key == VoiceAction.ANNOUNCE_ON
            settingsRepository.update { it.copy(voiceEnabled = on) }
            Log.i(TAG, "announcements ${if (on) "on" else "off"}")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                "Voice: announcements ${if (on) "on" else "off"}"
            )
            return
        }
        val data = wheelRepository.wheelData.value
        val catalogKey = when (key) {
            VoiceAction.LIGHT_ON -> if (data.lightOn) null else "LIGHT_TOGGLE"
            VoiceAction.LIGHT_OFF -> if (data.lightOn) "LIGHT_TOGGLE" else null
            VoiceAction.LOCK -> if (wheelRepository.locked.value) null else "LOCK_TOGGLE"
            VoiceAction.UNLOCK -> if (wheelRepository.locked.value) "LOCK_TOGGLE" else null
            else -> key
        }
        if (catalogKey == null) {
            // Already as asked. Saying so beats silence, and beats toggling.
            Log.i(TAG, "action $key already satisfied")
            return
        }
        flicManager.get().runAction(catalogKey)
        Log.i(TAG, "action $key ran as $catalogKey")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("Voice: did $key")
    }

    /**
     * Ask before doing, then listen for a yes.
     *
     * A second window rather than a dialog, because the whole point of this
     * feature is that the rider is not looking at the screen.
     */
    private suspend fun confirmAndRun(answer: Answer.Act, settings: com.eried.eucplanet.data.model.AppSettings) {
        val mic = AndroidVoiceListener(
            context = context,
            languageTag = VoiceLocaleTag.tag(recognitionTag(settings)),
        )
        mic.start()
        val readyBy = System.currentTimeMillis() + READY_WAIT_MS
        while (System.currentTimeMillis() < readyBy && mic.state.value is ListenState.Preparing) {
            delay(20)
        }
        openingCue(settings)
        val deadline = System.currentTimeMillis() + CONFIRM_WINDOW_MS
        var said: String? = null
        while (System.currentTimeMillis() < deadline) {
            when (val st = mic.state.value) {
                is ListenState.Final -> { said = st.text; break }
                is ListenState.Failed -> break
                else -> {}
            }
            delay(60)
        }
        mic.stop()
        val yes = heardCtx.getString(R.string.voice_yes_terms)
            .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val heardYes = said?.lowercase()?.let { spoken -> yes.any { spoken.contains(it) } } == true
        if (heardYes) {
            runAction(answer.key)
            _state.value = UiState.Spoke(answer.name)
            voiceService.speak(answer.name)
        } else {
            val text = voiceCtx.getString(R.string.voice_action_cancelled)
            _state.value = UiState.Spoke(text)
            voiceService.speak(text)
            Log.i(TAG, "action ${answer.key} cancelled")
        }
    }

    /** Something else holds the microphone. Say so, rather than blame the rider. */
    private fun sayMicUnavailable(): Boolean {
        val text = voiceCtx.getString(R.string.voice_answer_mic_busy)
        _state.value = UiState.Spoke(text)
        voiceService.speak(text)
        Log.i(TAG, "said \"$text\" (mic busy)")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("Voice: said \"$text\" (mic busy)")
        return true
    }

    /** Work out the reply and speak it. Never returns without saying something. */
    private suspend fun answer(
        heard: String?,
        settings: com.eried.eucplanet.data.model.AppSettings,
    ): Boolean {
        val vocabulary = vocabulary()
        val onDashboard = settings.dashboardMetricOrder
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        if (heard.isNullOrBlank()) {
            return speak(VoiceAnswer.notUnderstood(vocabulary, onDashboard), settings)
        }
        _state.value = UiState.Heard(heard)
        // Peel off "max" or "average" before matching, so the metric behind it
        // is found by its own name.
        val parsed = VoiceStatModifier.parse(heard, statPhrases())
        val phrase = if (parsed.stat != null && parsed.rest.isNotBlank()) parsed.rest else heard
        // The matcher picks one term, but `read` cannot suspend, so the two
        // readings that need I/O are fetched before the match rather than
        // inside it. Both are a single value from a flow already in memory.
        lastTripSnapshot = runCatching { tripRepository.allTrips.first() }
            .getOrNull()?.filter { it.endTime != null }?.maxByOrNull { it.startTime }
        // The forecast is not one of those. It lives in memory only, filled by
        // the dashboard panel and by the weather widget's worker, so a rider
        // who has neither open has never had one: asking for the weather
        // answered "no weather yet" on every fresh start of the app, forever.
        // Fetched here instead, and only when the phrase turned out to be
        // about weather, so asking for the battery never touches the network.
        if (weatherWanted(phrase, vocabulary, onDashboard)) fetchWeather(settings)
        val answer = VoiceCommandSession.answer(
            heard = phrase,
            vocabulary = vocabulary,
            onDashboard = onDashboard,
            read = { term -> readingBlocking(term, settings, parsed.stat) },
            needsConfirm = { key -> key in CONFIRMED_ACTIONS },
        )
        return speak(answer, settings)
    }

    /**
     * The current value for a term.
     *
     * Reports come back null so [speak] can hand them to VoiceService, which
     * already formats and localises every one of them. Metrics that are also
     * reports take the same route, which is why Speed and Battery sound like
     * the announcement a rider already knows rather than a bare number.
     */
    /** What the session calls: everything suspending has already happened. */
    private fun readingBlocking(
        term: SpokenTerm,
        settings: com.eried.eucplanet.data.model.AppSettings,
        stat: VoiceStatModifier.Stat? = null,
    ): VoiceCommandSession.Reading? {
        // A statistic only means something for a live metric with history
        // behind it. Asked of a report or a special it is ignored, which is
        // kinder than refusing: "max weather" is a slip, not a request.
        if (stat != null && term.kind == Kind.METRIC) {
            statReading(term, settings, stat)?.let { return it }
        }
        return readingOf(term, settings)
    }

    /**
     * A metric over its rolling history, rather than right now.
     *
     * The window, the sampling and the arithmetic already exist and are
     * already shared: this is the same computeDashboardStatValue the tiles
     * and the slot sheet use, over the same buffer, so a spoken max cannot
     * disagree with the corner readout on the tile beside it.
     */
    private fun statReading(
        term: SpokenTerm,
        settings: com.eried.eucplanet.data.model.AppSettings,
        stat: VoiceStatModifier.Stat,
    ): VoiceCommandSession.Reading? {
        val samples = historyFor(term.key) ?: return null
        if (samples.isEmpty()) {
            return VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
        }
        val dashStat = when (stat) {
            VoiceStatModifier.Stat.MAX -> com.eried.eucplanet.ui.settings.DashboardStat.MAX
            VoiceStatModifier.Stat.MIN -> com.eried.eucplanet.ui.settings.DashboardStat.MIN
            VoiceStatModifier.Stat.AVG -> com.eried.eucplanet.ui.settings.DashboardStat.AVG
            // Highest level held for two seconds, not the highest sample. A
            // rider asking for peak PWM means what the wheel sustained, not a
            // spike the sparkline barely drew.
            VoiceStatModifier.Stat.PEAK ->
                com.eried.eucplanet.ui.settings.DashboardStat.SUSTAINED_PEAK
        }
        val raw = com.eried.eucplanet.ui.settings.computeDashboardStatValue(
            dashStat, samples, Float.NaN,
        ) ?: return VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
        if (raw.isNaN()) return VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
        val value = formatMetric(term.key, raw, settings)
        // The statistic is said, not implied: "max speed, 42 km/h" and
        // "speed, 42 km/h" are different answers to different questions.
        return VoiceCommandSession.Reading(
            null, null,
            reportText = voiceCtx.getString(
                R.string.voice_stat_answer, statLabel(stat), term.name, value,
            ),
        )
    }

    /** The rolling buffer for a metric key, legacy six or the keyed extras. */
    private fun historyFor(key: String): List<com.eried.eucplanet.data.repository.MetricSample>? {
        val h = wheelRepository.fullHistory.value
        return when (key) {
            "BATTERY" -> h.battery
            "TEMPERATURE" -> h.temperature
            "VOLTAGE" -> h.voltage
            "CURRENT" -> h.current
            "LOAD" -> h.load
            "SPEED" -> h.speed
            else -> h.extras[key]
        }
    }

    /** The word to say for a statistic: the first phrasing its locale lists. */
    private fun statLabel(stat: VoiceStatModifier.Stat): String =
        statPhrases()[stat]?.split(",")?.firstOrNull()?.trim().orEmpty()

    private fun statPhrases(): Map<VoiceStatModifier.Stat, String> = mapOf(
        VoiceStatModifier.Stat.MAX to heardCtx.getString(R.string.voice_stat_max_terms),
        VoiceStatModifier.Stat.MIN to heardCtx.getString(R.string.voice_stat_min_terms),
        VoiceStatModifier.Stat.AVG to heardCtx.getString(R.string.voice_stat_avg_terms),
        VoiceStatModifier.Stat.PEAK to heardCtx.getString(R.string.voice_stat_peak_terms),
    )

    private fun readingOf(
        term: SpokenTerm,
        settings: com.eried.eucplanet.data.model.AppSettings,
    ): VoiceCommandSession.Reading? {
        if (term.kind == Kind.SPECIAL) {
            // A whole sentence rather than a value and a unit, so it travels
            // the same road a report does.
            val text = special(term.key, settings)
            return if (text.isNullOrBlank()) {
                VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
            } else {
                VoiceCommandSession.Reading(null, null, reportText = text)
            }
        }
        val data = wheelRepository.wheelData.value
        val connected = wheelRepository.connectionState.value ==
            com.eried.eucplanet.ble.ConnectionState.CONNECTED

        // A wheel that is not connected has no readings, only the zeroes a
        // fresh WheelData is born with. The report route will happily phrase
        // those as "temperature 0 degrees", which is the one thing this must
        // never do: a rider hearing a number believes it. Asked with no wheel,
        // the honest answer is that there is nothing yet.
        if (!connected && term.key !in OFF_WHEEL) {
            return VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
        }

        // Some things the wheel simply does not have. That is not "not yet",
        // and telling a rider to wait for a sensor their wheel was built
        // without is worse than telling them it is missing.
        if (UNSUPPORTED[term.key]?.invoke(data) == true) {
            return VoiceCommandSession.Reading(null, VoiceAnswer.Reason.UNSUPPORTED_BY_WHEEL)
        }

        // Anything with a spoken report of its own borrows that sentence: it is
        // already in the rider's language and units, and it is the same wording
        // the periodic announcement uses, so asking for Battery sounds like the
        // app rather than like a different feature.
        val report = if (term.kind == Kind.REPORT) term.key else REPORT_FOR_METRIC[term.key]
        if (report != null) {
            val text = voiceService.reportText(report, data, settings)
            // A report with nothing to say is the wheel having sent nothing
            // yet, not a reason to stay quiet.
            return if (text.isNullOrBlank()) {
                VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
            } else {
                VoiceCommandSession.Reading(null, null, reportText = text)
            }
        }
        // A state before a number. The light is on or off, not 1.0, and
        // asking for it used to fall through the numeric extractors to "No
        // Light yet" forever: the metric was in the vocabulary and in the
        // "what can I say" list, so the app offered it and then never
        // answered it.
        STATE_READINGS[term.key]?.let { read ->
            val said = read(data, voiceCtx)
            return if (said == null) {
                VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
            } else {
                VoiceCommandSession.Reading(said, null)
            }
        }
        val value = EXTRACTORS[term.key]?.invoke(data)
        return when {
            value == null -> VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
            value.isNaN() -> VoiceCommandSession.Reading(null, VoiceAnswer.Reason.NO_DATA_YET)
            // The dashboard's own formatter, so a spoken value carries the same
            // unit in the same rider's units as the tile they would have read.
            else -> VoiceCommandSession.Reading(formatMetric(term.key, value, settings), null)
        }
    }

    /**
     * One value, in the rider's units, formatted the way the tile formats it.
     *
     * Lifted out because the statistic path needs exactly the same treatment:
     * a max speed that arrived in km/h while the tile said mph would be the
     * unit bug this feature has already had once.
     */
    private fun formatMetric(
        key: String,
        raw: Float,
        settings: com.eried.eucplanet.data.model.AppSettings,
    ): String = com.eried.eucplanet.data.model.MetricValueFormat.format(
        key = key,
        raw = raw,
        speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings),
        speedUnitLabel = com.eried.eucplanet.util.Units.speedUnit(
            context, com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
        ),
        tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings),
        tempUnitLabel = com.eried.eucplanet.util.Units.tempUnit(
            com.eried.eucplanet.util.Units.effectiveTempUnit(settings)
        ),
        distanceUnit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings),
        pressureUnit = com.eried.eucplanet.util.Units.effectivePressureUnit(settings),
    )

    /**
     * Say the reply, and report whether anything was actually said.
     *
     * The return value is what tells the session whether to wait for speech
     * and close with a chirp. A rider who turned the unrecognised-phrase
     * sentence off is not waiting for a voice, and treating that the same as
     * a spoken answer left a second of dead air at the end of every miss.
     */
    private fun speak(answer: Answer, settings: com.eried.eucplanet.data.model.AppSettings): Boolean {
        // The name came out of the vocabulary the rider speaks. When they are
        // answered in another language it has to cross over, or a Russian
        // sentence arrives with an English word in the middle of it.
        fun said(name: String): String = spokenNames[name] ?: name
        val text = when (answer) {
            is Answer.Say -> "${said(answer.name)}, ${answer.value}"
            // Already a whole sentence, name included.
            is Answer.SayReport -> answer.text
            is Answer.Act -> if (answer.confirm) {
                voiceCtx.getString(R.string.voice_action_confirm, said(answer.name))
            } else {
                said(answer.name)
            }
            is Answer.Unavailable -> voiceCtx.getString(reasonRes(answer.reason), said(answer.name))
            is Answer.NotUnderstood -> voiceCtx.getString(
                R.string.voice_answer_unknown,
                answer.helpPhrase,
            )
            // The list goes on screen, so the spoken half says where to look
            // rather than reciting three of fifty names. Reading examples out
            // was the answer when there was nothing to show.
            is Answer.Examples -> voiceCtx.getString(R.string.voice_answer_examples)
            is Answer.NeedsChoice -> voiceCtx.getString(
                R.string.voice_answer_which,
                answer.names.getOrElse(0) { "" },
                answer.names.getOrElse(1) { "" },
            )
        }
        _state.value = UiState.Spoke(text)
        if (answer is Answer.Examples) _showVocabulary.tryEmit(Unit)

        // "I did not catch that. Say what can I say for help." is the right
        // sentence the first three times and a scolding by the twentieth, so
        // a rider can trade it for a low two-note fall or for nothing. The
        // words stay on the tile either way: the ear is what gets tired of
        // them, and a rider glancing down still deserves the explanation.
        val quiet = answer is Answer.NotUnderstood &&
            settings.voiceCommands.unknownCue != com.eried.eucplanet.data.model.VoiceCommandSettings.UNKNOWN_MESSAGE
        if (quiet) {
            if (settings.voiceCommands.unknownCue == com.eried.eucplanet.data.model.VoiceCommandSettings.UNKNOWN_BEEP) {
                scope.launch { tonePlayer.playErrorPrompt() }
            }
            Log.i(TAG, "not understood, said nothing (${settings.voiceCommands.unknownCue})")
            return false
        }

        // An action is the one answer that does something. It runs after the
        // sentence is chosen so the rider hears the acknowledgement and the
        // wheel acts at the same moment, rather than acting into silence.
        if (answer is Answer.Act && !answer.confirm) {
            scope.launch { runAction(answer.key) }
        }

        // One sentence, one way out. The report route used to live here and
        // was unreachable: it only ran for Answer.Say, and a report-backed
        // term never produced one. Building the sentence in reading() instead
        // means there is nothing left to choose between at this point.
        voiceService.speak(text)

        // The log already carries what was heard. Without what was said, a
        // rider reporting "it answered the wrong thing" leaves us guessing
        // whether the matcher picked the wrong term or the value was wrong.
        if (answer is Answer.Act && answer.confirm) {
            scope.launch { confirmAndRun(answer, settings) }
        }

        val via = if (answer is Answer.SayReport) "report" else "answer"
        Log.i(TAG, "said \"$text\" ($via)")
        com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("Voice: said \"$text\" ($via)")
        return true
    }

    /**
     * The sound that says the microphone is open, if the rider kept one.
     *
     * The spoken alternative is not played here: it has to come before the
     * microphone opens, so [listen] handles it and this is left with the
     * tone and with silence.
     */
    private suspend fun openingCue(settings: com.eried.eucplanet.data.model.AppSettings) {
        if (settings.voiceCommands.promptCue == com.eried.eucplanet.data.model.VoiceCommandSettings.CUE_BEEP) {
            tonePlayer.playPrompt()
        }
    }

    /**
     * The falling half of the pair, and only for the rider who kept the pair.
     *
     * Silent for the spoken cue as well as for none: a session that opened
     * with a word closes with the answer, and a chirp after it would be a
     * third sound in a conversation that already has two.
     */
    private suspend fun closingCue(settings: com.eried.eucplanet.data.model.AppSettings) {
        if (settings.voiceCommands.promptCue == com.eried.eucplanet.data.model.VoiceCommandSettings.CUE_BEEP) {
            tonePlayer.playEndPrompt()
        }
    }

    /**
     * The language the app speaks in, and the language it listens in.
     *
     * Three languages, not one. The interface can be English while the voice
     * is Russian: that used to leave the command words in English and the
     * recogniser listening for Russian, so nothing a rider said ever matched
     * and the feature looked broken rather than mismatched. Resolved once per
     * session rather than threaded through twenty call sites, which is safe
     * because a session is the only thing running: a second press stops the
     * first rather than starting beside it.
     */
    private var voiceCtx: Context = context
    private var heardCtx: Context = context

    /**
     * The map from a heard name to the same thing in the speaking voice.
     *
     * Empty whenever the two languages agree, which is the default and the
     * common case. When a rider deliberately speaks one language and is
     * answered in another, "speed" has to come back as the Russian word
     * inside a Russian sentence, and the keys are the only thing the two
     * vocabularies share.
     */
    private var spokenNames: Map<String, String> = emptyMap()

    /** What the rider speaks: their own choice, or the speaking voice. */
    private fun recognitionTag(settings: com.eried.eucplanet.data.model.AppSettings): String =
        settings.voiceCommands.recognitionLocale.ifBlank { settings.voiceLocale }

    private fun applyLocales(settings: com.eried.eucplanet.data.model.AppSettings) {
        voiceCtx = localized(settings.voiceLocale)
        val heardTag = recognitionTag(settings)
        heardCtx = if (heardTag == settings.voiceLocale) voiceCtx else localized(heardTag)
        spokenNames = if (heardCtx === voiceCtx) {
            emptyMap()
        } else {
            val heard = vocabulary()
            val spoken = run {
                val saved = heardCtx
                heardCtx = voiceCtx
                try { vocabulary() } finally { heardCtx = saved }
            }.associate { it.key to it.name }
            heard.mapNotNull { t -> spoken[t.key]?.let { t.name to it } }.toMap()
        }
    }

    /** Strings resolved in one language, whatever the interface is set to. */
    private fun localized(tag: String): Context {
        if (tag.isBlank()) return context
        val locale = java.util.Locale.forLanguageTag(tag.replace("_", "-"))
        val cfg = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(locale) }
        return context.createConfigurationContext(cfg)
    }

    /** The names a rider can say, in their language. */
    private fun vocabulary(): List<SpokenTerm> = VoiceVocabulary.build(
        metricNames = MetricCatalog.all.associate { it.key to heardCtx.getString(it.spokenLabelRes ?: it.labelRes) },
        reportNames = VoicePhrases.resolve(VoicePhrases.REPORTS) { heardCtx.getString(it) },
        splitName = heardCtx.getString(R.string.voice_split_term),
        helpPhrases = heardCtx.getString(R.string.voice_help_terms),
        actionPhrases = VoicePhrases.resolve(VoicePhrases.ACTIONS) { heardCtx.getString(it) },
        specialPhrases = VoicePhrases.resolve(VoicePhrases.SPECIALS) { heardCtx.getString(it) },
    )

    private fun reasonRes(reason: VoiceAnswer.Reason): Int = when (reason) {
        VoiceAnswer.Reason.OFF_IN_SETTINGS -> R.string.voice_answer_off
        VoiceAnswer.Reason.UNSUPPORTED_BY_WHEEL -> R.string.voice_answer_unsupported
        VoiceAnswer.Reason.NO_DATA_YET -> R.string.voice_answer_nodata
        VoiceAnswer.Reason.NEEDS_SETUP -> R.string.voice_answer_setup
    }

    private fun formatNumber(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
}

/** Report key to its translated name. */
/**
 * Terms that mean something with no wheel connected.
 *
 * The clock, the phone's own battery, whether a trip is recording, where the
 * navigation is going: none of these come off the wheel, so refusing them
 * while disconnected would be refusing a question the app can answer.
 * Everything else is a reading, and a reading with no wheel is a zero
 * pretending to be a measurement.
 */
/**
 * Actions worth asking about before doing.
 *
 * Only the ones that throw something away. Stopping a recording ends a ride
 * that cannot be resumed, and a misheard word must not be able to do that.
 * Lights, the horn and the lock cost nothing to undo, so confirming them
 * would make the feature tiring for no safety gained.
 */
/**
 * Action keys as the voice layer names them.
 *
 * Distinct from the catalog's keys because two of them have no catalog
 * equivalent: the wheel has one lights command and one lock command, and
 * "lights on" is a statement about the result rather than a request to flip.
 */
private object VoiceAction {
    const val LIGHT_ON = "V_LIGHT_ON"
    const val LIGHT_OFF = "V_LIGHT_OFF"
    const val LOCK = "V_LOCK"
    const val UNLOCK = "V_UNLOCK"

    /**
     * The periodic announcements, on and off.
     *
     * The widget has had this button since long before the app could be
     * spoken to, and it is the one a rider reaches for mid-ride: the reports
     * are welcome on an empty road and unbearable in traffic. There was no
     * word for it, so a rider wearing a helmet had to stop and find the
     * widget to silence the thing talking in their ear.
     *
     * On and off rather than a toggle, because a toggle answers a question
     * the rider cannot see the answer to. "Voice off" means off whether or
     * not it already was.
     */
    const val ANNOUNCE_ON = "V_ANNOUNCE_ON"
    const val ANNOUNCE_OFF = "V_ANNOUNCE_OFF"
}

private val CONFIRMED_ACTIONS = setOf("RECORD_STOP", "RESET_TRIP")

/** The specials that need a forecast before they can answer. */
private val WEATHER_KEYS = setOf(
    VoiceVocabulary.Special.WEATHER,
    VoiceVocabulary.Special.AIR_TEMP,
    VoiceVocabulary.Special.WIND,
    VoiceVocabulary.Special.HUMIDITY,
)

internal val OFF_WHEEL = setOf(
    "Time", "PhoneBattery", "Recording", "Navigation",
    "PHONE_BATTERY", "GPS_ALTITUDE", "GPS_SPEED", "EXTERNAL_GPS_BATTERY",
    VoiceVocabulary.HELP_KEY,
    // The specials are mostly about the world rather than the wheel, and
    // "is the wheel connected" is asked precisely when it is not.
    VoiceVocabulary.Special.WEATHER, VoiceVocabulary.Special.DAYLIGHT,
    VoiceVocabulary.Special.AIR_TEMP, VoiceVocabulary.Special.WIND,
    VoiceVocabulary.Special.HUMIDITY,
    VoiceVocabulary.Special.CONNECTED, VoiceVocabulary.Special.UPTIME,
    VoiceVocabulary.Special.NAV_NEXT, VoiceVocabulary.Special.LAST_TRIP,
    VoiceVocabulary.Special.REPORT,
)

/**
 * Metrics a wheel can be built without, and how to tell.
 *
 * Distinct from having no value yet: the rider should stop waiting rather
 * than ask again in a minute.
 */
private val UNSUPPORTED: Map<String, (com.eried.eucplanet.data.model.WheelData) -> Boolean> =
    mapOf("TIRE_PRESSURE" to { !it.hasTirePressure })

/**
 * Metrics that already have a spoken report. Routing these through
 * VoiceService means Speed and Battery are read out with the rider's units
 * and in their language, exactly as the periodic announcement says them,
 * rather than as a bare number with no unit.
 */
internal val REPORT_FOR_METRIC = mapOf(
    "SPEED" to "Speed",
    "BATTERY" to "Battery",
    "PHONE_BATTERY" to "PhoneBattery",
    "TEMPERATURE" to "Temp",
    "LOAD" to "PWM",
    "CURRENT" to "Current",
    "MOTOR_POWER" to "Power",
    "BATTERY_POWER" to "Power",
    "TRIP" to "Distance",
)

/**
 * Readings whose value is a word.
 *
 * The dashboard renders these with their own branch and a bare literal, "ON"
 * or "DRIVE", which is right for a tile and wrong for a voice: a tile is read
 * by someone looking at it and this is read out to someone who is not, so the
 * words are translated.
 *
 * Null means the wheel has not said yet, which is distinct from "off": a mode
 * of -1 is no telemetry, and answering "ride mode, lock" to that would be
 * inventing a state the wheel never reported.
 */
private val STATE_READINGS:
    Map<String, (com.eried.eucplanet.data.model.WheelData, Context) -> String?> = mapOf(
    "LIGHT_ON" to { d, c ->
        c.getString(if (d.lightOn) R.string.voice_state_on else R.string.voice_state_off)
    },
    "PC_MODE" to { d, c ->
        when (d.pcMode) {
            0 -> c.getString(R.string.voice_mode_lock)
            1 -> c.getString(R.string.voice_mode_drive)
            2 -> c.getString(R.string.voice_mode_shutdown)
            3 -> c.getString(R.string.voice_mode_idle)
            else -> null
        }
    },
)

/**
 * Catalog metrics the voice layer cannot read, and why.
 *
 * Not a wish list: every one of these needs a source this layer does not
 * have. The trip three live in the trip recorder, the GPS pair in a location
 * fix, and the rest are computed for the graphs rather than published on the
 * packet. They stay in the vocabulary because a rider who asks gets "no X
 * yet", which is true, rather than "I did not catch that", which is not.
 *
 * VoiceMetricCoverageTest holds this against the catalog, so a metric added
 * tomorrow is either readable or listed here on purpose.
 */
internal val VOICE_CANNOT_READ = setOf(
    // The trip recorder owns these, not the wheel packet.
    "TRIP_TIME", "TRIP_MAX_SPEED", "AVG_TRIP_SPEED",
    // A location fix owns these.
    "GPS_HEADING", "GPS_ACCURACY", "LAT_LONG",
    // Derived for the graphs, never published on WheelData.
    "HEADROOM", "SLOPE", "ASCENT", "DESCENT", "MOTOR_RPM",
)

/** Whether a spoken question about [key] can produce a value at all. */
internal fun voiceCanRead(key: String): Boolean =
    key in EXTRACTORS || key in STATE_READINGS || key in REPORT_FOR_METRIC

/**
 * Live values for the metrics with no spoken report of their own. Numbers
 * without units for now: the units live in the dashboard's per-key formatter,
 * and lifting them out is its own change.
 */
internal val EXTRACTORS: Map<String, (com.eried.eucplanet.data.model.WheelData) -> Float?> = mapOf(
    "VOLTAGE" to { it.voltage },
    "MOTOR_TEMP" to { it.temperatures.firstOrNull() },
    "CONTROLLER_TEMP" to { it.temperatures.getOrNull(1) },
    "BATTERY_TEMP" to { it.temperatures.getOrNull(2) },
    "ODOMETER" to { it.totalDistance },
    "TRIP_METER" to { it.tripMeterKm },
    "PITCH" to { it.pitchAngle },
    "ROLL" to { it.rollAngle },
    "G_FORCE" to { it.gForce },
    "FORWARD_G" to { it.forwardGFromSpeed },
    "LATERAL_G" to { it.accelX },
    "TORQUE" to { it.torque },
    "PHASE_CURRENT" to { it.phaseCurrent },
    "BATTERY_1" to { it.battery1Percent },
    "BATTERY_2" to { it.battery2Percent },
    "BATTERY_ENVELOPE" to { it.batteryEnvelope },
    // A wheel with no sensor reads 0, which is not a pressure. hasTirePressure
    // is the wheel saying whether it has one at all, and the rider hears
    // "your wheel does not report tyre pressure" instead of "0".
    "TIRE_PRESSURE" to { if (it.hasTirePressure) it.tirePressureKpa else null },
    "WH_CONSUMED" to { it.whConsumed },
    "REGEN_WH" to { it.whRegen },
    "WH_PER_KM" to { it.whPerKmRecent },
    "RANGE_ESTIMATE" to { it.rangeKmEstimate },
    "GPS_ALTITUDE" to { it.gpsAltitudeM },
    "GPS_SPEED" to { it.gpsSpeedKmh.takeIf { v -> v >= 0f } },
    "DYN_SPEED_LIMIT" to { it.dynamicSpeedLimit },
    "DYN_CURRENT_LIMIT" to { it.dynamicCurrentLimit },
    "WHEEL_MAX_SPEED" to { it.wheelMaxSpeedKmh.takeIf { v -> v >= 0f } },
    "WHEEL_ALARM_SPEED" to { it.wheelAlarmSpeedKmh.takeIf { v -> v >= 0f } },
    "BT_RSSI" to { it.rssiDbm.toFloat().takeIf { v -> v != 0f } },
    "EXTERNAL_GPS_BATTERY" to { it.externalGpsBatteryPercent.toFloat().takeIf { v -> v >= 0f } },
)
