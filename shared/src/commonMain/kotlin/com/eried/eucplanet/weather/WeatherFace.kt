package com.eried.eucplanet.weather

import com.eried.eucplanet.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The face an hour wears, and the line that goes with it.
 *
 * A registry rather than a `when` in the panel, because the home screen widgets
 * have to reach the same verdict from a saved snapshot, in a process where no
 * forecast is loaded. The widget stores [key]; both surfaces resolve it here,
 * so the face on the launcher and the face in the app can never disagree.
 *
 * Order is priority order, and priority mirrors danger: what can put a rider
 * down comes before what merely spoils the ride.
 */
enum class WeatherFace(
    /** Stable id. Persisted in the widget snapshot, so never renamed casually. */
    val key: String,
    val emoji: String,
    val textRes: StringResource,
) {
    SNOW("SNOW", "🥶", Res.string.weather_face_snow),
    RAIN("RAIN", "😬", Res.string.weather_face_rain),
    WIND("WIND", "😖", Res.string.weather_face_wind),
    COLD("COLD", "🥶", Res.string.weather_face_cold),
    HOT("HOT", "🥵", Res.string.weather_face_hot),
    GOLDEN("GOLDEN", "🌇", Res.string.weather_face_golden),
    NIGHT("NIGHT", "😴", Res.string.weather_face_night),
    CLEAR("CLEAR", "😄", Res.string.weather_face_clear),
    MEH("MEH", "😐", Res.string.weather_face_meh),
    POOR("POOR", "🙁", Res.string.weather_face_meh);

    companion object {
        fun of(b: RidabilityScore.Breakdown): WeatherFace = when {
            b.snow -> SNOW
            b.rain -> RAIN
            b.wind && b.score < 0f -> WIND
            b.cold -> COLD
            b.hot -> HOT
            b.golden -> GOLDEN
            b.night && b.score < 2f -> NIGHT
            b.score >= 2f -> CLEAR
            b.score >= -1f -> MEH
            else -> POOR
        }

        fun byKey(key: String): WeatherFace =
            entries.firstOrNull { it.key == key } ?: MEH
    }
}
