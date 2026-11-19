package com.eried.eucplanet.voice

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.AppHealthRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.platform.getString
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource

/**
 * The phone side of the spoken commands on Android: SpeechRecognizer, audio
 * focus, the strings in a chosen language through a configuration context.
 */
class AndroidVoiceCommandPorts(
    private val context: Context,
    private val appHealth: AppHealthRepository,
    private val tripRepository: TripRepository,
    // A Lazy, not the manager itself: FlicManager injects the controller this
    // feeds, and no DI graph can build a cycle of two constructors.
    private val flicManager: Lazy<FlicManager>,
) : VoiceCommandPorts {

    private val listeningFocus by lazy {
        ListeningFocus(context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun newListener(languageTag: String): VoiceListener =
        AndroidVoiceListener(context = context, languageTag = languageTag)

    override fun holdFocus(musicWhileListening: String): Any = listeningFocus.hold(musicWhileListening)

    override fun releaseFocus(token: Any) {
        (token as? Int)?.let { listeningFocus.release(it) }
    }

    override fun releaseAllFocus() = listeningFocus.releaseAll()

    override fun stringsIn(languageTag: String): LanguageStrings = androidLanguageStrings(context, languageTag)

    override fun noteMicrophoneNeeded() = appHealth.noteMicrophoneNeeded()

    override fun microphoneGranted() = appHealth.microphoneGranted()

    override suspend fun runAction(catalogKey: String) = flicManager.value.runAction(catalogKey)

    override fun isRecording(): Boolean = tripRepository.recording.value

    override fun location(): GeoPoint? =
        (tripRepository.currentLocation.value ?: tripRepository.lastKnownLocation.value)
            ?.let { GeoPoint(it.latitude, it.longitude) }

    override suspend fun lastFinishedTrip(): TripRecord? =
        tripRepository.allTrips.first().filter { it.endTime != null }.maxByOrNull { it.startTime }
}

/** Strings in [languageTag] through a context set to it; blank is [context]'s own language. */
fun androidLanguageStrings(context: Context, languageTag: String): LanguageStrings {
    val ctx = if (languageTag.isBlank()) context else {
        val locale = java.util.Locale.forLanguageTag(languageTag.replace("_", "-"))
        val cfg = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(locale) }
        context.createConfigurationContext(cfg)
    }
    return object : LanguageStrings {
        override fun text(res: StringResource, vararg args: Any): String =
            if (args.isEmpty()) ctx.getString(res) else ctx.getString(res, *args)
    }
}
