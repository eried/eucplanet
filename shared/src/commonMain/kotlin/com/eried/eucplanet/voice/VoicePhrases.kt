package com.eried.eucplanet.voice

import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Every spoken phrase list, in one place, because there are two readers.
 *
 * The controller builds the vocabulary the matcher listens against, and the
 * vocabulary dialog builds the list a rider reads. They have to be the same
 * set or the page lies, and while they were two hand-written maps they drifted
 * the first time an action was added: the announce commands worked and were
 * absent from the only page that says what works.
 *
 * Rule 13. A registry rather than two lists, and a drift guard over it.
 */
object VoicePhrases {

    /** Action key to the comma-separated phrasings for it. */
    val ACTIONS: List<Pair<String, StringResource>> = listOf(
        "V_LIGHT_ON" to Res.string.voice_act_light_on_terms,
        "V_LIGHT_OFF" to Res.string.voice_act_light_off_terms,
        "V_LOCK" to Res.string.voice_act_lock_terms,
        "V_UNLOCK" to Res.string.voice_act_unlock_terms,
        // The catalog's own toggle, reached by the bare word. The on and off
        // pair above still exist for a rider who knows which way they want
        // it; this is for the one who just wants it flipped.
        "LIGHT_TOGGLE" to Res.string.voice_act_light_toggle_terms,
        "V_ANNOUNCE_ON" to Res.string.voice_act_announce_on_terms,
        "V_ANNOUNCE_OFF" to Res.string.voice_act_announce_off_terms,
        "HORN" to Res.string.voice_act_horn_terms,
        "RECORD_START" to Res.string.voice_act_record_start_terms,
        "RECORD_STOP" to Res.string.voice_act_record_stop_terms,
        "RESET_TRIP" to Res.string.voice_act_reset_trip_terms,
        // The rider's music. Catalog keys, so voice, Flic and the HUD all
        // press the same media key.
        "MUSIC_START" to Res.string.voice_act_music_start_terms,
        "MEDIA_PAUSE" to Res.string.voice_act_media_pause_terms,
        "MEDIA_NEXT" to Res.string.voice_act_media_next_terms,
        "MEDIA_PREVIOUS" to Res.string.voice_act_media_prev_terms,
    )

    /** The things the app knows that are not wheel readings. */
    val SPECIALS: List<Pair<String, StringResource>> = listOf(
        VoiceVocabulary.Special.WEATHER to Res.string.voice_sp_weather_terms,
        VoiceVocabulary.Special.AIR_TEMP to Res.string.voice_sp_air_temp_terms,
        VoiceVocabulary.Special.WIND to Res.string.voice_sp_wind_terms,
        VoiceVocabulary.Special.HUMIDITY to Res.string.voice_sp_humidity_terms,
        VoiceVocabulary.Special.DAYLIGHT to Res.string.voice_sp_daylight_terms,
        VoiceVocabulary.Special.CONNECTED to Res.string.voice_sp_connected_terms,
        VoiceVocabulary.Special.UPTIME to Res.string.voice_sp_uptime_terms,
        VoiceVocabulary.Special.NAV_NEXT to Res.string.voice_sp_nav_terms,
        VoiceVocabulary.Special.LAST_TRIP to Res.string.voice_sp_last_trip_terms,
        VoiceVocabulary.Special.REPORT to Res.string.voice_sp_report_terms,
    )

    /**
     * The spoken reports, whose keys are English identifiers.
     *
     * Their names have been translated all along under report_*, and using the
     * keys directly put "Battery" and "Distance" into a German rider's list
     * beside Akku and Energie.
     */
    val REPORTS: List<Pair<String, StringResource>> = listOf(
        "Speed" to Res.string.report_speed,
        "Battery" to Res.string.report_battery,
        "PhoneBattery" to Res.string.report_phone_battery,
        "Temp" to Res.string.report_temp,
        "PWM" to Res.string.report_pwm,
        "Current" to Res.string.report_current,
        "Power" to Res.string.report_power,
        "Distance" to Res.string.report_distance,
        "Recording" to Res.string.report_recording,
        "Time" to Res.string.report_time,
        "Navigation" to Res.string.report_navigation,
    )

    /** Resolve one list in one language: [get] reads a string in it. */
    fun resolve(
        entries: List<Pair<String, StringResource>>,
        get: (StringResource) -> String,
    ): Map<String, String> = entries.associate { (key, res) -> key to get(res) }
}
